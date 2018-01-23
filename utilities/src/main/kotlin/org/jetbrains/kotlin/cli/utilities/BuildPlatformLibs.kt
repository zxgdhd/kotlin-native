/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.utilities

import org.jetbrains.kotlin.backend.konan.library.KonanLibrarySearchPathResolver
import org.jetbrains.kotlin.konan.exec.Command
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.file.use
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.TargetManager
import org.jetbrains.kotlin.konan.util.DefFile
import org.jetbrains.kotlin.konan.util.visibleName
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun buildPlatformLibs(targetManager: TargetManager) {
    val target = targetManager.target
    val defFiles = target.platformLibDefFiles

    val destinationRepo = KonanLibrarySearchPathResolver(
            repositories = emptyList(),
            targetManager = targetManager,
            distributionKlib = konanHome.child("klib").absolutePath, // TODO: use Distribution class.
            localKonanDir = null
    ).distPlatformHead!!

    destinationRepo.mkdirs()

    buildPlatformLibs(target, defFiles, destinationRepo)
}

private val konanHome get() = File(System.getProperty("konan.home"))

private val KonanTarget.platformLibDefFiles: List<File>
    get() = konanHome.child("platforms").child(family.visibleName)
            .listFiles.filter { it.name.endsWith(".def") }

private fun buildPlatformLibs(target: KonanTarget, defFiles: List<File>, destinationRepo: File) {
    lock.withLock {
        val lockFile = destinationRepo.child(".lock").javaIoFile.apply { if (!exists()) createNewFile() }
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                buildPlatformLibsUnderLock(target, defFiles, destinationRepo)
            }
        }

    }
}

private val lock = ReentrantLock()

private fun buildPlatformLibsUnderLock(
        target: KonanTarget,
        defFiles: List<File>,
        destinationRepo: File
) {
    val nameToDefFile = defFiles.associate { file -> DefFile(file.javaIoFile, target).let { it.name to it } }
    val remainingDefFiles = nameToDefFile.values.toMutableSet()

    while (remainingDefFiles.isNotEmpty()) {
        val defFile = remainingDefFiles.first { defFile ->
            defFile.config.depends.all { nameToDefFile[it]!! !in remainingDefFiles }
        }
        remainingDefFiles.remove(defFile)

        val libName = defFile.name
        if (destinationRepo.child(libName).exists) {
            println("Skipping $libName")
            continue
        } else {
            println("Building $libName")
        }

        val buildDir = File(createTempDir("konanBuild").toPath())
        try {
            val klibFile = buildDir.child(libName)

            runKonanTool("cinterop",
                    "-target", target.visibleName,
                    "-def", defFile.file!!.absolutePath,
                    "-o", klibFile.absolutePath,
                    "-nodefaultlibs",
                    *defFile.config.depends.flatMap { listOf("-library", it) }.toTypedArray(),
                    "--purge_user_libs"
            )

            runKonanTool("klib",
                    "install", klibFile.absolutePath,
                    "-target", target.visibleName,
                    "-repository", destinationRepo.absolutePath
            )

        } finally {
            buildDir.deleteRecursively()
        }


    }
}

/**
 * Executes [org.jetbrains.kotlin.cli.utilities.main] with given arguments.
 */
private fun runKonanTool(name: String, vararg args: String) {
    val scriptExt = when (TargetManager.host) {
        KonanTarget.LINUX, KonanTarget.MACBOOK -> ""
        KonanTarget.MINGW -> ".bat"
        else -> error(TargetManager.host)
    }

    // Note: it is better to call tool entry point directly,
    // however currently most of the tools manage resources very badly.
    // So execute any tool in a new separate process every time:
    Command(System.getProperty("konan.home") + "/bin/run_konan$scriptExt", name, *args).execute()
}
