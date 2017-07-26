package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

fun Type.asString(): String = when (this) {
    Type.boolean       -> "boolean"
    Type.byte          -> "byte"
    Type.short         -> "short"
    Type.int           -> "int"
    Type.long          -> "long"
    Type.float         -> "float"
    Type.double        -> "double"
    Type.char          -> "char"
    Type.string        -> "string"
    Type.ptr           -> "ptr"
    is Type.klassPtr   -> klass.name
    is Type.funcPtr    -> function.name
    is Type.operandPtr -> "$type*"
}

//-----------------------------------------------------------------------------//

fun Variable.asString() = "$name:$type"

//-----------------------------------------------------------------------------//

fun Constant.asString() =
    when(type) {
        Type.boolean -> if (value == 1) "true" else "false"
        Type.string  -> "\"$value\""
        else         -> value.toString()
    }

//-----------------------------------------------------------------------------//

fun Instruction.asString(): String {
    if (opcode == Opcode.call)   return callAsString()
    if (opcode == Opcode.invoke) return callAsString()
    val buff = StringBuilder()
    if (defs.isNotEmpty()) {
        buff.append(defs.joinToString())
        buff.padEnd(8, ' ')
        buff.append(" = ")
    }

    buff.append("$opcode ")
    buff.append(uses.joinToString())
    return buff.toString()
}

//-----------------------------------------------------------------------------//

fun Instruction.callAsString(): String {
    val buff = StringBuilder()
    if (defs.size > 0) {
        buff.append(defs[0].toString() + " = ")                                      // return value
    }

    val use0   = uses[0]
    val callee = when (use0) {
        is Variable -> use0.name
        is Constant -> use0.value.toString()
        else        -> throw TODO("Unexpected callee type")
    }
    val arguments = uses.drop(1).joinToString()
    buff.append("$opcode $callee($arguments)")
    return buff.toString()
}

//-----------------------------------------------------------------------------//

fun Function.asString(): String {
    val valueParametersStr = parameters.joinToString(", ", "", "", -1, "", { it.asString() })     // Function parameters as string.
    return "$name($valueParametersStr)"                                                     // Print selectFunction declaration.
}

//-----------------------------------------------------------------------------//

fun Block.log() {
    println("  $name:")
    instructions.forEach { println("    $it") }
}

//-----------------------------------------------------------------------------//

fun Function.log() {
    println("\nfun $this {")                                                     // Print selectFunction declaration.
    val blocks = search(enter)                                                                      // Get basic blocks of selectFunction body.
    blocks.reversed().forEach(Block::log)                                                           // Print the blocks.
    println("}")
}

//-----------------------------------------------------------------------------//

fun Klass.log() {
    println("\nclass $name {")
    fields.forEach  { println("    field $it") }
    methods.forEach { println("    fun $it") }
    println("}")
}

//-----------------------------------------------------------------------------//

fun Ir.log() {
    if (klasses.isNotEmpty()) println("\n//--- Classes ---------------------------------------------//")
    klasses.forEach { it.value.log() }
    if (functions.isNotEmpty()) println("\n//--- Functions -------------------------------------------//")
    functions.forEach { it.value.log() }
    functions.forEach { (_, f) -> f.enter.let { dotFunction(it, f.name)} }
}

//-----------------------------------------------------------------------------//

fun Function.genVariableName() = "op${maxVariableId++}"
fun Function.genBlockName(blockName: String) = "$blockName${maxBlockId++}"

