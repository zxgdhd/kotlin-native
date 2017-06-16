package org.jetbrains.kotlin.backend.konan.library

import org.jetbrains.kotlin.backend.konan.util.File

// This scheme describes the Konan Library (klib) layout.
interface SplitLibraryScheme {
    val libDir: File
    val target: String?
            // This is a default implementation. Can't make it an assignment.
        get() = null
    val klibFile
        get() = File("${libDir.path}.klib")
    val manifestFile
        get() = File(libDir, "manifest")
    val resourcesDir
        get() = File(libDir, "resources")
    val targetsDir
        get() = File(libDir, "targets")
    val targetDir
        get() = File(targetsDir, target!!)
    val kotlinDir
        get() = File(targetDir, "kotlin")
    val nativeDir
        get() = File(targetDir, "native")
    val linkdataDir
        get() = File(libDir, "linkdata")
    val moduleHeaderFile
        get() = File(linkdataDir, "module")
    fun packageFile(packageName: String)
            = File(linkdataDir, if (packageName == "") "root_package" else "package_$packageName")
}