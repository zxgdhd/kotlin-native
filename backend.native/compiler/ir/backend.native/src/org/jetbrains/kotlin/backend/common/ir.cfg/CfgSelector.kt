package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.ir.cfg.bitcode.CfgToBitcode
import org.jetbrains.kotlin.backend.common.ir.cfg.bitcode.createLlvmModule
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.isInterface
import org.jetbrains.kotlin.backend.konan.descriptors.isIntrinsic
import org.jetbrains.kotlin.backend.konan.descriptors.isUnit
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltinOperatorDescriptorBase
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isPrimitiveNumberType
import org.jetbrains.kotlin.util.OperatorNameConventions

//-----------------------------------------------------------------------------//

internal class CfgSelector(override val context: Context): IrElementVisitorVoid, TypeResolver {

    private val ir = Ir()

    private var currentLandingBlock: Block? = null
    private var currentFunction = ConcreteFunction("Outer")
    private var currentBlock    = currentFunction.enter

    // TODO: add args: Array<String> parameter
    private val globalInitFunction: ConcreteFunction = ConcreteFunction("global-init").also {
        ir.addFunction(it)
    }
    private val globalInitBlock: Block = globalInitFunction.enter

    private val variableMap = mutableMapOf<ValueDescriptor, Variable>()
    private val loopStack   = mutableListOf<LoopLabels>()

    //-------------------------------------------------------------------------//

    private fun newVariable(type: Type, kind: Kind = Kind.TMP, name: String = currentFunction.genVariableName())
            = Variable(type, name, kind)

    //-------------------------------------------------------------------------//

    private fun newBlock(name: String = "block") = currentFunction.newBlock(name)

    //-------------------------------------------------------------------------//

    private val operatorToOpcode = mapOf(
            OperatorNameConventions.PLUS  to BinOp::Add,
            OperatorNameConventions.MINUS to BinOp::Sub,
            OperatorNameConventions.TIMES to BinOp::Mul,
            OperatorNameConventions.DIV   to BinOp::Sdiv,
            OperatorNameConventions.MOD   to BinOp::Srem
    )

    private data class LoopLabels(val loop: IrLoop, val check: Block, val exit: Block)

    //-------------------------------------------------------------------------//

    fun select() {
        context.irModule!!.acceptVoid(this)
        globalInitBlock.inst(Ret())
        context.log { ir.log(); "" }

        createLlvmModule(context)

        CfgToBitcode(
                ir,
                context
        ).select()
    }

    //-------------------------------------------------------------------------//

