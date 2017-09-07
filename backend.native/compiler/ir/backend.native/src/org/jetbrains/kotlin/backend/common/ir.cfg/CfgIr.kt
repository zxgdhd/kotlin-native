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
// TODO: variables are too generic
class Variable(type: Type,
               val name: String,
               val kind: Kind,
               val isVar: Boolean = false
): Operand(type) {                                                              // Operand which value is unknown at compile time.
    override fun toString() = asString()
}

//-----------------------------------------------------------------------------//

abstract class Instruction(
    val uses: List<Operand> = listOf(),                                         // Operands used by this instruction.
    val defs: List<Variable> = listOf()) {                                         // Operands defined by this instruction.
    override fun toString() = asString()

    // TODO: get rid of lateinit
    lateinit var owner: Block

    init {
        uses.forEach { it.uses += this }
        defs.forEach { it.defs += this }
    }
}

//-----------------------------------------------------------------------------//

class Block(val name: String, val owner: ConcreteFunction) {
    val instructions = mutableListOf<Instruction>()
    val predecessors = mutableSetOf<Block>()
    val successors   = mutableSetOf<Block>()
    override fun toString() = name
}

//-----------------------------------------------------------------------------//

// TODO: Add linkage for both klasses and functions?
// Maybe introduce symbol class containing such information
sealed class Function(
        open val name: String,
        open val returnType: Type = TypeUnit,
        open val parameters: List<Variable> = emptyList()
) {
    override fun toString() = asString()
}

// Function without body (well, abstract :))
class AbstractFunction(override val name: String,
                       override val returnType: Type = TypeUnit,
                       override val parameters: List<Variable> = emptyList()
) : Function(name, returnType, parameters)


// Function with body
class ConcreteFunction(
        override val name: String,
        override val returnType: Type = TypeUnit,
        override val parameters: List<Variable> = emptyList()
) : Function(name, returnType, parameters) {
    val enter         = Block("enter", this)                                   // Enter block of function cfg.
    var maxBlockId    = 0
    var maxVariableId = 0
}

//-----------------------------------------------------------------------------//

// TODO: Replace methods list with VTable
class Klass(val name: String) {
    var superclass: Klass = anyKlass
    val interfaces = mutableListOf<Klass>()                                    // Superclass and interfaces.
    val methods = mutableListOf<Function>()                                    // Methods and property getters/setters.
    val fields  = mutableListOf<Variable>()                                    // Backing fields.
    override fun toString() = name
}

//-----------------------------------------------------------------------------//

class Ir {
    val functions = mutableListOf<ConcreteFunction>()                           // Functions defined in current compilation module.
    val klasses   = mutableListOf<Klass>()                                      // Classes defined in current compilation module.
}

//-----------------------------------------------------------------------------//

// Represents (not yet) all information about program
// TODO: Replace with CfgModule?
internal data class Cfg(
        val ir: Ir = Ir(),
        val declarations: CfgDeclarations = CfgDeclarations(),
        val globalStaticInitializers: MutableMap<Variable, Constant> = mutableMapOf()
)