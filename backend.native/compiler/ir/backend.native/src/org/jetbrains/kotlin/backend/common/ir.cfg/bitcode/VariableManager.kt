package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Variable
import org.jetbrains.kotlin.backend.konan.llvm.isObjectType
import org.jetbrains.kotlin.backend.konan.llvm.kObjHeaderPtr


internal class VariableManager(val codegen: CodeGenerator) {
    internal interface Record {
        fun load() : LLVMValueRef
        fun store(value: LLVMValueRef)
        fun address() : LLVMValueRef
    }

    inner class SlotRecord(val address: LLVMValueRef, val refSlot: Boolean, val isVar: Boolean) : Record {
        override fun load() : LLVMValueRef = codegen.loadSlot(address, isVar)
        override fun store(value: LLVMValueRef) = codegen.storeAnyLocal(value, address)
        override fun address() : LLVMValueRef = this.address
        override fun toString() = (if (refSlot) "refslot" else "slot") + " for $address"
    }

    class ValueRecord(val value: LLVMValueRef, val name: String) : Record {
        override fun load() : LLVMValueRef = value
        override fun store(value: LLVMValueRef) = error("writing to immutable: $name")
        override fun address() : LLVMValueRef = error("no address for: $name")
        override fun toString() = "value of $value from $name"
    }

    val variables = mutableListOf<Record>()
    val contextVariablesToIndex = mutableMapOf<Variable, Int>()

    // Clears inner state of variable manager.
    fun clear() {
        variables.clear()
        contextVariablesToIndex.clear()
    }

    fun createVariable(variable: Variable, value: LLVMValueRef? = null) : Int {
        // Note that we always create slot for object references for memory management.
        return if (!variable.isVar && value != null)
            createImmutable(variable, value)
        else
        // Unfortunately, we have to create mutable slots here,
        // as even vals can be assigned on multiple paths. However, we use varness
        // knowledge, as anonymous slots are created only for true vars (for vals
        // their single assigner already have slot).
            createMutable(variable, variable.isVar)
    }

    fun createMutable(variable: Variable,
                      isVar: Boolean, value: LLVMValueRef? = null) : Int {
        assert(variable !in contextVariablesToIndex)
        val index = variables.size
        val type = codegen.getLlvmType(variable.type)
        val slot = codegen.alloca(type, variable.name)
        if (value != null)
            codegen.storeAnyLocal(value, slot)
        variables += SlotRecord(slot, codegen.isObjectType(type), isVar)
        contextVariablesToIndex[variable] = index
        return index
    }

    fun createImmutable(variable: Variable, value: LLVMValueRef) : Int {
        if (variable in contextVariablesToIndex) error("$variable is already defined")
        val index = variables.size
        variables += ValueRecord(value, variable.name)
        contextVariablesToIndex[variable] = index
        return index
    }

    // Creates anonymous mutable variable.
    // Think of slot reuse.
    fun createAnonymousSlot(value: LLVMValueRef? = null) : LLVMValueRef {
        val index = createAnonymousMutable(codegen.kObjHeaderPtr, value)
        return addressOf(index)
    }

    private fun createAnonymousMutable(type: LLVMTypeRef, value: LLVMValueRef? = null) : Int {
        val index = variables.size
        val slot = codegen.alloca(type)
        if (value != null)
            codegen.storeAnyLocal(value, slot)
        variables += SlotRecord(slot, codegen.isObjectType(type), true)
        return index
    }

    fun indexOf(descriptor: Variable) : Int = contextVariablesToIndex[descriptor] ?: -1

    fun addressOf(index: Int): LLVMValueRef = variables[index].address()

    fun load(index: Int): LLVMValueRef = variables[index].load()

    fun store(value: LLVMValueRef, index: Int) = variables[index].store(value)
}
