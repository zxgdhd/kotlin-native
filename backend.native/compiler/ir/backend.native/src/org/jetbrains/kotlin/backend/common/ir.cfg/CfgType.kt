package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

sealed class Type {
    object boolean: Type()
    object byte   : Type()
    object short  : Type()
    object int    : Type()
    object long   : Type()
    object float  : Type()
    object double : Type()
    object char   : Type()
    class ptr<out T>(val value: T) : Type()

    override fun toString() = asString()
}

//--- Predefined types --------------------------------------------------------//

val TypeUnit     = Type.ptr("unit")             // Invalid pointer
val TypeString   = Type.ptr("string")           // Pointer to string constant
val TypeFunction = Type.ptr("function")         // Pointer to function
val TypeBlock    = Type.ptr("block")            // Pointer to basic block
val TypePtr      = Type.ptr("ptr")              // Pointer to any object we know nothing about

//--- Type usage examples -----------------------------------------------------//

/*
 42                          -> Constant(Type.int, 42)
 "Hello world"               -> Constant(TypeString, "Hello world")
 foo()                       -> Constant(TypeFunction, "foo")

 isOn: Boolean               -> Variable(Type.boolean, "isOn")
 obj: A                      -> Variable(Type.ptr(Klass("A")), "obj")
 body: (a: Int) -> Unit      -> Variable(TypeFunction, "body")
*/
