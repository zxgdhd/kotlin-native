package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Block
import org.jetbrains.kotlin.backend.common.ir.cfg.CfgNull
import org.jetbrains.kotlin.backend.common.ir.cfg.Function
import org.jetbrains.kotlin.backend.common.ir.cfg.Operand
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.konan.target.KonanTarget

internal class CodeGenerator(override val context: Context) : BitcodeSelectionUtils {

    private val blockMap = mutableMapOf<Block, LLVMBasicBlockRef>()

    val variableManager = VariableManager(this)

    private var returnSlot: LLVMValueRef? = null
    private var function: LLVMValueRef? = null
    var returnType: LLVMTypeRef? = null

    private var localAllocs = 0
    private var arenaSlot: LLVMValueRef? = null
    private var slotCount = 0
    private var slotsPhi: LLVMValueRef? = null

    lateinit var prologueBlock: LLVMBasicBlockRef
    lateinit var localsInitBlock: LLVMBasicBlockRef
    lateinit var enterBlock: LLVMBasicBlockRef
    lateinit var epilogueBlock: LLVMBasicBlockRef
    lateinit var cleanupLandningpad: LLVMBasicBlockRef

    val intPtrType = LLVMIntPtrType(llvmTargetData)!!
    private val immOneIntPtrType = LLVMConstInt(intPtrType, 1, 1)!!

    private val returns = mutableMapOf<LLVMBasicBlockRef, LLVMValueRef>()

    private var positionHolder = PositionHolder()

    val builder: LLVMBuilderRef
        get() = positionHolder.builder

    val currentBlock: LLVMBasicBlockRef
        get() = positionHolder.currentBlock

    fun getName(value: LLVMValueRef) = LLVMGetValueName(value)?.toKString()

    inner class PositionHolder {
        val builder = LLVMCreateBuilder()!!

        fun positionAtEnd(block: LLVMBasicBlockRef) {
            LLVMPositionBuilderAtEnd(builder, block)
        }

        val currentBlock: LLVMBasicBlockRef
            get() = LLVMGetInsertBlock(builder)!!

        val currentFunction: LLVMValueRef
            get() = LLVMGetBasicBlockParent(currentBlock)!!

        fun dispose() {
            LLVMDisposeBuilder(builder)
        }

        var isAfterTerminator: Boolean = false
            private set

        fun setAfterTerminator() {
            isAfterTerminator = true
        }
    }

    inline fun <R> preservingPosition(code: () -> R): R {
        val oldPositionHolder = positionHolder
        val newPositionHolder = PositionHolder()
        positionHolder = newPositionHolder
        try {
            return code()
        } finally {
            positionHolder = oldPositionHolder
            newPositionHolder.dispose()
        }
    }

    fun positionAtEnd(bbLabel: LLVMBasicBlockRef) = positionHolder.positionAtEnd(bbLabel)

    inline fun <R> appendingTo(block: LLVMBasicBlockRef, code: CodeGenerator.() -> R) = preservingPosition {
        positionHolder.positionAtEnd(block)
        code()
    }

    fun prologue(function: Function) {
        val llvmFunction = function.llvmFunction
        val returnType = getLlvmType(function.returnType)
        prologue(llvmFunction, returnType, {
            blockMap[function.enter] = LLVMAppendBasicBlock(llvmFunction, function.enter.name)!!
            blockMap[function.enter]!!
        })
    }

