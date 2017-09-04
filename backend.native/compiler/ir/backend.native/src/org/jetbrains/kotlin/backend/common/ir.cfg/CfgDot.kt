package org.jetbrains.kotlin.backend.common.ir.cfg

import java.io.File

//-----------------------------------------------------------------------------//

fun dotFunction(function: ConcreteFunction) {

    createDotDir()
    val dotFile = File("out_dot/${function.name}.dot")
    val blocks = function.blocks
    dotFile.printWriter().use { out ->
        out.println("digraph {")
        out.println("node [shape=record, style=rounded, penwidth=0.5, fontname=Menlo, fontsize=10];")
        out.println("edge [penwidth=0.5, fontname=Menlo, fontsize=10];")
        out.println("rankdir=TB;")

        blocks.forEach { block ->
            out.println("${block.name} [label=\"{${block.asDot()}}\"]\n")
            val successors = block.successors
            successors.forEach { successor ->
                out.println("${block.name} -> ${successor.name} [color=black]")
            }
        }
        out.println("}")
    }
}

//-----------------------------------------------------------------------------//

fun createDotDir() {
    val dotDir = File("out_dot")
    if (dotDir.exists()) return
    try {
        dotDir.mkdir()
    } catch (e: SecurityException) {
        error("Cannot create folder")
    }
}

//-----------------------------------------------------------------------------//

private fun Instruction.asDot() = asString()
    .replace("<", "")
    .replace(">", "")
    .replace("\"", "\\\"")
    .replace("\'", "\\\'")


//-----------------------------------------------------------------------------//

fun Block.asDot(): String = with(StringBuilder()) {
    append("$name|")
    instructions.forEach { append( it.asDot() + "\\l")}
    toString()
}


