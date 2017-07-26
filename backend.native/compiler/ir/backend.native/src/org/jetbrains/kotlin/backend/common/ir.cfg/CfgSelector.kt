package org.jetbrains.kotlin.backend.common.ir.cfg


import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ValueType
import org.jetbrains.kotlin.backend.konan.correspondingValueType
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.isPrimitiveNumberType
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.util.OperatorNameConventions

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    private val ir = Ir()
    private val declarations      = createCfgDeclarations(context)
    private val classDependencies = mutableListOf<Klass>()
    private val funcDependencies  = mutableListOf<Function>()

    private var currentBlock                = Block("Entry")
    private var currentFunction             = Function("Outer")
    private var currentLandingBlock: Block? = null
    private var currentClass: Klass?        = null

    private val variableMap = mutableMapOf<ValueDescriptor, Operand>()
    private val loopStack   = mutableListOf<LoopLabels>()

    private val operatorToOpcode = mutableMapOf(
            OperatorNameConventions.PLUS to Opcode.add,
            OperatorNameConventions.MINUS to Opcode.sub,
            OperatorNameConventions.TIMES to Opcode.mul,
            OperatorNameConventions.DIV to Opcode.sdiv,
            OperatorNameConventions.MOD to Opcode.srem
    )

    private data class LoopLabels(val loop: IrLoop, val check: Block, val exit: Block)

    //-------------------------------------------------------------------------//

    fun select() {
        context.irModule!!.accept(this, null)
        context.log { ir.log(); "" }
    }

    //-------------------------------------------------------------------------//

    override fun visitClass(declaration: IrClass) {
        val klass = declarations.classes[declaration.descriptor]
        if (klass != null) {
            currentClass = klass
            ir.newKlass(klass)
            declaration.declarations.forEach {
                it.acceptVoid(this)
            }
            currentClass = null
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitField(declaration: IrField) {
        currentClass?.let {
            val type = declaration.symbol.descriptor.type.toCfgType()
            val name = declaration.symbol.descriptor.fqNameSafe.asString()
            declaration.initializer?.expression?.let {
                selectStatement(it)
            }
            it.fields += Variable(type, name)
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        selectFunction(declaration)
    }

    //-------------------------------------------------------------------------//

    private fun selectFunction(irFunction: IrFunction) {
        currentFunction = declarations.functions[irFunction.descriptor]
                ?: error("selecting undeclared function")
        if (currentClass != null) {
            currentClass!!.methods += currentFunction
        }
        ir.newFunction(currentFunction)

        irFunction.valueParameters
            .map {
                val variable = Variable(it.type.toCfgType(), it.descriptor.name.asString())
                variableMap[it.descriptor] = variable
                variable
            }.let(currentFunction::addValueParameters)

        irFunction.body?.let {
            currentBlock = currentFunction.enter
            currentLandingBlock = null
            when (it) {
                is IrExpressionBody -> selectStatement(it.expression)
                is IrBlockBody -> it.statements.forEach { statement ->
                    selectStatement(statement)
                }
                else -> throw TODO("unsupported function body type: $it")
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement): Operand =
        when (statement) {
            is IrTypeOperatorCall    -> selectTypeOperatorCall   (statement)
            is IrCall                -> selectCall               (statement)
            is IrContainerExpression -> selectContainerExpression(statement)
            is IrConst<*>            -> selectConst              (statement)
            is IrWhileLoop           -> selectWhileLoop          (statement)
            is IrBreak               -> selectBreak              (statement)
            is IrContinue            -> selectContinue           (statement)
            is IrReturn              -> selectReturn             (statement)
            is IrWhen                -> selectWhen               (statement)
            is IrSetVariable         -> selectSetVariable        (statement)
            is IrVariable            -> selectVariable           (statement)
            is IrVariableSymbol      -> selectVariableSymbol     (statement)
            is IrValueSymbol         -> selectValueSymbol        (statement)
            is IrGetValue            -> selectGetValue           (statement)
            is IrVararg              -> selectVararg             (statement)
            is IrThrow               -> selectThrow              (statement)
            is IrTry                 -> selectTry                (statement)
            else -> {
                println("ERROR: Not implemented yet: $statement")
                CfgNull
            }
        }

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
        val type  = statement.typeOperand.toCfgType()
        return inst(Opcode.cast, type, value)
    }

    //-------------------------------------------------------------------------//

    private fun selectIntegerCoercion(statement: IrTypeOperatorCall): Operand {
        println("ERROR: Not implemented yet: selectIntegerCoercion")
        return Variable(Type.int, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectImplicitCast(statement: IrTypeOperatorCall): Operand {
        println("ERROR: Not implemented yet: selectImplicitCast")
        return Variable(Type.int, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectImplicitNotNull(statement: IrTypeOperatorCall): Operand {
        println("ERROR: Not implemented yet: selectImplicitNotNull")
        return Variable(Type.int, "invalid")
    }

    //-------------------------------------------------------------------------//

    private fun selectCoercionToUnit(statement: IrTypeOperatorCall): Operand {
        selectStatement(statement.argument)
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectSafeCast(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val type  = statement.typeOperand.toCfgType()
        return inst(Opcode.cast, type, value)
    }

    //-------------------------------------------------------------------------//

    private fun selectInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val klass = statement.typeOperand.toCfgType()                                       // Class to compare.
        val type  = Constant(klass, klass)                                                  // Operand representing the Class.
        return inst(Opcode.instance_of, Type.boolean, value, type)
    }

    //-------------------------------------------------------------------------//

    private fun selectNotInstanceOf(statement: IrTypeOperatorCall): Operand {
        val value = selectStatement(statement.argument)                                     // Evaluate object to compare.
        val klass = statement.typeOperand.toCfgType()                                       // Class to compare.
        val type  = Constant(klass, klass)                                                  // Operand representing the Class.
        return inst(Opcode.not_instance_of, Type.boolean, value, type)
    }

    //-------------------------------------------------------------------------//

    private fun IrCall.hasOpcode() =
            descriptor.isOperator &&
            descriptor.name in operatorToOpcode &&
            dispatchReceiver?.type?.isValueType() ?: false &&
            descriptor.valueParameters.all { it.type.isValueType() }

    //-------------------------------------------------------------------------//

    private fun selectCall(irCall: IrCall): Operand {
        return if (irCall.hasOpcode()) {
            selectOperator(irCall)
        } else {
            generateCall(irCall)
        }
    }

    //-------------------------------------------------------------------------//

    private fun generateCall(irCall: IrCall): Operand {
        val funcName = irCall.descriptor.toCfgName()
        val callee   = Constant(TypeFunction, funcName)
        val args     = irCall.getArguments().map { (_, expr) -> selectStatement(expr) }
        val uses     = (listOf(callee) + args) as MutableList<Operand>

        val opcode = if (currentLandingBlock != null) {                                     // We're inside try block.
            currentBlock.addSuccessor(currentLandingBlock!!)
            uses += Constant(TypeBlock, currentLandingBlock)
            Opcode.invoke
        } else {
            Opcode.call
        }
        return inst(opcode, irCall.type.toCfgType(), *uses.toTypedArray())
    }

    //-------------------------------------------------------------------------//

    private fun selectOperator(irCall: IrCall): Operand {
        val uses = irCall.getArguments().map { selectStatement(it.second) }
        val type = irCall.type.toCfgType()
        val opcode = operatorToOpcode[irCall.descriptor.name]
                ?: throw IllegalArgumentException("No opcode for call: $irCall")
        return inst(opcode, type, *uses.toTypedArray())
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

        currentBlock.addSuccessor(loopCheck)
        currentBlock = loopCheck
        currentBlock.condBr(selectStatement(irWhileLoop.condition), loopBody, loopExit)

        currentBlock = loopBody
        irWhileLoop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal())
            currentBlock.br(loopCheck)

        loopStack.pop()
        currentBlock = loopExit
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectBreak(expression: IrBreak): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, _, exit) -> currentBlock.br(exit) }
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectContinue(expression: IrContinue): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
            .let { (_, check, _) -> currentBlock.br(check) }
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectReturn(irReturn: IrReturn): Operand {
        val target = irReturn.returnTarget
        val evaluated = selectStatement(irReturn.value)
        currentBlock.ret(evaluated)
        return if (target.returnsUnit()) {
            CfgNull
        } else {
            evaluated
        }
    }

    //-------------------------------------------------------------------------//

    private fun selectWhen(expression: IrWhen): Operand {
        val resultVar = if (expression.type == context.builtIns.unitType) {
            null
        } else {
            newVariable(expression.type.toCfgType())
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
                currentBlock.condBr(selectStatement(irBranch.condition), it, nextBlock)
            }
        }

        val clauseExpr = selectStatement(irBranch.result)
        with(currentBlock) {
            if (!isLastInstructionTerminal()) {
                variable?.let { mov(it, clauseExpr) }
                br(exitBlock)
            }
        }
        currentBlock = nextBlock
    }

    //-------------------------------------------------------------------------//

    private fun selectSetVariable(irSetVariable: IrSetVariable): Operand {
        val operand = selectStatement(irSetVariable.value)
        variableMap[irSetVariable.descriptor] = operand
        val variable = Variable(irSetVariable.value.type.toCfgType(), irSetVariable.descriptor.name.asString())
        currentBlock.mov(variable, operand)
        return CfgNull
    }

    //-------------------------------------------------------------------------//

    private fun selectVariable(irVariable: IrVariable): Operand {
        val operand = irVariable.initializer?.let { selectStatement(it) } ?: CfgNull
        variableMap[irVariable.descriptor] = operand
        return operand
    }

    //-------------------------------------------------------------------------//

    private fun selectVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
        = variableMap[irVariableSymbol.descriptor] ?: CfgNull

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
        // TODO: replace with a correct array type
        return Constant(Type.ptr(Klass("Array")), elements)
    }

    //-------------------------------------------------------------------------//
    // Returns first catch block
    private fun selectCatches(irCatches: List<IrCatch>, tryExit: Block): Block {
        val prevBlock = currentBlock

        val header = newBlock("catch_header")
        val exception = Variable(TypePtr, "exception")
        val isInstanceFunc = Variable(TypePtr, "IsInstance")

        // TODO: should expand to real exception object extraction
        header.instruction(Opcode.landingpad, exception)
        currentBlock = header
        irCatches.forEach {
            if (it == irCatches.last()) {
                selectStatement(it.result)
                currentBlock.br(tryExit)
            } else {
                val catchBody = newBlock()
                val isInstance = inst(Opcode.call, Type.boolean, isInstanceFunc, exception)
                val nextCatch = newBlock("check_for_${it.parameter.name.asString()}")
                currentBlock.condBr(isInstance, catchBody, nextCatch)
                currentBlock = catchBody
                selectStatement(it.result)
                currentBlock.br(tryExit)
                currentBlock = nextCatch
            }
        }
        currentBlock = prevBlock
        return header
    }

    //-------------------------------------------------------------------------//

    private fun selectThrow(irThrow: IrThrow): Operand {
        val evaluated = selectStatement(irThrow.value)
        // TODO: call ThrowException
//        currentBlock.invoke(currentFunction.newBlock(), )
        return CfgNull // TODO: replace with Nothing type
    }

    //-------------------------------------------------------------------------//

    private fun selectTry(irTry: IrTry): Operand {
        val tryExit = newBlock("try_exit")
        currentLandingBlock = selectCatches(irTry.catches, tryExit)
        val operand = selectStatement(irTry.tryResult)
        currentBlock.br(tryExit)
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

    fun KotlinType.toCfgType(): Type {
        if (isUnit()) return TypeUnit

        if (!isValueType()) {
            return TypeUtils.getClassDescriptor(this)?.classPtr() ?: TypeUnit
        }

        return when (correspondingValueType) {
            ValueType.BOOLEAN        -> Type.boolean
            ValueType.CHAR           -> Type.short
            ValueType.BYTE           -> Type.byte
            ValueType.SHORT          -> Type.short
            ValueType.INT            -> Type.int
            ValueType.LONG           -> Type.long
            ValueType.FLOAT          -> Type.float
            ValueType.DOUBLE         -> Type.double
            ValueType.NATIVE_PTR     -> TypePtr
            ValueType.NATIVE_POINTED -> TypePtr
            ValueType.C_POINTER      -> TypePtr
            null                     -> throw TODO("Null ValueType")
        }
    }

    //-------------------------------------------------------------------------//

    fun ClassDescriptor.classPtr(): Type {
        val klass = if (declarations.classes.contains(this)) {
            declarations.classes[this]!!
        } else {
            val clazz = Klass(this.name.asString())
            classDependencies += clazz
            clazz
        }
        return Type.ptr(klass)
    }

    //-------------------------------------------------------------------------//

    private fun newVariable(type: Type) = Variable(type, currentFunction.genVariableName())

    //-------------------------------------------------------------------------//

    private fun newBlock(name: String = "block") = currentFunction.newBlock(name)

    //-------------------------------------------------------------------------//

    private fun inst(opcode: Opcode, defType: Type, vararg uses: Operand): Operand {
        if (defType == TypeUnit) {
            currentBlock.instruction(opcode, *uses)
            return CfgNull
        } else {
            val def = newVariable(defType)
            currentBlock.instruction(opcode, def, *uses)
            return def
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
        = element.acceptChildren(this, null)
}

