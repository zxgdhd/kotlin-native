package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

sealed class Type {
    abstract val byteSize: Int

    object boolean: Type() { override val byteSize: Int get() = 1 }
    object byte   : Type() { override val byteSize: Int get() = 2 }
    object short  : Type() { override val byteSize: Int get() = 2 }
    object int    : Type() { override val byteSize: Int get() = 4 }
    object long   : Type() { override val byteSize: Int get() = 8 }
    object float  : Type() { override val byteSize: Int get() = 4 }
    object double : Type() { override val byteSize: Int get() = 8 }
    object char   : Type() { override val byteSize: Int get() = 2 }
    class ptr<out T>(val value: T) : Type() { override val byteSize: Int get() = 8 }

    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

abstract class Operand(val type: Type) {
    val uses = mutableListOf<Instruction>()
    val defs = mutableListOf<Instruction>()
}

//-----------------------------------------------------------------------------//

class Constant(type: Type, val value: Any?): Operand(type) {
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

class Variable(type: Type, val name: String): Operand(type) {
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

class Instruction(val opcode: Opcode) {
    val uses = mutableListOf<Operand>()
    val defs = mutableListOf<Variable>()

    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

class Block(val name: String) {
    val instructions = mutableListOf<Instruction>()
    val predecessors = mutableSetOf<Block>()
    val successors   = mutableSetOf<Block>()

    override fun toString() = name
}

//-----------------------------------------------------------------------------//

class Function(val name: String) {
    val parameters = mutableListOf<Variable>()
    var enter      = Block("enter")

    var maxBlockId    = 0
    var maxVariableId = 0
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

class Klass(val name: String) {
    val supers  = mutableListOf<Klass>()
    val methods = mutableListOf<Function>()
    val fields  = mutableListOf<Operand>()

    override fun toString() = name
}

//-----------------------------------------------------------------------------//

class Ir {
    val functions  = mutableMapOf<String, Function>()
    val klasses    = mutableMapOf<String, Klass>()

    fun newFunction(function: Function) {
        functions[function.name] = function
    }

    fun newKlass(klass: Klass) {
        klasses[klass.name] = klass
    }
}


