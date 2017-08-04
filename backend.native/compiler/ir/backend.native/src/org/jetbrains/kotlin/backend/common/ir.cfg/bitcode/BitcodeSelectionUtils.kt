package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.LLVMTargetDataRef
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.common.ir.cfg.Function
import org.jetbrains.kotlin.backend.common.ir.cfg.FunctionMetaInfo
import org.jetbrains.kotlin.backend.common.ir.cfg.KlassMetaInfo
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.isAbstract
import org.jetbrains.kotlin.backend.konan.descriptors.isIntrinsic
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor

internal interface BitcodeSelectionUtils : RuntimeAware {
    val context: Context

    override val runtime: Runtime
        get() = context.llvm.runtime

    val llvmTargetData: LLVMTargetDataRef
        get() = runtime.targetData

    val staticData: StaticData
        get() = context.llvm.staticData

    val Function.llvmFunction: LLVMValueRef
        get() = context.cfgLlvmDeclarations.functions[this]?.llvmFunction
                ?: error("$this is undeclared for reason unknown")
}