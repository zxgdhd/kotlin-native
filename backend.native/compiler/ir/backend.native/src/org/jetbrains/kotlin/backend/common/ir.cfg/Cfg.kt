package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

sealed class Type() {
    object boolean: Type()
    object byte   : Type()
    object short  : Type()
    object int    : Type()
    object long   : Type()
    object float  : Type()
    object double : Type()
    object char   : Type()
    class ptr<out T>(val value: T) : Type()

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
    val parameters    = mutableListOf<Variable>()
    var enter         = Block("enter")
    var maxBlockId    = 0
    var maxVariableId = 0

    override fun toString() = asString()
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

//-----------------------------------------------------------------------------//

enum class Opcode {
    ret,                    // Terminators
    br,
    condbr,
    switch,
    indirectbr,
    invoke,
    resume,
    catchswitch,
    catchret,
    cleanupret,
    unreachable,

    add,                    // Integer binary operations
    sub,
    mul,
    udiv,
    sdiv,
    urem,
    srem,

    shl,                    // Bitwise binary operations
    lshr,
    ashr,
    and,
    or,
    xor,

    extractelement,         // Vector operations
    insertelement,
    shufflevector,

    extractvalue,           // Aggregate operations
    insertvalue,

    alloca,                 // Memory access and addressing operations
    load,
    store,
    fence,
    cmpxchg,
    atomicrmw,
    getelementptr,

    trunc,                  // Conversion operations
    zext,
    sext,
    fptrunc,
    fpext,
    fptoui,
    fptosi,
    uitofp,
    sitofp,
    ptrtoint,
    inttoptr,
    bitcast,
    addrspacecast,

    cmp,                    // Other operations
    phi,
    select,
    call,
    mov,
    landingpad,
    catchpad,
    cleanuppad,
    invalid,

    cast,                   // Type operations (workaround)
    integer_coercion,
    implicit_cast,
    implicit_not_null,
    coercion_to_unit,
    safe_cast,
    instance_of,
    not_instance_of
}

//-----------------------------------------------------------------------------//

