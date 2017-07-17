package org.jetbrains.kotlin.backend.common.ir.cfg

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
    enter?.let { dot(it, name) }
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
