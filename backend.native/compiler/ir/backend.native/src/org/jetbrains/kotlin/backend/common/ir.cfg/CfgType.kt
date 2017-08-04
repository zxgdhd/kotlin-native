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
    object FunctionPtr              : ptr()
    object BlockPtr                 : ptr()
    object FieldPtr                 : ptr()

    override fun toString() = asString()
}

//--- Predefined types --------------------------------------------------------//

val unitKlass = Klass("Unit")
val TypeUnit = Type.KlassPtr(unitKlass) // TODO: workaround. Add builtins
val TypeString = Type.KlassPtr(Klass("String"))

//--- Utils -------------------------------------------------------------------//

fun Type.KlassPtr.fieldOffset(fieldName: String): Int {
    var offset = 0
    klass.fields.forEach { field ->
        if (field.name == fieldName) return offset
        offset += field.type.byteSize
    }
    println("ERROR: No field $fieldName found in class $klass")
    return -1
}

//--- Type usage examples -----------------------------------------------------//

/*
 42                          -> Constant(Type.int, 42)
 "Hello world"               -> Constant(TypeString, "Hello world")
 foo()                       -> Constant(TypeFunction, "foo")

 isOn: Boolean               -> Variable(Type.boolean, "isOn")
 obj: A                      -> Variable(Type.ptr(Klass("A")), "obj")
 body: (a: Int) -> Unit      -> Variable(TypeFunction, "body")
*/
