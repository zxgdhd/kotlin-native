package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

fun Type.asString(): String = when (this) {
    Type.boolean   -> "boolean"
    Type.byte      -> "byte"
    Type.short     -> "short"
    Type.int       -> "int"
    Type.long      -> "long"
    Type.float     -> "float"
    Type.double    -> "double"
    Type.char      -> "char"
    is Type.KlassPtr -> klass.name
    is Type.ptr -> "ptr"
}

//-----------------------------------------------------------------------------//

fun Variable.asString() = "$name:$type"

//-----------------------------------------------------------------------------//

fun Constant.asString() =
    when(type) {
        TypeString -> "\"$value\""
        else       -> value.toString()
    }

//-----------------------------------------------------------------------------//

fun Instruction.asString(): String {
    if (this is Call)   return asString()
    if (this is Invoke) return asString()
    val buff = StringBuilder()
    if (defs.isNotEmpty()) {
        buff.append(defs.joinToString())
        buff.padEnd(8, ' ')
        buff.append(" = ")
    }

    val opcode = this::class.simpleName?.toLowerCase()
    buff.append("$opcode ")
    buff.append(uses.joinToString())
    return buff.toString()
}

//-----------------------------------------------------------------------------//

fun Call.asString(): String {
    val buff = StringBuilder()
    if (defs.isNotEmpty()) {
        buff.append(defs[0].toString() + " = ")                                      // return value
    }
    val arguments = args.joinToString()
    val opcode = this::class.simpleName?.toLowerCase()
    buff.append("$opcode ${callee.name}($arguments)")
    return buff.toString()
}

//-----------------------------------------------------------------------------//


fun Invoke.asString(): String {
    val buff = StringBuilder()
    if (defs.isNotEmpty()) {
        buff.append(defs[0].toString() + " = ")                                      // return value
    }
    val arguments = args.joinToString()
    val opcode = this::class.simpleName?.toLowerCase()
    buff.append("$opcode ${callee.name}($arguments)")
    return buff.toString()
}


//-----------------------------------------------------------------------------//

fun Function.asString(): String {
    val valueParametersStr = parameters.joinToString(", ", "", "", -1, "", { it.asString() })     // Function parameters as string.
    return "$name($valueParametersStr): ${returnType.asString()}"                                                     // Print selectFunction declaration.
}

//-----------------------------------------------------------------------------//

fun Block.log() {
    println("  L.$name")
    instructions.forEach { println("    $it") }
}

//-----------------------------------------------------------------------------//

fun Function.log() {
    println("\nfun $this {")                                                            // Print selectFunction declaration.
    blocks.reversed().forEach(Block::log)                                               // Print the blocks.
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
    functions.forEach { (_, f) -> dotFunction(f) }
}

//-----------------------------------------------------------------------------//

fun Function.genVariableName() = "op${maxVariableId++}"
fun Function.genBlockName(blockName: String) = "$blockName${maxBlockId++}"

