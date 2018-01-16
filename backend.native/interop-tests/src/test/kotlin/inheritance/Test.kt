package inheritance

import org.jetbrains.kotlin.testlib.*
import org.junit.Test

import java.nio.file.Paths

class Test {
    private val testSrcDir = Paths.get(testHome, "test", "kotlin", "inheritance")

    @Test
    fun testInheritance() {
        InteropTest(kotlinTest = "hierarchy.kt", swiftTest = "hierarchy.swift")
    }
}