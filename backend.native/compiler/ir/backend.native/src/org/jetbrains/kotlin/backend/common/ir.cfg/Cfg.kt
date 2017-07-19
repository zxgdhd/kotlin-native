package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.types.KotlinType

//-----------------------------------------------------------------------------//

enum class SimpleType {
    double,
    float,
    long,
    int,
    short,
    byte,
    char,
    boolean,
    string,
    pointer
}

//-----------------------------------------------------------------------------//

open class Type(val simpleType: SimpleType) {
    override fun toString() = simpleType.toString()
}

//-----------------------------------------------------------------------------//

class KtType(val kotlinType: KotlinType) : Type(SimpleType.pointer) {
    override fun toString() = kotlinType.toString()
}

//-----------------------------------------------------------------------------//

abstract class Operand(val type: Type) {
    val uses = mutableListOf<Instruction>()
    val defs = mutableListOf<Instruction>()
}

//-----------------------------------------------------------------------------//

class Variable(type: Type, val name: String): Operand(type) {
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

class Constant(type: Type, val value: Any?): Operand(type) {
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
    val reifiedTypes = mutableListOf<Type>()
    val parameters   = mutableListOf<Variable>()
    var enter: Block = Block("${name}_enter")

    var maxBlockId    = 0
    var maxVariableId = 0

    override fun toString() = name
}

//-----------------------------------------------------------------------------//

class Class(val name: String): Type(SimpleType.pointer) {
    val superclasses = mutableListOf<Class>()
    val methods      = mutableListOf<Function>()
    val fields       = mutableListOf<Variable>()

    override fun toString() = name
}

//-----------------------------------------------------------------------------//

class Ir {
    val functions  = mutableMapOf<String, Function>()
    val classes    = mutableMapOf<String, Class>()
    val globalInit = Function("globalInit")

    fun newFunction(function: Function) {
        functions[function.name] = function
    }

}

//-----------------------------------------------------------------------------//

enum class Opcode {
    add,
    cmp,
    ret,
    br,
    condbr,
    mov,
    call
}

//-----------------------------------------------------------------------------//

