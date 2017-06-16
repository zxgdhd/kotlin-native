package org.jetbrains.kotlin.backend.konan.library.impl

import llvm.LLVMModuleRef
import llvm.LLVMWriteBitcodeToFile
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryWriter
import org.jetbrains.kotlin.backend.konan.library.LinkData
import org.jetbrains.kotlin.backend.konan.library.SplitLibraryScheme
import org.jetbrains.kotlin.backend.konan.util.File
import org.jetbrains.kotlin.backend.konan.util.copyTo
import org.jetbrains.kotlin.backend.konan.util.zipDirAs
import java.util.*

internal class SplitMetadataGenerator(override val libDir: File): SplitLibraryScheme {

    fun addLinkData(linkData: LinkData) {

        val linkdataDir = File(libDir, "linkdata")
        val header = Properties()
        header.putAll(hashMapOf(
                "abi_version" to "${linkData.abiVersion}",
                "module_name" to "${linkData.moduleName}"
        ))
        moduleHeaderFile.writeText(linkData.module)
        manifestFile.outputStream().use {
            header.store(it, null)
        }

        linkData.fragments.forEachIndexed { index, it ->
            val name = linkData.fragmentNames[index]
            packageFile(name).writeText(it)
        }
    }
}

class SplitLibraryWriter(override val libDir: File, override val target: String?,
                         val nopack: Boolean = false): KonanLibraryWriter, SplitLibraryScheme {

    public constructor(path: String, target: String, nopack: Boolean):
            this(File(path), target, nopack)

    // TODO: Experiment with separate bitcode files.
    // Per package or per class.
    val mainBitcodeFile = File(kotlinDir, "program.kt.bc")
    override val mainBitcodeFileName = mainBitcodeFile.path

    init {
        // TODO: figure out the proper policy here.
        libDir.deleteRecursively()
        klibFile.delete()
        libDir.mkdirs()
        linkdataDir.mkdirs()
        targetDir.mkdirs()
        kotlinDir.mkdirs()
        nativeDir.mkdirs()
        resourcesDir.mkdirs()
    }

    var llvmModule: LLVMModuleRef? = null

    override fun addKotlinBitcode(llvmModule: LLVMModuleRef) {
        this.llvmModule = llvmModule
        LLVMWriteBitcodeToFile(llvmModule, mainBitcodeFileName)
    }

    override fun addLinkData(linkData: LinkData) {
        SplitMetadataGenerator(libDir).addLinkData(linkData)
    }

    override fun addNativeBitcode(library: String) {
        val basename = File(library).name
        File(library).copyTo(File(nativeDir, basename))
    }

    override fun commit() {
        if (!nopack) {
            libDir.zipDirAs(klibFile)
            libDir.deleteRecursively()
        }
    }
}
