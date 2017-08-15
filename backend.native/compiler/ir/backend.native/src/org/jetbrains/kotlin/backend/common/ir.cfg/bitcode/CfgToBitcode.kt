package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Function
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.llvm.getFunctionType
import org.jetbrains.kotlin.backend.konan.llvm.voidType


internal fun emitBitcodeFromCfg(context: Context) {
    val module = LLVMModuleCreateWithName("out")!!
    context.llvmModule = module
}

internal class CfgToBitcode(
        val ir: Ir,
        override val context: Context
) : BitcodeSelectionUtils {
    private val codegen = CodeGenerator(context)

    private val variableManager: VariableManager
        get() = codegen.variableManager

    private val registers = mutableMapOf<Variable, LLVMValueRef>()

    init {
        context.cfgLlvmDeclarations = createCfgLlvmDeclarations(context)
    }

    fun select() {
        ir.functions.values.forEach { selectFunction(it) }
        val main = ir.functions.values.find { it.name == "main" }!!
        val entryFunction = entryPointSelector(
                main.llvmFunction,
                getFunctionType(main.llvmFunction),
                "EntryPointSelector"
        )

        LLVMSetLinkage(entryFunction, LLVMLinkage.LLVMExternalLinkage)
    }

    fun entryPointSelector(entryPoint: LLVMValueRef,
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


    fun selectFunction(function: Function) {
        // TODO: handle init func
        if (function.name == "global-init") {
            return
        }
        codegen.prologue(function)
        function.parameters.forEachIndexed { paramNum, arg ->
            registers[arg] = codegen.param(function.llvmFunction, paramNum)
        }
        function.blocks.forEach { selectBlock(it) }
        codegen.epilogue()
        registers.clear()
    }

    private fun selectBlock(block: Block) {
        context.log { "Selecting block ${block.name}" }
        codegen.appendingTo(codegen.llvmBlockFor(block)) {
            block.instructions.forEach { selectInstruction(it) }

            // TODO: add return on cfg
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
            is Call     -> selectCall(instruction)
//            is Invoke   -> selectInvoke(instruction)
            is Ret      -> selectRet(instruction)
            is Br       -> selectBr(instruction)
            is Condbr   -> selectCondbr(instruction)
            is Store    -> selectStore(instruction)
            is Alloc    -> selectAlloc(instruction)
            is GT0      -> selectGT0(instruction)
            is LT0      -> selectLT0(instruction)
            is BinOp    -> selectBinOp(instruction)
        }
    }

    private fun selectBinOp(binOp: BinOp) {
        registers[binOp.def] = when (binOp) {
            is BinOp.Add    -> codegen::plus
            is BinOp.Srem   -> codegen::srem
            is BinOp.Sub    -> codegen::minus
            is BinOp.Mul    -> codegen::mul
            is BinOp.Sdiv   -> codegen::div
            else -> error("$binOp is not implemented yet")
        }(selectOperand(binOp.op1), selectOperand(binOp.op2), "")
    }

    private fun selectLT0(lt0: LT0) {
        registers[lt0.def] = codegen.icmpLt(selectOperand(lt0.arg), codegen.kImmZero)
    }

    private fun selectGT0(gt0: GT0) {
        registers[gt0.def] = codegen.icmpGt(selectOperand(gt0.arg), codegen.kImmZero)
    }

    private fun selectAlloc(alloc: Alloc) {
        registers[alloc.def] = variableManager.addressOf(variableManager.createVariable(alloc.def))
    }

    private fun selectStore(store: Store) {
        val value = selectOperand(store.value)
        val address = store.address.address
        codegen.store(value, address)
    }

    private fun selectInvoke(invoke: Invoke): Nothing {
        TODO("INVOKE is not implemented yet")
    }

    private fun selectBr(br: Br) = codegen.br(codegen.llvmBlockFor(br.target))

    private fun selectCondbr(condbr: Condbr) = codegen.condbr(
            selectOperand(condbr.condition),
            codegen.llvmBlockFor(condbr.targetTrue),
            codegen.llvmBlockFor(condbr.targetFalse)
    )

    private fun selectRet(ret: Ret) = codegen.ret(selectOperand(ret.value))

    private fun selectCall(call: Call) {
        registers[call.def] = codegen.call(
                call.callee.llvmFunction,
                call.args.map { selectOperand(it) }
        )
    }

    fun selectConst(const: Constant): LLVMValueRef {
        return when(const.type) {
            Type.boolean -> if (const.value as Boolean) codegen.kTrue else codegen.kFalse
            Type.byte -> LLVMConstInt(LLVMInt8Type(), (const.value as Byte).toLong(), 1)!!
            Type.char -> LLVMConstInt(LLVMInt16Type(), (const.value as Char).toLong(), 0)!!
            Type.short -> LLVMConstInt(LLVMInt16Type(), (const.value as Short).toLong(), 1)!!
            Type.int -> LLVMConstInt(LLVMInt32Type(), (const.value as Int).toLong(), 1)!!
            Type.long -> LLVMConstInt(LLVMInt64Type(), (const.value as Long).toLong(), 1)!!
            Type.float -> LLVMConstRealOfString(LLVMFloatType(), (const.value as Float).toString())!!
            Type.double -> LLVMConstRealOfString(LLVMDoubleType(), (const.value as Double).toString())!!
            TypeString -> context.llvm.staticData.kotlinStringLiteral(
                    context.builtIns.stringType, const.value as String).llvm
            TypeUnit -> codegen.kTrue //codegen.theUnitInstanceRef.llvm // TODO: Add support for unit const
            else            -> TODO("Const ${const.type} is not implemented yet")
        }
    }

    fun selectOperand(it: Operand): LLVMValueRef = when(it) {
        is Variable -> it.value
        is Constant -> selectConst(it)
        else        -> error("Unexpected operand type")
    }

    val Variable.address: LLVMValueRef
        get() {
            val indexOf = variableManager.indexOf(this)
            if (indexOf < 0) {
                for (register in registers) {
                    println(register)
                }
                error("No address for $this")
            }
            return variableManager.addressOf(indexOf)
        }

    val Variable.value: LLVMValueRef
        get() {
            val indexOf = variableManager.indexOf(this)
            if (indexOf == -1) {
                if (registers[this] == null) {
                    for (register in registers) {
                        println(register)
                    }
                    error("No value for $this")
                } else {
                    return registers[this]!!
                }
            }
            return variableManager.load(indexOf)
        }

}