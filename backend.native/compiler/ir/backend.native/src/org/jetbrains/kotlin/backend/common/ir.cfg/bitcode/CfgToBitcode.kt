package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.*
import org.jetbrains.kotlin.backend.common.ir.cfg.ConcreteFunction
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.llvm.*


internal fun createLlvmModule(context: Context) {
    val module = LLVMModuleCreateWithName("out")!!
    context.llvmModule = module
}

internal class CfgToBitcode(override val context: Context) : BitcodeSelectionUtils {
    private val codegen = CodeGenerator(context)

    private val registers: Registers
        get() = codegen.registers

    private val analysisResult = analyzeReturns(context.cfg.ir)

    init {
        context.cfgLlvmDeclarations = createCfgLlvmDeclarations(context)

        val rttiGenerator = RTTIGenerator(context)
        context.cfg.declarations.classMetas.forEach { klass, meta ->
            if (!meta.isExternal) {
                rttiGenerator.generate(klass)
            }
        }
    }

    fun select() {
        context.cfg.globalStaticInitializers.forEach { global, value ->
            registers.createVariable(global, value.llvm)
        }

        // TODO: handle init func
        context.cfg.ir.functions
                .filterNot { it.name == "global-init" }
                .forEach { selectFunction(it) }

        val main = context.cfg.ir.functions.find { it.name == "main" }!!
        val entryFunction = entryPointSelector(
                main.llvmFunction,
                getFunctionType(main.llvmFunction),
                "EntryPointSelector"
        )
        LLVMSetLinkage(entryFunction, LLVMLinkage.LLVMExternalLinkage)
    }

    private fun entryPointSelector(entryPoint: LLVMValueRef,
                           entryPointType: LLVMTypeRef, selectorName: String): LLVMValueRef {

        assert(LLVMCountParams(entryPoint) == 1)

        val selector = LLVMAddFunction(context.llvmModule, selectorName, entryPointType)!!
        var bb: LLVMBasicBlockRef? = null
        codegen.prologue(selector, voidType) {
            bb = LLVMAppendBasicBlock(selector, "enter")!!
            bb!!
        }
        codegen.appendingTo(bb!!) {
            val parameter = LLVMGetParam(selector, 0)!!
            codegen.call(entryPoint, listOf(parameter))
            codegen.ret(null)
        }
        codegen.epilogue()
        return selector
    }


    fun selectFunction(function: ConcreteFunction) {
        codegen.prologue(function)
        function.parameters.forEachIndexed { paramNum, arg ->
            registers[arg] = codegen.param(function.llvmFunction, paramNum)
        }
        function.blocks.forEach { selectBlock(it) }
        codegen.epilogue()
        registers.clear()
        verifyModule(context.llvmModule!!)
    }

    private fun selectBlock(block: Block) {
        codegen.appendingTo(codegen.llvmBlockFor(block)) {
            block.instructions.forEach { selectInstruction(it) }

            if (!codegen.isAfterTerminator()) {
                if (codegen.returnType == voidType)
                    codegen.ret(null)
                else
                    codegen.unreachable()
            }
        }
    }

    private fun selectInstruction(instruction: Instruction) {
        when (instruction) {
            is Call             -> this::selectCall
            is CallVirtual      -> this::selectCallVirtual
            is CallInterface    -> this::selectCallInterface
            is Invoke           -> this::selectInvoke
            is Ret              -> this::selectRet
            is Br               -> this::selectBr
            is Condbr           -> this::selectCondbr
            is Store            -> this::selectStore
            is Load             -> this::selectLoad
            is AllocStack       -> this::selectAlloc
            is FieldPtr         -> this::selectFieldPtr
            is GT0              -> this::selectGT0
            is LT0              -> this::selectLT0
            is BinOp            -> this::selectBinOp
            is AllocInstance    -> this::selectAllocInstance
            is Throw            -> this::selectThrow
            is Landingpad       -> this::selectLandingpad
            is InstanceOf       -> this::selectInstanceOf
            else                -> this::stub
        } (instruction)
    }

    private fun computeDefLifetime(instruction: Instruction): Lifetime =
        if (instruction in analysisResult[instruction.owner.owner]!!) {
            Lifetime.RETURN_VALUE
        } else {
            if (instruction.defs != listOf(CfgUnit)) Lifetime.LOCAL else Lifetime.IRRELEVANT
        }

    private fun stub(instruction: Instruction) {
        context.log { "$instruction is not supported yet" }
    }

