package org.jetbrains.kotlin.backend.konan.library

import llvm.LLVMModuleRef

interface KonanLibraryWriter {

    fun addLinkData(linkData: LinkData)
    fun addNativeBitcode(library: String)
    fun addKotlinBitcode(llvmModule: LLVMModuleRef)
    fun commit()

    val mainBitcodeFileName: String
}

class LinkData(
        val abiVersion: Int,
        val module: String,
        val moduleName: String,
        val fragments: List<String>,
        val fragmentNames: List<String>)