package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.LLVMConstBitCast
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.common.ir.cfg.Function
import org.jetbrains.kotlin.backend.common.ir.cfg.Klass
import org.jetbrains.kotlin.backend.konan.llvm.*

internal interface BitcodeSelectionUtils: ContextUtils {

    val Klass.isExternal: Boolean
            get() = context.cfgDeclarations.classMetas[this]!!.isExternal

    val Function.llvmFunction: LLVMValueRef
        get() {
            val meta = context.cfgDeclarations.funcMetas[this]!!
            return if (meta.isExternal) {
                context.llvm.externalFunction(meta.symbol, getLlvmType(this))
            } else {
                context.cfgLlvmDeclarations.functions[this]?.llvmFunction!!
            }
        }

    val Function.entryPointAddress: ConstValue
        get() {
            val result = LLVMConstBitCast(this.llvmFunction, int8TypePtr)!!
            return constValue(result)
        }


    val Klass.typeInfoPtr: ConstPointer
        get() {
            return if (this.isExternal) {
                constPointer(importGlobal(this.typeInfoSymbolName, runtime.typeInfoType))
            } else {
                context.cfgLlvmDeclarations.classes[this]!!.typeInfo
            }
        }
}