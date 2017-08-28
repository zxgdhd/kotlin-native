package org.jetbrains.kotlin.backend.common.ir.cfg

//-----------------------------------------------------------------------------//

sealed class Type {
    abstract val byteSize: Int

    object boolean: Type() { override val byteSize: Int get() = 1 }
    object byte   : Type() { override val byteSize: Int get() = 1 }
    object short  : Type() { override val byteSize: Int get() = 2 }
    object int    : Type() { override val byteSize: Int get() = 4 }
    object long   : Type() { override val byteSize: Int get() = 8 }
    object float  : Type() { override val byteSize: Int get() = 4 }
    object double : Type() { override val byteSize: Int get() = 8 }
    object char   : Type() { override val byteSize: Int get() = 2 }

    open class ptr : Type() { override val byteSize: Int get() = 8 }
    class KlassPtr(val klass: Klass): ptr()
    class ArrayPtr(val type: Type)  : ptr()
    object FunctionPtr              : ptr()
    object BlockPtr                 : ptr()
    object FieldPtr                 : ptr()

    override fun toString() = asString()
}


// Denotes kind of variable
enum class Kind {
    ARG,            // Function argument
    FIELD,          // Class field
    LOCAL,          // Local variable
    TMP,            // Created by CFG selection
    STATIC          // Field that doesn't belong to any object
}

//--- Predefined types --------------------------------------------------------//

val unitKlass   = Klass("Unit")
val anyKlass    = Klass("Any")
val TypeUnit    = Type.KlassPtr(unitKlass) // TODO: workaround. Add builtins
val TypeAny     = Type.KlassPtr(anyKlass)
val TypeString  = Type.KlassPtr(Klass("String"))

//--- Utils -------------------------------------------------------------------//

fun Klass.isAny() = this == anyKlass

//--- Type usage examples -----------------------------------------------------//

/*
 42                          -> Constant(Type.int, 42)
 "Hello world"               -> Constant(TypeString, "Hello world")
 foo()                       -> Constant(TypeFunction, "foo")

 isOn: Boolean               -> Variable(Type.boolean, "isOn")
 obj: A                      -> Variable(Type.ptr(Klass("A")), "obj")
 body: (a: Int) -> Unit      -> Variable(TypeFunction, "body")
*/
