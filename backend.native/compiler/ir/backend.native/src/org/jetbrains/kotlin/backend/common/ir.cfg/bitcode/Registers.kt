package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Kind
import org.jetbrains.kotlin.backend.common.ir.cfg.Variable
import org.jetbrains.kotlin.backend.konan.Context


internal class Registers(val codegen: CodeGenerator, val context: Context) {
    private val globals     = mutableMapOf<Variable, LLVMValueRef>()
    private val registers   = mutableMapOf<Variable, LLVMValueRef>()

    operator fun get(variable: Variable): LLVMValueRef {
        if (variable !in registers && variable !in globals) {
            error("Trying to access $variable that is not in registers")
        }
        return when (variable.kind) {
            Kind.GLOBAL             -> codegen.loadSlot(globals[variable]!!, variable.isVar)
            Kind.LOCAL              -> codegen.loadSlot(registers[variable]!!, variable.isVar)
            Kind.TMP, Kind.ARG,
            Kind.LOCAL_IMMUT        -> registers[variable]!!
            else                    -> error("Cannot get value for $variable")
        }

    }

    operator fun set(variable: Variable, value: LLVMValueRef) {
        when (variable.kind) {
            Kind.GLOBAL             -> codegen.storeAnyLocal(value, globals[variable]!!)
            Kind.LOCAL              -> codegen.storeAnyLocal(value, registers[variable]!!)
            Kind.TMP, Kind.ARG      -> registers[variable] = value
            else                    -> error("Cannot set variable $variable")
        }
    }

    fun createVariable(variable: Variable, initVal: LLVMValueRef?) {
        when (variable.kind) {
            Kind.LOCAL -> {
                val slot = codegen.alloca(codegen.getLlvmType(variable.type), variable.name)
                initVal?.let { codegen.storeAnyLocal(initVal, slot) }
                registers[variable] = slot
            }
            Kind.LOCAL_IMMUT -> registers[variable] = initVal!!
            Kind.TMP -> println("Alloc for TMP $variable")
            Kind.GLOBAL -> {
                val globalProperty = context.cfgLlvmDeclarations.globals[variable]?.storage
                    ?: error("Not in globals $variable")
                LLVMSetInitializer(globalProperty, initVal)
                LLVMSetLinkage(globalProperty, LLVMLinkage.LLVMInternalLinkage)
                globals[variable] = globalProperty
            }
            else -> error("Cannot create variable record of kind ${variable.kind}")
        }
    }

    fun createAnonymousSlot(type: LLVMTypeRef): LLVMValueRef =
            codegen.alloca(type)

    fun clear() {
        // TODO: do not clear global registers
        registers.clear()
    }
}