package org.jetbrains.kotlin.testlib

import java.nio.file.Path

class Framework(val name: String, val path: Path) {

    companion object {
        fun produce(name: String, ktFiles: List<Path>, outputDir: Path): Framework {
            val options = listOf("-produce", "framework", "-opt", "-o", name)
            val output = outputDir.resolve(name)
            CompilerUtils.konanc(ktFiles, options, output)
            return Framework(name, output)
        }
    }

    fun produceTest(from: String) {}
}