    fun prologue(llvmFunction:LLVMValueRef, returnType:LLVMTypeRef,
                 lazyEnterBlock: () -> LLVMBasicBlockRef) {
        if (isObjectType(returnType)) {
            returnSlot = LLVMGetParam(llvmFunction, numParameters(llvmFunction.type))
        }

        this.function = llvmFunction
        this.returnType = returnType

        prologueBlock = LLVMAppendBasicBlock(llvmFunction,"prologue")!!
        localsInitBlock = LLVMAppendBasicBlock(llvmFunction, "locals_init")!!
        enterBlock = lazyEnterBlock()
        epilogueBlock = LLVMAppendBasicBlock(llvmFunction,"epilogue")!!
        cleanupLandningpad = LLVMAppendBasicBlock(llvmFunction, "cleanup_landingpad")!!

        positionAtEnd(localsInitBlock)
        slotCount = 1
        localAllocs = 0
        slotsPhi = phi(kObjHeaderPtrPtr)
        arenaSlot = intToPtr(
                or(ptrToInt(slotsPhi, intPtrType), immOneIntPtrType), kObjHeaderPtrPtr)


    }

    fun epilogue() {
        appendingTo(prologueBlock) {
            val slots = if (needSlots)
                LLVMBuildArrayAlloca(builder, kObjHeaderPtr, Int32(slotCount).llvm, "")!!
            else
                kNullObjHeaderPtrPtr
            if (needSlots) {
                // Zero-init slots.
                val slotsMem = bitcast(kInt8Ptr, slots)
                val pointerSize = LLVMABISizeOfType(llvmTargetData, kObjHeaderPtr).toInt()
                val alignment = LLVMABIAlignmentOfType(llvmTargetData, kObjHeaderPtr)
                call(context.llvm.memsetFunction,
                        listOf(slotsMem, Int8(0).llvm,
                                Int32(slotCount * pointerSize).llvm, Int32(alignment).llvm,
                                Int1(0).llvm))
            }
            addPhiIncoming(slotsPhi!!, prologueBlock to slots)
            br(localsInitBlock)
        }

        appendingTo(localsInitBlock) {
            br(enterBlock)
        }

        appendingTo(epilogueBlock) {
            when {
                returnType == voidType -> {
                    releaseVars()
                    LLVMBuildRetVoid(builder)
                }
                returns.isNotEmpty() -> {
                    val returnPhi = phi(returnType!!)
                    addPhiIncoming(returnPhi, *returns.toList().toTypedArray())
                    if (returnSlot != null) {
                        updateReturnRef(returnPhi, returnSlot!!)
                    }
                    releaseVars()
                    LLVMBuildRet(builder, returnPhi)
                }
                else -> LLVMBuildUnreachable(builder)
            }
        }

        appendingTo(cleanupLandningpad) {
            val landingpad = gxxLandingpad(numClauses = 0)
            LLVMSetCleanup(landingpad, 1)
            releaseVars()
            LLVMBuildResume(builder, landingpad)
        }
        blockMap.clear()
        variableManager.clear()
        returns.clear()
        returnSlot = null
        slotsPhi = null
    }

    fun addPhiIncoming(phi: LLVMValueRef, vararg incoming: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
        memScoped {
            val incomingValues = incoming.map { it.second }.toCValues()
            val incomingBlocks = incoming.map { it.first }.toCValues()

            LLVMAddIncoming(phi, incomingValues, incomingBlocks, incoming.size)
        }
    }

    val kVoidFuncType : LLVMTypeRef = LLVMFunctionType(LLVMVoidType(), null, 0, 0)!!
    val kInitFuncType : LLVMTypeRef = LLVMFunctionType(LLVMVoidType(), cValuesOf(LLVMInt32Type()), 1, 0)!!
    val kNodeInitType : LLVMTypeRef = LLVMGetTypeByName(context.llvmModule, "struct.InitNode")!!
    val kImmZero      : LLVMValueRef = LLVMConstInt(LLVMInt32Type(),  0, 1)!!
    val kImmOne       : LLVMValueRef = LLVMConstInt(LLVMInt32Type(),  1, 1)!!
    val kTrue         : LLVMValueRef = LLVMConstInt(LLVMInt1Type(),   1, 1)!!
    val kFalse        : LLVMValueRef = LLVMConstInt(LLVMInt1Type(),   0, 1)!!

