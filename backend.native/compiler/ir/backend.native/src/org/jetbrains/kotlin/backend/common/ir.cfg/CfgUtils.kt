package org.jetbrains.kotlin.backend.common.ir.cfg

//--- Globals -----------------------------------------------------------------//

val CfgNull = Constant(TypeUnit, "null")
val Cfg0    = Constant(Type.int, 0)
val Cfg1    = Constant(Type.int, 1)
val CfgUnit = Variable(TypeUnit, "unit")

//--- Opcode ------------------------------------------------------------------//

fun Instruction.isTerminal() =
        this is Br || this is Ret || this is Invoke


//--- Block -------------------------------------------------------------------//

fun Block.addSuccessor(successor: Block) {
    successors += successor
    successor.predecessors += this
}

//-----------------------------------------------------------------------------//

fun Block.inst(instruction: Instruction): Variable {
    instructions += instruction

    return when (instruction) {
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
        is Call         -> instruction.def
        is Alloc        -> instruction.def
        is InstanceOf   -> instruction.def
        is GT0          -> instruction.def
        is LT0          -> instruction.def
        is BinOp        -> instruction.def
        else            -> CfgUnit
    }
}

//-----------------------------------------------------------------------------//

fun Block.isLastInstructionTerminal(): Boolean
    = instructions.isNotEmpty() && instructions.last().isTerminal()

//-----------------------------------------------------------------------------//

val Block.ptr: Constant
    get() = Constant(Type.BlockPtr, this)

//--- Function ----------------------------------------------------------------//

fun Function.newBlock(name: String = "block") = Block(genBlockName(name))

//-----------------------------------------------------------------------------//

val Function.blocks
    get() = search(this.enter)

//-----------------------------------------------------------------------------//

val Function.ptr: Constant
    get() = Constant(Type.FunctionPtr, this)

//--- Ir ----------------------------------------------------------------------//

fun Ir.addKlass(klass: Klass)          { klasses[klass.name] = klass }
fun Ir.addFunction(function: Function) { functions[function.name] = function }

//-----------------------------------------------------------------------------//
// Build direct-ordered list of blocks in graph starting with "enter" block

private fun search(enter: Block): List<Block> {
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

