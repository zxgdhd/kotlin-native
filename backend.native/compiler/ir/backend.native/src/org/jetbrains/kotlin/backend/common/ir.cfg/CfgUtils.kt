package org.jetbrains.kotlin.backend.common.ir.cfg

//--- Globals -----------------------------------------------------------------//

val CfgNull = Constant(TypeUnit, "null")
val Cfg0    = Constant(TypeInt, 0)
val Cfg1    = Constant(TypeInt, 1)
val CfgUnit = Variable(TypeUnit, "unit")

//--- Opcode ------------------------------------------------------------------//

fun Opcode.isTerminal() = this == Opcode.br || this == Opcode.ret || this == Opcode.invoke || this == Opcode.resume

//--- Operand -----------------------------------------------------------------//

fun Operand.addDef(instruction: Instruction) { defs.add(instruction) }
fun Operand.addUse(instruction: Instruction) { uses.add(instruction) }

//--- Instruction -------------------------------------------------------------//

fun Instruction.addUse(operand: Operand)  { operand.addUse(this); uses.add(operand) }
fun Instruction.addDef(operand: Variable) { operand.addDef(this); defs.add(operand) }

//--- Block -------------------------------------------------------------------//

fun Block.addSuccessor(successor: Block) {
    successors.add(successor)
    successor.predecessors.add(this)
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode, vararg uses: Operand): Instruction {
    val instruction = Instruction(opcode)
    instructions.add(instruction)
    uses.forEach(instruction::addUse)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode, def: Variable, vararg uses: Operand): Instruction {
    val instruction = Instruction(opcode)
    instructions.add(instruction)
    uses.forEach(instruction::addUse)
    instruction.addDef(def)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.isLastInstructionTerminal(): Boolean
    = instructions.isNotEmpty() && instructions.last().opcode.isTerminal()

//--- Function ----------------------------------------------------------------//

fun Function.newBlock(name: String = "block") = Block(genBlockName(name))
fun Function.addValueParameters(parameters: List<Variable>) { this.parameters.addAll(parameters) }

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