    fun icmpEq(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntEQ,  arg0, arg1, name)!!
    fun icmpGt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSGT, arg0, arg1, name)!!
    fun icmpGe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSGE, arg0, arg1, name)!!
    fun icmpLt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSLT, arg0, arg1, name)!!
    fun icmpLe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSLE, arg0, arg1, name)!!
    fun icmpNe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntNE,  arg0, arg1, name)!!

    fun plus  (arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildAdd (builder, arg0, arg1, name)!!
    fun mul   (arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildMul (builder, arg0, arg1, name)!!
    fun minus (arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildSub (builder, arg0, arg1, name)!!
    fun div   (arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildSDiv(builder, arg0, arg1, name)!!
    fun srem  (arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildSRem(builder, arg0, arg1, name)!!

    fun intToPtr(value: LLVMValueRef?, DestTy: LLVMTypeRef, Name: String = "") = LLVMBuildIntToPtr(builder, value, DestTy, Name)!!
    fun ptrToInt(value: LLVMValueRef?, DestTy: LLVMTypeRef, Name: String = "") = LLVMBuildPtrToInt(builder, value, DestTy, Name)!!

    fun or(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef = LLVMBuildOr (builder, arg0, arg1, name)!!

    fun bitcast(type: LLVMTypeRef?, value: LLVMValueRef, name: String = "") = LLVMBuildBitCast(builder, value, type, name)!!

    fun param(func: LLVMValueRef, paramNum: Int): LLVMValueRef {
        return LLVMGetParam(func, paramNum)!!
    }

    fun gep(base: LLVMValueRef, index: LLVMValueRef, name: String = ""): LLVMValueRef {
        return LLVMBuildGEP(builder, base, cValuesOf(index), 1, name)!!
    }

    fun alloca(type: LLVMTypeRef, name: String = ""): LLVMValueRef {
        if (isObjectType(type)) {
            appendingTo(localsInitBlock) {
                return gep(slotsPhi!!, Int32(slotCount++).llvm, name)
            }
        }
        appendingTo(prologueBlock) {
            return LLVMBuildAlloca(builder, type, name)!!
        }
    }

    fun load(address: LLVMValueRef, name: String = ""): LLVMValueRef
            = LLVMBuildLoad(builder, address, name)!!

    fun loadSlot(address: LLVMValueRef, isVar: Boolean, name: String = "") : LLVMValueRef {
        val value = LLVMBuildLoad(builder, address, name)!!
        if (isObjectRef(value) && isVar) {
            val slot = alloca(LLVMTypeOf(value)!!)
            storeAnyLocal(value, slot)
        }
        return value
    }

    fun store(value: LLVMValueRef, ptr: LLVMValueRef) {
        LLVMBuildStore(builder, value, ptr)
    }

    fun storeAnyLocal(value: LLVMValueRef, ptr: LLVMValueRef) {
        if (isObjectRef(value)) {
            updateRef(value, ptr)
        } else {
            LLVMBuildStore(builder, value, ptr)
        }
    }

    fun phi(type: LLVMTypeRef, name: String = ""): LLVMValueRef {
        return LLVMBuildPhi(builder, type, name)!!
    }

    fun updateReturnRef(value: LLVMValueRef, address: LLVMValueRef) {
        call(context.llvm.updateReturnRefFunction, listOf(address, value))
    }

    fun updateRef(value: LLVMValueRef, address: LLVMValueRef, ignoreOld: Boolean = false) {
        call(if (ignoreOld) context.llvm.setRefFunction else context.llvm.updateRefFunction,
                listOf(address, value))
    }

    private fun createArgs(callee: LLVMValueRef,
                           args: List<LLVMValueRef>,
                           resultLifetime: Lifetime) = if (isObjectReturn(callee.type)) {
            // If function returns an object - create slot for the returned value or give local arena.
            // This allows appropriate rootset accounting by just looking at the stack slots,
            // along with ability to allocate in appropriate arena.
            val resultSlot = when (resultLifetime.slotType) {
                SlotType.ARENA -> {
                    localAllocs++
                    arenaSlot!!
                }
                SlotType.RETURN -> returnSlot!!
            // TODO: for RETURN_IF_ARENA choose between created slot and arenaSlot
            // dynamically.
                SlotType.ANONYMOUS, SlotType.RETURN_IF_ARENA -> variableManager.createAnonymousSlot()
                else -> throw Error("Incorrect slot type")
            }
            (args + resultSlot)
        } else {
            args
        }

    fun allocInstance(typeInfo: LLVMValueRef): LLVMValueRef =
            call(context.llvm.allocInstanceFunction, listOf(typeInfo), Lifetime.LOCAL)

    fun call(callee: LLVMValueRef,
             args: List<LLVMValueRef>,
             resultLifetime: Lifetime = Lifetime.IRRELEVANT): LLVMValueRef {
        val callArgs = createArgs(callee, args, resultLifetime)
        // toCValues changes array size. Why?
        return LLVMBuildCall(builder, callee, callArgs.toCValues(), callArgs.size, "")!!
    }

    fun invoke(callee: LLVMValueRef,
               args: List<LLVMValueRef>,
               successBlock: LLVMBasicBlockRef,
               landingpad: LLVMBasicBlockRef? = cleanupLandningpad,
               resultLifetime: Lifetime = Lifetime.IRRELEVANT): LLVMValueRef {
        val callArgs = createArgs(callee, args, resultLifetime)
        positionHolder.setAfterTerminator()
        return LLVMBuildInvoke(builder, callee, callArgs.toCValues(), callArgs.size, successBlock, landingpad, "")!!
    }

    fun appendBasicBlock(): LLVMBasicBlockRef = LLVMAppendBasicBlock(function, "")!!

    fun llvmBlockFor(block: Block): LLVMBasicBlockRef = blockMap.getOrPut(block) {
        val blockRef = LLVMInsertBasicBlock(currentBlock, block.name)!!
        LLVMMoveBasicBlockAfter(blockRef, currentBlock)
        blockRef
    }

    fun br(targetBlock: LLVMBasicBlockRef) {
        positionHolder.setAfterTerminator()
        LLVMBuildBr(builder, targetBlock)
    }

    fun condbr(condition: LLVMValueRef?, bbTrue: LLVMBasicBlockRef?, bbFalse: LLVMBasicBlockRef?) {
        positionHolder.setAfterTerminator()
        LLVMBuildCondBr(builder, condition, bbTrue, bbFalse)
    }

    fun ret(value: LLVMValueRef?) {
        positionHolder.setAfterTerminator()
        LLVMBuildBr(builder, epilogueBlock)!!
        if (value != null) {
            returns[currentBlock] = value
        }
    }

    fun gxxLandingpad(numClauses: Int, name: String = ""): LLVMValueRef {
        val personalityFunction = LLVMConstBitCast(context.llvm.gxxPersonalityFunction, int8TypePtr)

        // Type of `landingpad` instruction result (depends on personality function):
        val landingpadType = structType(int8TypePtr, int32Type)

        return LLVMBuildLandingPad(builder, landingpadType, personalityFunction, numClauses, name)!!
    }

    private val needSlots: Boolean
        get() {
            return slotCount > 1 || localAllocs > 0 ||
                    // Prevent empty cleanup on mingw to workaround LLVM bug:
                    context.config.targetManager.target == KonanTarget.MINGW
        }

    private fun releaseVars() {
        if (needSlots) {
            call(context.llvm.leaveFrameFunction,
                    listOf(slotsPhi!!, Int32(slotCount).llvm))
        }
    }

    fun isAfterTerminator(): Boolean = positionHolder.isAfterTerminator

    fun unreachable() {
        LLVMBuildUnreachable(builder)
    }

    fun functionHash(function: Function): LLVMValueRef
            = context.cfgDeclarations.funcMetas[function]!!.symbol.localHash.llvm
}