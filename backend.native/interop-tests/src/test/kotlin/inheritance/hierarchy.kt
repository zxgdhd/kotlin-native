package inheritance

interface I {
    fun iFun(): String = "I::iFun"
}

private interface PI {
    fun piFun(): Any
    fun iFun(): String = "PI::iFun"
}

class DefaultInterfaceExt : I

open class OpenClassI : I {
    override fun iFun(): String = "OpenClassI::iFun"
}

class FinalClassExtOpen : OpenClassI() {
    override fun iFun(): String = "FinalClassExtOpen::iFun"
}

open class MultiExtClass : OpenClassI(), PI {
    override fun piFun(): Any {
        return 42
    }

    override fun iFun(): String = super<PI>.iFun()
}

open class ConstrClass(open val i: Int, val s: String, val a: Any = "AnyS") : OpenClassI()

class ExtConstrClass(override val i: Int) : ConstrClass(i, "String") {
    override fun iFun(): String  = "ExtConstrClass::iFun::$i-$s-$a"
}