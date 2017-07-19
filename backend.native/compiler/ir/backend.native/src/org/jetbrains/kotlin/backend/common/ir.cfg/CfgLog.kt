package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

fun Instruction.asString(): String {

    if (opcode == Opcode.call) return callAsString()
    var buffer = ""
    if (defs.isNotEmpty()) {
        buffer += defs.joinToString()
        buffer = buffer.padEnd(8, ' ') + " = "
    }

    buffer += "$opcode "
    buffer += uses.joinToString()
    return buffer
}

//-----------------------------------------------------------------------------//

//invoke void @"kfun:main(kotlin.Array<kotlin.String>)"(%struct.ObjHeader* %0)
//to label %call_success unwind label %cleanup_landingpad

fun Instruction.callAsString(): String {
    val retValue = defs[0].toString()
    val callee = (uses[0] as Variable).name
    val arguments = uses.drop(1).joinToString(separator = ", ", prefix = "(", postfix = ")")
    return "$retValue = $opcode $callee$arguments"
}

//-----------------------------------------------------------------------------//

fun Variable.asString() = "%$name:$type"

//-----------------------------------------------------------------------------//

fun Constant.asString() =
    when(type.simpleType) {
        SimpleType.string  -> "\"$value\""
        SimpleType.boolean -> if (value == 1) "true" else "false"
        SimpleType.char    -> "\'$value\'"
        else               -> value.toString()
    }

//-----------------------------------------------------------------------------//

fun Block.log() {
    println("    block $name")
    instructions.forEach { println("        $it") }
}

//-----------------------------------------------------------------------------//

fun Function.log() {

//    val typeParametersStr  = reifiedTypes.joinToString()
//    val valueParametersStr = parameters.joinToString(", ", "", "", -1, "", { it.asString() })                 // Function parameters as string.
//    println("fun <$typeParametersStr> $name($valueParametersStr) {")                                                            // Print selectFunction declaration.
//    if (enter != null) {
//        val blocks = search(enter!!)                                                            // Get basic blocks of selectFunction body.
//        blocks.reversed().forEach(Block::asString)                                                   // Print the blocks.
//    }
//    println("}")
    enter.let { dotFunction(it, name) }
}

//-----------------------------------------------------------------------------//

fun Class.log() {
    println("class $name {")
    fields.forEach  { println("    field $it") }
    methods.forEach { println("    fun   $it") }
    println("}")
}

//-----------------------------------------------------------------------------//

fun Ir.log() {
    functions.forEach { it.value.log() }
}

//-----------------------------------------------------------------------------//

fun Function.genVariableName() = "${maxVariableId++}"
fun Function.genBlockName() = "bb${maxBlockId++}"

