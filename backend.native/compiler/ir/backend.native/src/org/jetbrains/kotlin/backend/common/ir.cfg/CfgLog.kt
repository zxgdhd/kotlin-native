package org.jetbrains.kotlin.backend.common.ir.cfg

import java.io.File

//-----------------------------------------------------------------------------//

fun Variable.toStr() = "$name: $type"

//-----------------------------------------------------------------------------//

fun Instruction.toStr(): String {
    var buffer = "$opcode".padEnd(8, ' ')

    if (defs.isNotEmpty()) {
        buffer += defs.joinToString()
        buffer += " = "
    }

    buffer += uses.joinToString()
    return buffer
}

//-----------------------------------------------------------------------------//

fun Block.log() {
    println("    block $name")
    instructions.forEach { println("        $it") }
}

fun dot(enter: Block, name: String="graph") {
    val visited = mutableSetOf<Block>()
    val workSet = mutableListOf(enter)
    val edges = mutableListOf<Pair<Block, Block>>()
    File(name + ".dot").printWriter().use { out ->
        out.println("digraph {")
        search(enter).forEach {
            out.println("${it.name} [shape=box fontname=\"courier\" label=<${it.asDot()}>]\n")
        }
        while (workSet.isNotEmpty()) {
            val block = workSet.last()

            visited.add(block)
            val successors = block.successors.filterNot { edges.contains(Pair(block, it)) }
            successors.forEach { edges.add(Pair(block, it)) }
            workSet.addAll(successors)
            if (successors.isNotEmpty()) continue

            workSet.remove(block)
        }
        edges.forEach { (a, b) ->
            out.println("\"${a.name}\" -> \"${b.name}\"")
        }
        out.println("}")
    }
}

private fun Instruction.asDot() = toStr()
        .replace("<", "")
        .replace(">", "")


fun Block.asDot(): String = with(StringBuilder()) {
    val brLeft = "<br align=\"left\"/>"
    append("<b>$name</b>$brLeft")
    instructions.dropLast(1).forEach { append( it.asDot() + brLeft)}
    if (instructions.isNotEmpty())
        instructions.last().let { append(it.asDot() + brLeft) }
    toString()
}


//-----------------------------------------------------------------------------//

    fun Function.log() {

//    val typeParametersStr  = reifiedTypes.joinToString()
//    val valueParametersStr = parameters.joinToString(", ", "", "", -1, "", { it.toStr() })                 // Function parameters as string.
//    println("fun <$typeParametersStr> $name($valueParametersStr) {")                                                            // Print selectFunction declaration.
//    if (enter != null) {
//        val blocks = search(enter!!)                                                            // Get basic blocks of selectFunction body.
//        blocks.reversed().forEach(Block::log)                                                   // Print the blocks.
//    }
//    println("}")
        enter.let { dot(it, name) }
    }

//-----------------------------------------------------------------------------//

    fun Class.log() {

        println("class $name {")
        fields.forEach  { println("    field ${it.toStr()}") }
        methods.forEach { println("    fun   ${it}") }
        println("}")
    }

//-----------------------------------------------------------------------------//

    fun Ir.log() {
        functions.forEach { it.value.log() }
    }

//-----------------------------------------------------------------------------//

    fun Function.genVariableName() = "tmp${maxVariableId++}"
    fun Function.genBlockName() = "bb${maxBlockId++}"