    private fun selectFieldPtr(fieldPtr: FieldPtr) {
        assert(fieldPtr.obj.type is Type.KlassPtr)
        val klass = (fieldPtr.obj.type as Type.KlassPtr).klass
        val typePtr = pointerType(context.cfgLlvmDeclarations.classes[klass]!!.bodyType)
        val objectPtr = codegen.gep(fieldPtr.obj.llvm, Int32(1).llvm)
        val typedObjectPtr = codegen.bitcast(typePtr, objectPtr)
        registers[fieldPtr.def] = LLVMBuildStructGEP(codegen.builder, typedObjectPtr, fieldPtr.fieldIndex, "")!!
    }

    private fun selectInstanceOf(instanceOf: InstanceOf) {
        assert(instanceOf.type is Type.KlassPtr)
        val klass = (instanceOf.type as Type.KlassPtr).klass
        val typeInfoPtr = klass.typeInfoPtr.llvm
        val objInfoPtr = codegen.bitcast(codegen.kObjHeaderPtr, instanceOf.value.llvm)
        val result = codegen.call(context.llvm.isInstanceFunction, listOf(objInfoPtr, typeInfoPtr))
        registers[instanceOf.def] = LLVMBuildTrunc(codegen.builder, result, kInt1, "")!!
    }

    private fun selectThrow(thrw: Throw) {
        codegen.call(context.llvm.throwExceptionFunction, listOf(thrw.exception.llvm))
    }

    private fun selectLandingpad(landingpad: Landingpad) {
        val landingpadResult = codegen.gxxLandingpad(numClauses = 1, name = "lp")
        LLVMAddClause(landingpadResult, LLVMConstNull(kInt8Ptr))
        val exceptionRecord = LLVMBuildExtractValue(codegen.builder, landingpadResult, 0, "er")!!
        val beginCatch = context.llvm.cxaBeginCatchFunction
        val exceptionRawPtr = codegen.call(beginCatch, listOf(exceptionRecord))
        val exceptionPtrPtr = codegen.bitcast(codegen.kObjHeaderPtrPtr, exceptionRawPtr)
        val exceptionPtr = codegen.loadSlot(exceptionPtrPtr, true)
        codegen.call(context.llvm.cxaEndCatchFunction, listOf())
        registers[landingpad.exception] = exceptionPtr
    }

    private fun selectBinOp(binOp: BinOp) {
        registers[binOp.def] = when (binOp) {
            is BinOp.Add    -> codegen::plus
            is BinOp.Srem   -> codegen::srem
            is BinOp.Sub    -> codegen::minus
            is BinOp.Mul    -> codegen::mul
            is BinOp.Sdiv   -> codegen::div
            else -> error("$binOp is not implemented yet")
        } (binOp.op1.llvm, binOp.op2.llvm, "")
    }

    private fun selectLT0(lt0: LT0) {
        registers[lt0.def] = codegen.icmpLt(lt0.arg.llvm, codegen.kImmZero)
    }

    private fun selectGT0(gt0: GT0) {
        registers[gt0.def] = codegen.icmpGt(gt0.arg.llvm, codegen.kImmZero)
    }

    private fun selectAlloc(allocStack: AllocStack) {
        registers.createVariable(allocStack.def, allocStack.value?.llvm)
    }

    private fun selectAllocInstance(allocInstance: AllocInstance) {
        val lifetime = computeDefLifetime(allocInstance)
        registers[allocInstance.def] =
                codegen.allocInstance(allocInstance.klass.typeInfoPtr.llvm, lifetime)
    }

    private fun selectStore(store: Store) {
//        val value =
////        val ptr = store.address.llvm
////        val typedPtr = codegen.bitcast(pointerType(getLlvmType(store.value.type)), ptr)
////        codegen.storeAnyLocal(value, typedPtr)
        registers[store.address] = store.value.llvm
    }

    private fun selectLoad(load: Load) {
//        val ptr = load.base.llvm
//        val typedPtr = codegen.bitcast(pointerType(getLlvmType(load.def.type)), ptr)
        registers[load.def] = load.base.llvm
    }

    private fun selectInvoke(invoke: Invoke) {
        val successBlock = codegen.appendBasicBlock()
        registers[invoke.def] = codegen.invoke(
                invoke.callee.llvmFunction,
                invoke.args.map { it.llvm },
                successBlock,
                codegen.llvmBlockFor(invoke.landingpad)
        )
        codegen.positionAtEnd(successBlock)
    }

    private fun selectBr(br: Br) = codegen.br(codegen.llvmBlockFor(br.target))

    private fun selectCondbr(condbr: Condbr) = codegen.condbr(
            condbr.condition.llvm,
            codegen.llvmBlockFor(condbr.targetTrue),
            codegen.llvmBlockFor(condbr.targetFalse)
    )

