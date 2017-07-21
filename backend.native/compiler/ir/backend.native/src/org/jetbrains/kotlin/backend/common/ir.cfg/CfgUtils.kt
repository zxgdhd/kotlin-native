package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

val CfgNull = Constant(Type.pointer, 0)
val CfgUnit = Constant(Type.pointer, 0)

//--- Operand -----------------------------------------------------------------//

fun Operand.addDef(instruction: Instruction) { defs.add(instruction) }
fun Operand.addUse(instruction: Instruction) { uses.add(instruction) }

//--- Instruction -------------------------------------------------------------//

fun Instruction.addUse(operand: Operand)  { operand.addUse(this); uses.add(operand) }
fun Instruction.addDef(operand: Variable) { operand.addDef(this); defs.add(operand) }

//--- Block -------------------------------------------------------------------//

fun Block.addInstruction(instruction: Instruction) { instructions.add(instruction) }

//-----------------------------------------------------------------------------//

fun Block.addPredecessor(predecessor: Block) {
    predecessors.add(predecessor)
    predecessor.successors.add(this)
}

//-----------------------------------------------------------------------------//

fun Block.addSuccessor(successor: Block) {
    successors.add(successor)
    successor.predecessors.add(this)
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode): Instruction {
    val instruction = Instruction(opcode)
    addInstruction(instruction)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode, vararg uses: Operand): Instruction {

    val instruction = instruction(opcode)
    uses.forEach(instruction::addUse)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode, use: Operand): Instruction {

    val instruction = instruction(opcode)
    instruction.addUse(use)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode, def: Variable, use: Operand): Instruction {

    val instruction = instruction(opcode)
    instruction.addDef(def)
    instruction.addUse(use)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.instruction(opcode: Opcode, def: Variable, vararg uses: Operand): Instruction {

    val instruction = instruction(opcode)
    uses.forEach(instruction::addUse)
    instruction.addDef(def)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.cmp(def: Variable, use1: Operand, use2: Operand) {

    val instruction = instruction(Opcode.cmp)
    instruction.addUse(use1)
    instruction.addUse(use2)
    instruction.addDef(def)
}

//-----------------------------------------------------------------------------//

fun Block.mov(def: Variable, use: Operand) {

    val instruction = instruction(Opcode.mov)
    instruction.addUse(use)
    instruction.addDef(def)
}

//-----------------------------------------------------------------------------//

fun Block.ret(use: Operand) {
    val instruction = instruction(Opcode.ret)
    instruction.addUse(use)
}

//-----------------------------------------------------------------------------//

fun Block.br(target: Block) {
    val instruction   = instruction(Opcode.br)
    val targetOperand = Constant(Type.pointer, target)
    instruction.addUse(targetOperand)

    addSuccessor(target)
}

//-----------------------------------------------------------------------------//

fun Block.condBr(condition: Operand, targetTrue: Block, targetFalse: Block) {
    val instruction = instruction(Opcode.condbr)
    val targetTrueOperand  = Constant(Type.pointer, targetTrue)
    val targetFalseOperand = Constant(Type.pointer, targetFalse)
    instruction.addUse(condition)
    instruction.addUse(targetTrueOperand)
    instruction.addUse(targetFalseOperand)

    addSuccessor(targetTrue)
    addSuccessor(targetFalse)
}

//-----------------------------------------------------------------------------//

fun Block.isLastInstructionTerminal(): Boolean
    = instructions.isNotEmpty() && instructions.last().opcode.isTerminal()

//-----------------------------------------------------------------------------//

fun Block.invoke(targetSuccess: Block, targetFail: Block, def: Variable, vararg uses: Operand) {
    with(instruction(Opcode.invoke)) {
        addUse(uses[0]) // function name
        addUse(Constant(Type.pointer, targetSuccess))
        addUse(Constant(Type.pointer, targetFail))
        uses.drop(1).forEach(this::addUse)
        addDef(def)
    }
    addSuccessor(targetFail)
    addSuccessor(targetSuccess)
}

//--- Function ----------------------------------------------------------------//

fun Function.newBlock(name: String = "block") = Block(genBlockName(name))
fun Function.addTypeParameters(parameters: List<Type>) { this.reifiedTypes.addAll(parameters) }
fun Function.addValueParameters(parameters: List<Variable>) { this.parameters.addAll(parameters) }

//--- Utilities ---------------------------------------------------------------//

// TODO: add argument to the opcode?
fun Opcode.isTerminal() = this == Opcode.br || this == Opcode.ret || this == Opcode.invoke || this == Opcode.resume

//-----------------------------------------------------------------------------//


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

