package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Function
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.library.impl.buildLibrary
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.name.Name


internal fun emitBitcodeFromCfg(context: Context) {
    val module = LLVMModuleCreateWithName("out")!!
    context.llvmModule = module
}

internal class CfgToBitcode(
        val ir: Ir,
        override val context: Context
) : BitcodeSelectionUtils {
    private val codegen = CodeGenerator(context)
    private val variableManager = VariableManager(codegen)

    private val kVoidFuncType : LLVMTypeRef = LLVMFunctionType(LLVMVoidType(), null, 0, 0)!!
    private val kInitFuncType : LLVMTypeRef = LLVMFunctionType(LLVMVoidType(), cValuesOf(LLVMInt32Type()), 1, 0)!!
    private val kNodeInitType : LLVMTypeRef = LLVMGetTypeByName(context.llvmModule, "struct.InitNode")!!
    private val kImmZero      : LLVMValueRef = LLVMConstInt(LLVMInt32Type(),  0, 1)!!
    private val kImmOne       : LLVMValueRef = LLVMConstInt(LLVMInt32Type(),  1, 1)!!
    private val kTrue         : LLVMValueRef = LLVMConstInt(LLVMInt1Type(),   1, 1)!!
    private val kFalse        : LLVMValueRef = LLVMConstInt(LLVMInt1Type(),   0, 1)!!

    private val objects = mutableSetOf<LLVMValueRef>()

    init {
        context.cfgLlvmDeclarations = createCfgLlvmDeclarations(context)
    }

    fun select() {
        ir.functions.values.forEach { selectFunction(it) }

        val fileName = "TODO"
        val initName = "${fileName}_init_${context.llvm.globalInitIndex}"
        val nodeName = "${fileName}_node_${context.llvm.globalInitIndex}"
        val ctorName = "${fileName}_ctor_${context.llvm.globalInitIndex++}"

        val initFunction = createInitBody(initName)
        val initNode = createInitNode(initFunction, nodeName)
        createInitCtor(ctorName, initNode)

        appendEntryPointSelector(ir.functions.values.find { it.name == "main" }!!)
    }

    private fun appendEntryPointSelector(entry: Function) {
        val entryPoint = entry.llvmFunction
        val selectorName = "EntryPointSelector"
        val entryPointType = getFunctionType(entryPoint)!!
        val selector = entryPointSelector(entryPoint, entryPointType, selectorName)

        LLVMSetLinkage(selector, LLVMLinkage.LLVMExternalLinkage)
    }

    fun entryPointSelector(entryPoint: LLVMValueRef,
                           entryPointType: LLVMTypeRef, selectorName: String): LLVMValueRef {

        assert(LLVMCountParams(entryPoint) == 1)

        val selector = LLVMAddFunction(context.llvmModule, selectorName, entryPointType)!!
        codegen.prologue(selector, voidType)

        // Note, that 'parameter' is an object reference, and as such, shall
        // be accounted for in the rootset. However, current object management
        // scheme for arguments guarantees, that reference is being held in C++
        // launcher, so we could optimize out creating slot for 'parameter' in
        // this function.
        val parameter = LLVMGetParam(selector, 0)!!
        codegen.callAtFunctionScope(entryPoint, listOf(parameter), Lifetime.IRRELEVANT)

        codegen.ret(null)
        codegen.epilogue()
        return selector
    }

    fun createInitBody(initName: String): LLVMValueRef {
        val initFunction = LLVMAddFunction(context.llvmModule, initName, kInitFuncType)!!
        codegen.prologue(initFunction, voidType)

        val bbInit = codegen.basicBlock("init")
        val bbDeinit = codegen.basicBlock("deinit")
        codegen.condBr(codegen.icmpEq(LLVMGetParam(initFunction, 0)!!, kImmZero), bbDeinit, bbInit)

        codegen.appendingTo(bbDeinit) {
            context.llvm.fileInitializers.forEach {
                val irField = it as IrField
                val descriptor = irField.descriptor
                if (descriptor.type.isValueType()) {
                    return@forEach
                }
                val globalPtr = context.llvmDeclarations.forStaticField(descriptor).storage
                codegen.storeAnyGlobal(codegen.kNullObjHeaderPtr, globalPtr)
            }
            objects.forEach { codegen.storeAnyGlobal(codegen.kNullObjHeaderPtr, it) }
            codegen.ret(null)
        }

        codegen.appendingTo(bbInit) {
            context.llvm.fileInitializers.forEach {
                val irField = it as IrField
                val descriptor = irField.descriptor
//                val initialization =
//                val globalPtr = context.llvmDeclarations.forStaticField(descriptor).storage
//                codegen.storeAnyGlobal(initialization, globalPtr)
            }
            codegen.ret(null)
        }
        codegen.epilogue()

        return initFunction
    }

    fun createInitNode(initFunction: LLVMValueRef, nodeName: String): LLVMValueRef {
        val nextInitNode = LLVMConstNull(pointerType(kNodeInitType))
        val argList = cValuesOf(initFunction, nextInitNode)
        val initNode = LLVMConstNamedStruct(kNodeInitType, argList, 2)!!
        return context.llvm.staticData.placeGlobal(nodeName, constPointer(initNode)).llvmGlobal
    }

    fun createInitCtor(ctorName: String, initNodePtr: LLVMValueRef) {
        val ctorFunction = LLVMAddFunction(context.llvmModule, ctorName, kVoidFuncType)!!
        codegen.prologue(ctorFunction, voidType)
        codegen.call(context.llvm.appendToInitalizersTail, listOf(initNodePtr))
        codegen.ret(null)
        codegen.epilogue()
        context.llvm.staticInitializers.add(ctorFunction)
    }

    fun selectFunction(function: Function) {
        // TODO: handle init func
        if (function.name == "global-init") {
            return
        }
        val llvmFunction = function.llvmFunction
        codegen.prologue(llvmFunction, voidType)
        selectBlock(function.enter)
        if (!codegen.isAfterTerminator()) {
            if (codegen.returnType == voidType)
                codegen.ret(null)
            else
                codegen.unreachable()
        }
        codegen.epilogue()
    }

    private fun selectBlock(block: Block) {
        val basicBlock = codegen.basicBlock(block.name)
        codegen.br(basicBlock)
        codegen.positionAtEnd(basicBlock)
        block.instructions.forEach { selectInstruction(it) }
    }

    private fun selectInstruction(instruction: Instruction) {
        when (instruction) {
            is Call -> selectCall(instruction)
            is Ret -> selectRet(instruction)
            is Mov  -> selectMov(instruction)
        }
    }

    private fun selectMov(mov: Mov) {
        val variable = selectVariable(mov.def)
        variableManager.store(selectOperand(mov.use), variableManager.indexOf(mov.def))
    }

    private fun selectRet(ret: Ret) {
        // TODO: use ret value
        codegen.ret(null)
    }

    private fun selectCall(call: Call) {
        codegen.callAtFunctionScope(
                call.callee.llvmFunction,
                call.args.map { selectOperand(it)},
                Lifetime.IRRELEVANT
        )
    }

    fun selectConst(const: Constant): LLVMValueRef {
        return when(const.type) {
            Type.boolean -> if (const.value as Boolean) kTrue else kFalse
            Type.byte -> LLVMConstInt(LLVMInt8Type(), (const.value as Byte).toLong(), 1)!!
            Type.char -> LLVMConstInt(LLVMInt16Type(), (const.value as Char).toLong(), 0)!!
            Type.short -> LLVMConstInt(LLVMInt16Type(), (const.value as Short).toLong(), 1)!!
            Type.int -> LLVMConstInt(LLVMInt32Type(), (const.value as Int).toLong(), 1)!!
            Type.long -> LLVMConstInt(LLVMInt64Type(), (const.value as Long).toLong(), 1)!!
            Type.float -> LLVMConstRealOfString(LLVMFloatType(), (const.value as Float).toString())!!
            Type.double -> LLVMConstRealOfString(LLVMDoubleType(), (const.value as Double).toString())!!
            TypeString -> context.llvm.staticData.kotlinStringLiteral(
                    context.builtIns.stringType, const.value as String).llvm
            TypeUnit -> kTrue //codegen.theUnitInstanceRef.llvm // TODO: Add support for unit const
            else            -> TODO("Const ${const.type} is not implemented yet")
        }
    }

    fun selectVariable(variable: Variable): LLVMValueRef {
        val indexOf = variableManager.indexOf(variable)
        val index = if (indexOf < 0) {
            variableManager.createVariable(variable)
        } else {
            indexOf
        }
        return variableManager.load(index)
    }

    fun selectOperand(it: Operand): LLVMValueRef {
        return when(it) {
            is Variable -> selectVariable(it)
            is Constant -> selectConst(it)
            else        -> error("Unexpected operand type")
        }
    }
}