package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.types.KotlinType

//-----------------------------------------------------------------------------//

val typeDouble  = Type(SimpleType.double)
val typeFloat   = Type(SimpleType.float)
val typeLong    = Type(SimpleType.long)
val typeInt     = Type(SimpleType.int)
val typeShort   = Type(SimpleType.short)
val typeByte    = Type(SimpleType.byte)
val typeChar    = Type(SimpleType.char)
val typeString  = Type(SimpleType.string)
val typeBoolean = Type(SimpleType.boolean)
val typePointer = Type(SimpleType.pointer)

val Null = Constant(typePointer, 0)

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

fun Block.newInstruction(opcode: Opcode): Instruction {
    val instruction = Instruction(opcode)
    addInstruction(instruction)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.newInstruction(opcode: Opcode, use: Operand): Instruction {

    val instruction = newInstruction(opcode)
    instruction.addUse(use)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.isLastInstructionTerminal(): Boolean
        = instructions.isNotEmpty() && instructions.last().opcode.isTerminal()

//-----------------------------------------------------------------------------//

fun Block.newInstruction(opcode: Opcode, def: Variable, use: Operand): Instruction {

    val instruction = newInstruction(opcode)
    instruction.addDef(def)
    instruction.addUse(use)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.newInstruction(opcode: Opcode, def: Variable, vararg uses: Operand): Instruction {

    val instruction = newInstruction(opcode)
    uses.forEach(instruction::addUse)
    instruction.addDef(def)
    return instruction
}

//-----------------------------------------------------------------------------//

fun Block.cmp(def: Variable, use1: Operand, use2: Operand) {

    val instruction = newInstruction(Opcode.cmp)
    instruction.addUse(use1)
    instruction.addUse(use2)
    instruction.addDef(def)
}

//-----------------------------------------------------------------------------//

fun Block.mov(def: Variable, use: Operand) {

    val instruction = newInstruction(Opcode.mov)
    instruction.addUse(use)
    instruction.addDef(def)
}

//-----------------------------------------------------------------------------//

fun Block.ret(use: Operand) {

    val instruction = newInstruction(Opcode.ret)
    instruction.addUse(use)
}

//-----------------------------------------------------------------------------//

fun Block.br(target: Block) {

    val instruction   = newInstruction(Opcode.br)
    val targetOperand = Constant(typePointer, target)
    instruction.addUse(targetOperand)

    addSuccessor(target)
}

//-----------------------------------------------------------------------------//

fun Block.condBr(condition: Operand, targetTrue: Block, targetFalse: Block) {

    val instruction = newInstruction(Opcode.condbr)
    val targetTrueOperand  = Constant(typePointer, targetTrue)
    val targetFalseOperand = Constant(typePointer, targetFalse)
    instruction.addUse(condition)
    instruction.addUse(targetTrueOperand)
    instruction.addUse(targetFalseOperand)

    addSuccessor(targetTrue)
    addSuccessor(targetFalse)
}

//--- Function ----------------------------------------------------------------//

fun Function.newBlock(name: String = genBlockName()) = Block(name)
fun Function.addTypeParameters(parameters: List<Type>) { this.reifiedTypes.addAll(parameters) }
fun Function.addValueParameters(parameters: List<Variable>) { this.parameters.addAll(parameters) }

//--- Utilities ---------------------------------------------------------------//

fun Opcode.isTerminal() = this == Opcode.br || this == Opcode.ret

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

