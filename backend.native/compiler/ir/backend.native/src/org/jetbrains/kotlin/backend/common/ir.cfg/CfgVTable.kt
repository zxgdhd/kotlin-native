package org.jetbrains.kotlin.backend.common.ir.cfg

class VTable(val klass: Klass) {
    /**
     * Entry in the vtable.
     */
    sealed class Entry(open val method: Function) {
        class Normal(override val method: ConcreteFunction)
            : Entry(method)
        class Override(override val method: Function, val implementation: ConcreteFunction)
            : Entry(method)
    }

    private val entries = mutableListOf<Entry>()

    init {

    }
}