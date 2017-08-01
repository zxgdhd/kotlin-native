package org.jetbrains.kotlin.backend.common.ir.cfg

//--- Globals -----------------------------------------------------------------//

val CfgNull = Constant(TypeUnit, "null")
val Cfg0    = Constant(TypeInt, 0)
val Cfg1    = Constant(TypeInt, 1)
val CfgUnit = Variable(TypeUnit, "unit")

//--- Opcode ------------------------------------------------------------------//

fun Instruction.isTerminal() =
        this is Br || this is Ret || this is Invoke


//--- Block -------------------------------------------------------------------//

fun Block.addSuccessor(successor: Block) {
    successors.add(successor)
    successor.predecessors.add(this)
}

//-----------------------------------------------------------------------------//

fun Block.inst(instruction: Instruction): Operand {

    val ret: Variable = when (instruction) {
        is Invoke -> {
            addSuccessor(instruction.landingpad)
            instruction.def
        }
        is Condbr -> {
            addSuccessor(instruction.targetFalse)
            addSuccessor(instruction.targetTrue)
            CfgUnit
        }
        is Br -> {
            addSuccessor(instruction.target)
            CfgUnit

        }
        is InstanceOf -> instruction.def
        else -> CfgUnit
    }
    instructions += instruction
    return ret
}

//-----------------------------------------------------------------------------//

fun Block.isLastInstructionTerminal(): Boolean
    = instructions.isNotEmpty() && instructions.last().isTerminal()

//-----------------------------------------------------------------------------//

val Block.ptr: Constant
    get() = Constant(TypeBlock, this)

//--- Function ----------------------------------------------------------------//

fun Function.newBlock(name: String = "block") = Block(genBlockName(name))
fun Function.addValueParameters(parameters: List<Variable>) { this.parameters.addAll(parameters) }

//-----------------------------------------------------------------------------//

val Function.ptr: Constant
    get() = Constant(TypeFunction, this)

//--- Ir ----------------------------------------------------------------------//

fun Ir.addKlass(klass: Klass)          { klasses[klass.name] = klass }
fun Ir.addFunction(function: Function) { functions[function.name] = function }

//-----------------------------------------------------------------------------//
// Build direct-ordered list of blocks in graph starting with "enter" block

fun search(enter: Block): List<Block> {
    val result  = mutableListOf<Block>()
    val visited = mutableSetOf<Block>()
    val workSet = mutableListOf(enter)

    while (workSet.isNotEmpty()) {
        val block = workSet.last()

        visited.add(block)
        val successors = block.successors.filterNot { visited.contains(it) }
        workSet.addAll(successors)
        if (successors.isNotEmpty()) continue

        result.add(block)
        workSet.remove(block)
    }
    return result
}

