package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

abstract class Operand(var type: Type) {
    val uses = mutableListOf<Instruction>()                                    // Instructions using this operand.
    val defs = mutableListOf<Instruction>()                                    // Instructions defining this operand.
}

//-----------------------------------------------------------------------------//

class Constant(type: Type, val value: Any): Operand(type) {                    // Operand which value is known at compile time.
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

// TODO: remove isVar
class Variable(type: Type, val name: String, val isVar: Boolean = false): Operand(type) {                  // Operand which value is unknown at compile time.
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

abstract class Instruction(
    val uses: List<Operand> = listOf<Operand>(),                               // Operands used by this instruction.
    val defs: List<Variable> = listOf<Variable>()) {                           // Operands defined by this instruction.
    override fun toString() = asString()

    init {
        uses.forEach { it.uses += this }
        defs.forEach { it.defs += this }
    }
}

//-----------------------------------------------------------------------------//

class Block(val name: String) {
    val instructions = mutableListOf<Instruction>()
    val predecessors = mutableSetOf<Block>()
    val successors   = mutableSetOf<Block>()
    override fun toString() = name
}

//-----------------------------------------------------------------------------//

class Function(val name: String, val returnType: Type = TypeUnit) {
    val parameters = mutableListOf<Variable>()
    val enter      = Block("enter")                                            // Enter block of function cfg.

    var maxBlockId    = 0
    var maxVariableId = 0
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

class Klass(val name: String) {
    var superclass: Klass = anyKlass
    val interfaces = mutableListOf<Klass>()                                       // Superclass and interfaces.
    val methods = mutableListOf<Function>()                                    // Methods and property getters/setters.
    val fields  = mutableListOf<Variable>()                                    // Backing fields.
    override fun toString() = "$name"
}

//-----------------------------------------------------------------------------//

class Ir {
    val functions = mutableMapOf<String, Function>()                           // Functions defined in current compilation module.
    val klasses   = mutableMapOf<String, Klass>()                              // Classes defined in current compilation module.
}
