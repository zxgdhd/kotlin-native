package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Kind
import org.jetbrains.kotlin.backend.common.ir.cfg.Variable
import org.jetbrains.kotlin.backend.konan.llvm.isObjectType
import org.jetbrains.kotlin.backend.konan.llvm.kObjHeaderPtr


internal class Registers(val codegen: CodeGenerator) {
    private val registers = mutableMapOf<Variable, LLVMValueRef>()

    operator fun get(variable: Variable): LLVMValueRef {
        if (variable !in registers) {
            error("Trying to access $variable that is not in registers")
        }
        return when (variable.kind) {
            Kind.LOCAL              -> codegen.loadSlot(registers[variable]!!, variable.isVar)
            Kind.TMP, Kind.ARG      -> registers[variable]!!
            else                    -> error("Cannot get value for $variable")
        }

    }

    operator fun set(variable: Variable, value: LLVMValueRef) {
        when (variable.kind) {
            Kind.LOCAL              -> codegen.storeAnyLocal(value, registers[variable]!!)
            Kind.TMP, Kind.ARG      -> registers[variable] = value
            else                    -> error("Cannot set variable $variable")
        }
    }

    fun createVariable(variable: Variable) {
        when (variable.kind) {
            Kind.LOCAL -> {
                val slot = codegen.alloca(codegen.getLlvmType(variable.type), variable.name)
                registers[variable] = slot
            }
            else -> error("Cannot create variable record for ${variable.kind}")
        }
    }

    fun createAnonymousSlot(type: LLVMTypeRef): LLVMValueRef {
        return codegen.alloca(type)
    }

    fun clear() {
        registers.clear()
    }
}