    private fun selectRet(ret: Ret) {
        val value = if (ret.value == CfgUnit) {
            null
        } else {
            ret.value.llvm
        }
        codegen.ret(value)
    }

    // TODO: unify with selectCallInterface
    private fun selectCallVirtual(call: CallVirtual) {
        assert(call.args[0].type is Type.KlassPtr) { "0th arg should be Klass but it is : ${call.args[0].type}" }
        val klass = (call.args[0].type as Type.KlassPtr).klass
        val typeInfoPtrPtr = LLVMBuildStructGEP(codegen.builder, call.args[0].llvm, 0, "")
        val typeInfoPtr = codegen.load(typeInfoPtrPtr!!)
        val descriptor = context.cfg.declarations.functions
                .filterValues { it == call.callee }
                .map { it.key }
                .firstOrNull() ?: error("No declaration for ${call.callee}")
        val index = context.getVtableBuilder(klass).vtableIndex(descriptor)
        val vtablePlace = codegen.gep(typeInfoPtr, Int32(1).llvm)
        val vtable = codegen.bitcast(kInt8PtrPtr, vtablePlace)
        val slot = codegen.gep(vtable, Int32(index).llvm)
        val llvmMethod = codegen.load(slot)
        val functionPtrType = pointerType(getLlvmType(call.callee))
        val function = codegen.bitcast(functionPtrType, llvmMethod)
        call(function, call.args, call.def)
    }

    private fun selectCallInterface(call: CallInterface) {
        assert(call.args[0].type is Type.KlassPtr) { "0th arg should be Klass but it is : ${call.args[0].type}" }
        val klass = (call.args[0].type as Type.KlassPtr).klass
        val typeInfoPtrPtr = LLVMBuildStructGEP(codegen.builder, call.args[0].llvm, 0, "")
        val typeInfoPtr = codegen.load(typeInfoPtrPtr!!)
        val methodHash = codegen.functionHash(call.callee)
        val lookupArgs = listOf(typeInfoPtr, methodHash)
        val llvmMethod = codegen.call(context.llvm.lookupOpenMethodFunction, lookupArgs)
        val functionPtrType = pointerType(getLlvmType(call.callee))
        val function = codegen.bitcast(functionPtrType, llvmMethod)
        call(function, call.args, call.def)
    }

    private fun selectCall(call: Call) {
        val lifetime = computeDefLifetime(call)
        call(call.callee.llvmFunction, call.args, call.def, lifetime)
    }

    private fun call(function: LLVMValueRef, args: List<Operand>, def: Variable,
                     lifetime: Lifetime=Lifetime.IRRELEVANT) {
        val result = codegen.call(
                function, args.map { it.llvm },
                if (def != CfgUnit) lifetime else Lifetime.IRRELEVANT
        )
        if (def != CfgUnit) registers[def] = result
    }

    private fun selectConst(const: Constant): LLVMValueRef {
        return when(const.type) {
            Type.boolean        -> if (const.value as Boolean) codegen.kTrue else codegen.kFalse
            Type.byte           -> LLVMConstInt(LLVMInt8Type(), (const.value as Byte).toLong(), 1)!!
            Type.char           -> LLVMConstInt(LLVMInt16Type(), (const.value as Char).toLong(), 0)!!
            Type.short          -> LLVMConstInt(LLVMInt16Type(), (const.value as Short).toLong(), 1)!!
            Type.int            -> LLVMConstInt(LLVMInt32Type(), (const.value as Int).toLong(), 1)!!
            Type.long           -> LLVMConstInt(LLVMInt64Type(), (const.value as Long).toLong(), 1)!!
            Type.float          -> LLVMConstRealOfString(LLVMFloatType(), (const.value as Float).toString())!!
            Type.double         -> LLVMConstRealOfString(LLVMDoubleType(), (const.value as Double).toString())!!
            TypeString          -> context.llvm.staticData.kotlinStringLiteral(
                    context.builtIns.stringType, const.value as String).llvm
            TypeUnit            -> codegen.theUnitInstanceRef.llvm
//            is Type.ArrayPtr    -> codegen.staticData.createKotlinArray(const.type.type)
//            Type.GlobalPtr      -> context.cfgLlvmDeclarations.globals[const.value as Variable]?.storage
//                    ?: error("No such global ${const.value.name}")
            else                -> TODO("Const ${const.type} is not implemented yet")
        }
    }


    val Operand.llvm: LLVMValueRef
        get() = when(this) {
            CfgUnit     -> codegen.theUnitInstanceRef.llvm
            is Variable -> registers[this]
            is Constant -> selectConst(this)
            else        -> error("Unexpected operand type")
        }
}