    override fun visitClass(declaration: IrClass) {
        val klass = declaration.descriptor.cfgKlass
        ir.addKlass(klass)
        declaration.declarations.forEach {
            it.acceptVoid(this)
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitField(declaration: IrField) {
        val descriptor = declaration.descriptor

        val containingClass = descriptor.containingClass

        if (containingClass == null) {
            declaration.initializer?.expression?.let {
                val initValue = selectStatement(it)
                val fieldName = declaration.descriptor.toCfgName()
                val globalPtr = Constant(descriptor.type.cfgType, fieldName)                                  // TODO should we use special type here?
//                globalInitBlock.inst(Store(initValue, globalPtr, Cfg0))
            }
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        if (declaration.origin != IrDeclarationOrigin.FAKE_OVERRIDE) {
            selectFunction(declaration)
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectFunction(irFunction: IrFunction) {
        currentFunction = irFunction.descriptor.cfgFunction as? ConcreteFunction ?: return

        ir.addFunction(currentFunction)

        // TODO: refactor
        irFunction.descriptor.allParameters.forEach { param ->
                variableMap[param] = currentFunction.parameters.first { it.name == param.name.asString() }
            }

        irFunction.body?.let { body ->
            currentBlock = currentFunction.enter
            currentLandingBlock = null
            when (body) {
                is IrExpressionBody -> selectStatement(body.expression)
                is IrBlockBody -> body.statements.forEach { selectStatement(it) }
                else -> throw TODO("unsupported function body type: $body")
            }
//            if (!currentBlock.isLastInstructionTerminal()) {
//                // TODO last block of non unit function may not contain return
//                assert(currentFunction.returnType == TypeUnit)
//                currentBlock.inst(Ret())
//            }
        }
        variableMap.clear()
    }

    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement): Operand =
        when (statement) {
            is IrTypeOperatorCall          -> selectTypeOperatorCall         (statement)
            is IrCall                      -> selectCall                     (statement)
            is IrDelegatingConstructorCall -> selectDelegatingConstructorCall(statement)
            is IrContainerExpression       -> selectContainerExpression      (statement)
            is IrConst<*>                  -> selectConst                    (statement)
            is IrWhileLoop                 -> selectWhileLoop                (statement)
            is IrDoWhileLoop               -> selectDoWhileLoop              (statement)
            is IrBreak                     -> selectBreak                    (statement)
            is IrContinue                  -> selectContinue                 (statement)
            is IrReturn                    -> selectReturn                   (statement)
            is IrWhen                      -> selectWhen                     (statement)
            is IrSetVariable               -> selectSetVariable              (statement)
            is IrVariable                  -> selectVariable                 (statement)
            is IrValueSymbol               -> selectValueSymbol              (statement)
            is IrGetValue                  -> selectGetValue                 (statement)
            is IrVararg                    -> selectVararg                   (statement)
            is IrThrow                     -> selectThrow                    (statement)
            is IrTry                       -> selectTry                      (statement)
            is IrGetField                  -> selectGetField                 (statement)
            is IrSetField                  -> selectSetField                 (statement)
            is IrInstanceInitializerCall   -> selectInstanceInitializerCall  (statement)
            else                           -> selectionStub                  (statement)
        }

    //-------------------------------------------------------------------------//

    private fun selectionStub(statement: IrStatement): Operand {
        println("ERROR: Not implemented yet: $statement")
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectInstanceInitializerCall(statement: IrInstanceInitializerCall): Operand {
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectGetObjectValue(statement: IrGetObjectValue): Operand {
        if (statement.descriptor.isUnit()) {
            return CfgUnit
        }
        val initBlock = newBlock("label_init")
        val continueBlock = newBlock("label_continue")

        val address = Constant(Type.ptr(), "FIXME")
        val objectVal = currentBlock.inst(Load(newVariable(statement.type.cfgType), address,  false))
        val condition = currentBlock.inst(BinOp.IcmpNE(newVariable(Type.boolean), objectVal, CfgNull))
        currentBlock.inst(Condbr(condition, continueBlock, initBlock))

        currentBlock = initBlock
        // TODO: create object

        currentBlock = continueBlock
        return objectVal
    }

    //-------------------------------------------------------------------------//

    private fun selectGetField(statement: IrGetField): Operand {
        val fieldType = newVariable(statement.type.cfgType)
        val receiver = statement.receiver
        return if (receiver != null) {                                                      // It is class field.
            val thisPtr   = selectStatement(receiver)                                       // Get object pointer.
            val thisType  = receiver.type.cfgType as? Type.KlassPtr
                    ?: error("selecting GetFild on primitive type")                         // Get object type.
            val fieldIndex = thisType.klass.fieldIndex(statement.descriptor.toCfgName())
            val fieldPtr = currentBlock.inst(FieldPtr(newVariable(Type.FieldPtr), thisPtr, fieldIndex))
            currentBlock.inst(Load(fieldType, fieldPtr, statement.descriptor.isVar))
        } else {                                                                            // It is global field.
            val fieldName = statement.descriptor.toCfgName()
            val globalPtr = Constant(Type.FieldPtr, fieldName)                                  // TODO should we use special type here?
            currentBlock.inst(Load(fieldType, globalPtr, statement.descriptor.isVar))
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectSetField(statement: IrSetField): Operand {
        val value     = selectStatement(statement.value)                                    // Value to store in filed.
        val receiver  = statement.receiver                                                  // Object holding the field.
        if (receiver != null) {                                                             //
            val thisPtr = selectStatement(receiver)                                         // Pointer to the object.
            val thisType  = receiver.type.cfgType as? Type.KlassPtr
                    ?: error("selecting GetField on primitive type")                                    // Get object type.
            val fieldIndex = thisType.klass.fieldIndex(statement.descriptor.toCfgName())
            val fieldPtr = currentBlock.inst(FieldPtr(newVariable(Type.FieldPtr), thisPtr, fieldIndex))
            currentBlock.inst(Store(value, fieldPtr))
        } else {                                                                            // It is global field.
            val fieldName = statement.descriptor.toCfgName()
            val globalPtr = Constant(Type.FieldPtr, fieldName)                                  // TODO should we use special type here?
//            currentBlock.inst(Store(value, globalPtr, 0.cfg))
        }
        return CfgUnit
    }

    private fun Klass.fieldIndex(fieldName: String): Int
            = this.fields.indexOfFirst { fieldName == it.name }

    //-------------------------------------------------------------------------//

    private fun selectConst(const: IrConst<*>): Constant =
        when(const.kind) {
            IrConstKind.Null    -> CfgNull
            IrConstKind.Boolean -> Constant(Type.boolean, const.value as Boolean)
            IrConstKind.Byte    -> Constant(Type.byte,    const.value as Byte)
            IrConstKind.Short   -> Constant(Type.short,   const.value as Short)
            IrConstKind.Int     -> Constant(Type.int,     const.value as Int)
            IrConstKind.Long    -> Constant(Type.long,    const.value as Long)
            IrConstKind.Float   -> Constant(Type.float,   const.value as Float)
            IrConstKind.Double  -> Constant(Type.double,  const.value as Double)
            IrConstKind.Char    -> Constant(Type.char,    const.value as Char)
            IrConstKind.String  -> Constant(TypeString,   const.value as String)
        }

    //-------------------------------------------------------------------------//

    private fun selectTypeOperatorCall(statement: IrTypeOperatorCall): Operand =
        when (statement.operator) {
            IrTypeOperator.CAST                      -> selectCast           (statement)
            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> selectIntegerCoercion(statement)
            IrTypeOperator.IMPLICIT_CAST             -> selectImplicitCast   (statement)
            IrTypeOperator.IMPLICIT_NOTNULL          -> selectImplicitNotNull(statement)
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> selectCoercionToUnit (statement)
            IrTypeOperator.SAFE_CAST                 -> selectSafeCast       (statement)
            IrTypeOperator.INSTANCEOF                -> selectInstanceOf     (statement)
            IrTypeOperator.NOT_INSTANCEOF            -> selectNotInstanceOf  (statement)
        }

    //-------------------------------------------------------------------------//

    private fun selectCast(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type  = statement.typeOperand.cfgType
        return currentBlock.inst(Cast(newVariable(type), value))
    }

    //-------------------------------------------------------------------------//

    private fun KotlinType.isPrimitiveInteger(): Boolean {
        return isPrimitiveNumberType() &&
                !KotlinBuiltIns.isFloat(this) &&
                !KotlinBuiltIns.isDouble(this) &&
                !KotlinBuiltIns.isChar(this)
    }

    //-------------------------------------------------------------------------//

    private fun selectIntegerCoercion(statement: IrTypeOperatorCall): Operand {
        val type = statement.typeOperand
        assert(type.isPrimitiveInteger())
        val value = selectStatement(statement.argument)
        val srcType = value.type
        val dstType = type.cfgType
        val srcWidth = srcType.byteSize
        val dstWidth = dstType.byteSize
        return when {
            srcWidth == dstWidth -> value
            srcWidth >  dstWidth -> currentBlock.inst(Trunk(newVariable(dstType), value))
            else                 -> currentBlock.inst(Sext(newVariable(dstType), value))                     // srcWidth < dstWidth
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectImplicitCast(statement: IrTypeOperatorCall): Operand = selectStatement(statement.argument)

    //-------------------------------------------------------------------------//

    private fun selectImplicitNotNull(statement: IrTypeOperatorCall): Operand {
        println("ERROR: Not implemented yet: selectImplicitNotNull") // Also it's not implemented in IrToBitcode.
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectCoercionToUnit(statement: IrTypeOperatorCall): Operand {
        selectStatement(statement.argument)
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectSafeCast(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type  = statement.typeOperand.cfgType
        return currentBlock.inst(Cast(newVariable(type), value))
    }

    //-------------------------------------------------------------------------//

    private fun selectInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type = statement.typeOperand.cfgType                                        // Class to compare.
        return currentBlock.inst(InstanceOf(newVariable(Type.boolean), value, type))
    }

    //-------------------------------------------------------------------------//

    private fun selectNotInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type = statement.typeOperand.cfgType                                        // Class to compare.
        return currentBlock.inst(NotInstanceOf(newVariable(Type.boolean), value, type))
    }

    //-------------------------------------------------------------------------//

    private fun IrCall.hasOpcode() =
            descriptor.isOperator &&
            descriptor.name in operatorToOpcode &&
            dispatchReceiver?.type?.isValueType() ?: false &&
            descriptor.valueParameters.all { it.type.isValueType() }

    //-------------------------------------------------------------------------//

    private fun selectCall(irCall: IrCall): Operand = when {
        irCall.descriptor.isIntrinsic -> selectIntrinsicCall(irCall)
        irCall.descriptor is IrBuiltinOperatorDescriptorBase -> selectBuiltinOperator(irCall)
        irCall.hasOpcode() -> selectOperator(irCall)
        irCall.descriptor is ConstructorDescriptor -> selectConstructorCall(irCall)
        else -> {
            val args = irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
            if (irCall.descriptor.isOverridable && irCall.superQualifier == null) {
                generateVirtualCall(irCall.descriptor, irCall.type.cfgType, args)
            } else {
                generateCall(irCall.descriptor, irCall.type.cfgType, args)
            }
        }
    }

    private fun generateVirtualCall(descriptor: FunctionDescriptor, cfgType: Type, args: List<Operand>): Operand {
        val owner = descriptor.containingDeclaration as ClassDescriptor
        return if (!owner.isInterface) {
            currentBlock.inst(CallVirtual(descriptor.cfgFunction, newVariable(cfgType), args))
        } else {
            currentBlock.inst(CallInterface(descriptor.cfgFunction, newVariable(cfgType), args))
        }
    }

    private fun selectBuiltinOperator(irCall: IrCall): Operand {
        val descriptor = irCall.descriptor
        val ib = context.irModule!!.irBuiltins
        val args = irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
        return currentBlock.inst(when (descriptor) {
//            ib.eqeqeq     -> codegen.icmpEq(args[0], args[1])
            ib.gt0        -> GT0(newVariable(Type.boolean), args[0])
//            ib.gteq0      -> codegen.icmpGe(args[0], kImmZero)
            ib.lt0        -> LT0(newVariable(Type.boolean), args[0])
//            ib.lteq0      -> codegen.icmpLe(args[0], kImmZero)
//            ib.booleanNot -> codegen.icmpNe(args[0], kTrue)
            else -> {
                TODO(descriptor.name.toString())
            }
        })
    }

    //-------------------------------------------------------------------------//

    // TODO: maybe intrinsics should be replaced with special instructions in CFG
    private fun selectIntrinsicCall(irCall: IrCall): Operand {
        val args = irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
        val descriptor = irCall.descriptor.original
        val name = descriptor.fqNameUnsafe.asString()

        when (name) {
            "konan.internal.areEqualByValue" -> return currentBlock.inst(Builtin(newVariable(Type.boolean), name, args))
            "konan.internal.getContinuation" -> return CfgUnit // coroutines are not supported yet
        }

        val interop = context.interopBuiltIns
        return when (descriptor) {
            interop.interpretNullablePointed, interop.interpretCPointer,
            interop.nativePointedGetRawPointer, interop.cPointerGetRawValue -> args.single()

            in interop.readPrimitive -> {
                val pointerType = descriptor.returnType!!.cfgType // pointerType(codegen.getLLVMType(descriptor.returnType!!))
                val rawPointer = args.last()
                val pointer = currentBlock.inst(Bitcast(newVariable(pointerType), rawPointer, pointerType))
                currentBlock.inst(Load(newVariable(pointerType), pointer, false))
            }
            in interop.writePrimitive -> {
                val pointerType = descriptor.valueParameters.last().type.cfgType //pointerType(codegen.getLLVMType(descriptor.valueParameters.last().type))
                val rawPointer = args[1]
                val pointer = currentBlock.inst(Bitcast(newVariable(pointerType), rawPointer, pointerType))
                currentBlock.inst(Store(args[2], pointer))
                CfgUnit
            }
            context.builtIns.nativePtrPlusLong -> currentBlock.inst(Gep(newVariable(Type.ptr()), args[0], args[1]))
            context.builtIns.getNativeNullPtr -> Constant(Type.ptr(), CfgNull)
            interop.getPointerSize -> Constant(Type.int, Type.ptr().byteSize)
            context.builtIns.nativePtrToLong -> {
                val intPtrValue = Constant(Type.ptr(), args.single()) // codegen.ptrToInt(args.single(), codegen.intPtrType)
                val resultType = descriptor.returnType!!.cfgType

                if (resultType == intPtrValue.type) {
                    intPtrValue
                } else {
                    currentBlock.inst(Sext(newVariable(resultType), intPtrValue))
                }
            }
            else -> TODO(descriptor.toString())
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectConstructorCall(irCall: IrCall) : Operand {
        assert(irCall.descriptor is ConstructorDescriptor)
        val descriptor = irCall.descriptor as ConstructorDescriptor
        val constructedClass = descriptor.constructedClass.cfgKlass
        val objPtr = currentBlock.inst(AllocInstance(newVariable(irCall.type.cfgType), constructedClass))
        val args = listOf(objPtr) + irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
        generateCall(irCall.descriptor, TypeUnit, args)
        return objPtr
    }

    //-------------------------------------------------------------------------//

    private fun selectDelegatingConstructorCall(irCall: IrDelegatingConstructorCall): Operand {
        val args = mutableListOf<Operand>(currentFunction.parameters[0]).apply {
            irCall.getArguments().mapTo(this) { (_, expr) -> selectStatement(expr) }
        }
        return generateCall(irCall.descriptor, irCall.type.cfgType, args)
    }

    //-------------------------------------------------------------------------//

    private fun generateCall(descriptor: FunctionDescriptor, type: Type, args: List<Operand>): Operand {

        val callee = descriptor.cfgFunction as? ConcreteFunction
                ?: error("Cannot create direct call to abstract function $descriptor")

        val instruction = if (currentLandingBlock != null) {                    // We're inside try block.
            Invoke(callee, newVariable(type), args, currentLandingBlock!!)
        } else {
            Call(callee, newVariable(type), args)
        }
        return currentBlock.inst(instruction)
    }

    //-------------------------------------------------------------------------//

    private fun selectOperator(irCall: IrCall): Operand {
        val uses = irCall.getArguments().map { selectStatement(it.second) }
        val type = irCall.type.cfgType
        val def = newVariable(type)
        val binOp = operatorToOpcode[irCall.descriptor.name]?.invoke(def, uses[0], uses[1])
                ?: throw IllegalArgumentException("No opcode for call: $irCall")
        return currentBlock.inst(binOp)
    }

    //-------------------------------------------------------------------------//

    private fun selectContainerExpression(expression: IrContainerExpression): Operand {
        expression.statements.dropLast(1).forEach {
            selectStatement(it)
        }
        return expression.statements.lastOrNull()
            ?.let { selectStatement(it) } ?: CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectWhileLoop(irWhileLoop: IrWhileLoop): Operand {
        val loopCheck = newBlock("loop_check")
        val loopBody = newBlock("loop_body")
        val loopExit = newBlock("loop_exit")

        loopStack.push(LoopLabels(irWhileLoop, loopCheck, loopExit))

        currentBlock.inst(Br(loopCheck))
        currentBlock = loopCheck
        currentBlock.inst(Condbr(selectStatement(irWhileLoop.condition), loopBody, loopExit))

        currentBlock = loopBody
        irWhileLoop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal()) {
            currentBlock.inst(Br(loopCheck))
        }
        loopStack.pop()
        currentBlock = loopExit
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectDoWhileLoop(loop: IrDoWhileLoop): Operand {
        val loopCheck = newBlock("loop_check")
        val loopBody = newBlock("loop_body")
        val loopExit = newBlock("loop_exit")

        loopStack.push(LoopLabels(loop, loopCheck, loopExit))

        currentBlock.inst(Br(loopBody))
        currentBlock = loopBody
        loop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal()) {
            currentBlock.inst(Br(loopCheck))
        }

        currentBlock = loopCheck
        currentBlock.inst(Condbr(selectStatement(loop.condition), loopBody, loopExit))

        loopStack.pop()
        currentBlock = loopExit
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectBreak(expression: IrBreak): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, _, exit) ->
                currentBlock.inst(Br(exit))
                Unit
            }
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectContinue(expression: IrContinue): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, check, _) ->
                currentBlock.inst(Br(check))
                Unit
            }
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectReturn(irReturn: IrReturn): Operand {
        val target = irReturn.returnTarget
        val evaluated = selectStatement(irReturn.value)
        currentBlock.inst(Ret(evaluated))
        return if (target.returnsUnit()) {
            CfgNull
        } else {
            evaluated
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectWhen(expression: IrWhen): Operand {
        val resultVar = if (expression.type == context.builtIns.unitType
                || expression.type == context.builtIns.nothingType) {
            null
        } else {
            val newVariable = newVariable(expression.type.cfgType)
            currentBlock.inst(Alloc(newVariable, newVariable.type))
            newVariable
        }
        val exitBlock = newBlock()

        expression.branches.forEach {
            val nextBlock = if (it == expression.branches.last()) exitBlock else newBlock()
            selectWhenClause(it, nextBlock, exitBlock, resultVar)
        }

        currentBlock = exitBlock
        return resultVar ?: CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, variable: Variable?) {
        currentBlock = if (isUnconditional(irBranch)) {
            currentBlock
        } else {
            newBlock().also {
                currentBlock.inst(Condbr(selectStatement(irBranch.condition), it, nextBlock))
            }
        }

        val clauseExpr = selectStatement(irBranch.result)
        if (!currentBlock.isLastInstructionTerminal()) {
            variable?.let {
                currentBlock.inst(Store(clauseExpr, it))
                Unit
            }
            currentBlock.inst(Br(exitBlock))
        }
        currentBlock = nextBlock
    }

    //-------------------------------------------------------------------------//

    private fun selectSetVariable(irSetVariable: IrSetVariable): Operand {
        val operand = selectStatement(irSetVariable.value)
        currentBlock.inst(Store(operand, variableMap[irSetVariable.descriptor]!!))
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectVariable(irVariable: IrVariable): Operand {

        val (value, kind) = irVariable.initializer?.let {
            Pair(selectStatement(it), if (irVariable.descriptor.isVar) Kind.LOCAL else Kind.LOCAL_IMMUT)
        } ?: Pair(null, Kind.LOCAL)
        val variable = Variable(irVariable.descriptor.type.cfgType, irVariable.descriptor.name.asString(), kind, irVariable.descriptor.isVar)
        variableMap[irVariable.descriptor] = variable
        currentBlock.inst(Alloc(variable, variable.type, value))
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectValueSymbol(irValueSymbol: IrValueSymbol): Operand
        = variableMap[irValueSymbol.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectGetValue(getValue: IrGetValue): Operand
        = variableMap[getValue.descriptor] ?: CfgNull

    //-------------------------------------------------------------------------//

    private fun selectVararg(irVararg: IrVararg): Operand {
        val elements = irVararg.elements.map {
            if (it is IrExpression) {
                return@map selectStatement(it)
            }
            throw IllegalStateException("IrVararg neither was lowered nor can be statically evaluated")
        }
        return Constant(Type.ArrayPtr(irVararg.type.cfgType), elements)
    }

    //-------------------------------------------------------------------------//
    // Returns first catch block
    private fun selectCatches(irCatches: List<IrCatch>, tryExit: Block): Block {
        val prevBlock = currentBlock

        val header = newBlock("catch_header")
        val exception = Variable(Type.ptr(), "exception", Kind.TMP)

        header.inst(Landingpad(exception))
        currentBlock = header
        irCatches.forEach {
            val catchBody = newBlock()
            val isInstance = currentBlock.inst(InstanceOf(newVariable(Type.boolean), exception, it.parameter.type.cfgType))
            val nextCatch = if (it == irCatches.last()) tryExit else newBlock("check_for_${it.parameter.name}")
            currentBlock.inst(Condbr(isInstance, catchBody, nextCatch))
            currentBlock = catchBody
            selectStatement(it.result)
            // TODO: check for terminal instruction
            currentBlock.inst(Br(tryExit))
            currentBlock = nextCatch
        }
        currentBlock = prevBlock
        return header
    }

    //-------------------------------------------------------------------------//

    private fun selectThrow(irThrow: IrThrow): Operand {
        val evaluated = selectStatement(irThrow.value)
        currentBlock.inst(Throw(evaluated))
        return CfgUnit // TODO: replace with Nothing type
    }

    //-------------------------------------------------------------------------//

    private fun selectTry(irTry: IrTry): Operand {
        val tryExit = newBlock("try_exit")
        currentLandingBlock = selectCatches(irTry.catches, tryExit)
        val operand = selectStatement(irTry.tryResult)
        currentBlock.inst(Br(tryExit))
        currentLandingBlock = null
        currentBlock = tryExit
        return operand
    }

    //-------------------------------------------------------------------------//

    private fun CallableDescriptor.returnsUnit()
        = returnType == context.builtIns.unitType && !isSuspend

    //-------------------------------------------------------------------------//

    private fun isUnconditional(irBranch: IrBranch): Boolean =
            irBranch.condition is IrConst<*>                            // If branch condition is constant.
                    && (irBranch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
        = element.acceptChildren(this, null)
}