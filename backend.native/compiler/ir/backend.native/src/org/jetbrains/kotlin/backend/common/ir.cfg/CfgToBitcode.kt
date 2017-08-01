package org.jetbrains.kotlin.backend.common.ir.cfg

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanPhase
import org.jetbrains.kotlin.backend.konan.PhaseManager
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.ir.declarations.IrField


internal class CfgToBitcode(
        val ir: Ir,
        val context: Context,
        val funcDeclarations: List<Function>,
        val classDeclarations: List<Klass>,
        val funcDependencies: List<Function>
) {
    val codegen: CodeGenerator
    val module: LLVMModuleRef
    val cfgLlvmDeclarations: CfgLlvmDeclarations

    val kVoidFuncType : LLVMTypeRef
    val kInitFuncType : LLVMTypeRef
    val kNodeInitType : LLVMTypeRef
    val kImmZero      : LLVMValueRef
    val kImmOne       : LLVMValueRef
    val kTrue         : LLVMValueRef
    val kFalse        : LLVMValueRef

    private val objects = mutableSetOf<LLVMValueRef>()

    init {
        module = LLVMModuleCreateWithName("out")!!
        context.llvmModule = module
        cfgLlvmDeclarations = createLlvmDeclarations(context, funcDeclarations, funcDependencies)
        codegen = CodeGenerator(context)

        kVoidFuncType = LLVMFunctionType(LLVMVoidType(), null, 0, 0)!!
        kInitFuncType = LLVMFunctionType(LLVMVoidType(), cValuesOf(LLVMInt32Type()), 1, 0)!!
        kNodeInitType = LLVMGetTypeByName(context.llvmModule, "struct.InitNode")!!

        kImmZero     = LLVMConstInt(LLVMInt32Type(),  0, 1)!!
        kImmOne      = LLVMConstInt(LLVMInt32Type(),  1, 1)!!
        kTrue        = LLVMConstInt(LLVMInt1Type(),   1, 1)!!
        kFalse       = LLVMConstInt(LLVMInt1Type(),   0, 1)!!
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

        val program = context.config.outputName
        val output = "$program.kt.bc"
        context.bitcodeFileName = output

        PhaseManager(context).phase(KonanPhase.BITCODE_LINKER) {
            for (library in context.config.nativeLibraries) {
                val libraryModule = parseBitcodeFile(library)
                val failed = LLVMLinkModules2(module, libraryModule)
                if (failed != 0) {
                    throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
                }
            }
        }

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
        // only void types for now
        val llvmFunction = LLVMAddFunction(context.llvmModule, function.name, voidType)!!
        codegen.prologue(llvmFunction, voidType)
        selectBlock(function.enter)
        codegen.epilogue()
    }

    private fun selectBlock(block: Block) {
        val basicBlock = codegen.basicBlock(block.name)
        block.instructions.forEach { selectInstruction(it) }
    }

    private fun selectInstruction(instruction: Instruction) {
        when (instruction) {
            is Call -> selectCall(instruction)
            is Ret  -> selectRet(instruction)
        }
    }

    private fun selectRet(ret: Ret) {
        // TODO: use ret value
        codegen.ret(null)
    }

    private fun selectCall(call: Call) {
        codegen.call(call.callee.llvmFunction, call.args.map { selectOperand(it)})
    }

    fun selectConst(const: Constant): LLVMValueRef {
        return when(const.type) {
            Type.boolean    -> if (const.value as Boolean) kTrue else kFalse
            Type.byte       -> LLVMConstInt(LLVMInt8Type(), (const.value as Byte).toLong(), 1)!!
            Type.char       -> LLVMConstInt(LLVMInt16Type(), (const.value as Char).toLong(), 0)!!
            Type.short      -> LLVMConstInt(LLVMInt16Type(), (const.value as Short).toLong(), 1)!!
            Type.int        -> LLVMConstInt(LLVMInt32Type(), (const.value as Int).toLong(), 1)!!
            Type.long       -> LLVMConstInt(LLVMInt64Type(), (const.value as Long).toLong(), 1)!!
            Type.float      -> LLVMConstRealOfString(LLVMFloatType(), (const.value as Float).toString())!!
            Type.double     -> LLVMConstRealOfString(LLVMDoubleType(), (const.value as Double).toString())!!
            else            -> TODO("Const ${const.type} is not implemented yet")
        }
    }

    fun selectVariable(variable: Variable): LLVMValueRef = TODO("Not implemented yet")

    val Function.llvmFunction: LLVMValueRef
        get() = cfgLlvmDeclarations.functions[this]!!.llvmFunction

    fun selectOperand(it: Operand): LLVMValueRef {
        return when(it) {
            is Variable -> selectVariable(it)
            is Constant -> selectConst(it)
            else        -> error("Unexpected operand type")
        }
    }
}