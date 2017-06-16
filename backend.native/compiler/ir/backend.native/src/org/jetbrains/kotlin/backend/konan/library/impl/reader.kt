package org.jetbrains.kotlin.backend.konan.library.impl

import org.jetbrains.kotlin.backend.konan.library.*
import org.jetbrains.kotlin.backend.konan.serialization.Base64
import org.jetbrains.kotlin.backend.konan.serialization.KonanPackageFragment
import org.jetbrains.kotlin.backend.konan.serialization.parseModuleHeader
import org.jetbrains.kotlin.backend.konan.serialization.parsePackageFragment
import org.jetbrains.kotlin.backend.konan.util.File
import org.jetbrains.kotlin.backend.konan.util.unzipAs
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.PackageFragmentProviderImpl
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.serialization.KonanLinkData
import java.util.*

class SplitMetadataReader(override val libDir: File) : MetadataReader, SplitLibraryScheme {
    override fun loadSerializedModule(currentAbiVersion: Int): NamedModuleData {
        val header = Properties()
        manifestFile.bufferedReader().use { reader ->
            header.load(reader)
        }
        val headerAbiVersion = header.getProperty("abi_version")!!
        val moduleName = header.getProperty("module_name")!!
        val moduleData = moduleHeaderFile.readText()

        if ("$currentAbiVersion" != headerAbiVersion)
            error("ABI version mismatch. Compiler expects: $currentAbiVersion, the library is $headerAbiVersion")

        return NamedModuleData(moduleName, moduleData)
    }

    override fun loadSerializedPackageFragment(fqName: String): String = packageFile(fqName).readText()
}

abstract class FileBasedLibraryReader(
        val file: File, val currentAbiVersion: Int,
        val reader: MetadataReader): KonanLibraryReader {
    protected val namedModuleData: NamedModuleData by lazy {
        reader.loadSerializedModule(currentAbiVersion)
    }

    override val libraryName: String = file.path
    override val moduleName: String = namedModuleData.name
    val tableOfContents : Base64 = namedModuleData.base64

    fun packageMetadata(fqName: String): Base64 =
            reader.loadSerializedPackageFragment(fqName)

    override fun moduleDescriptor(kotlinSpecifics: KotlinSpecifics) =
            deserializeModule(kotlinSpecifics, { packageMetadata(it) }, tableOfContents, moduleName)

    companion object {
        private fun deserializeModule(kotlinSpecifics: KotlinSpecifics,
                                      packageLoader: (String) -> Base64,
                                      library: Base64,
                                      moduleName: String): ModuleDescriptorImpl {
            val moduleDescriptor = kotlinSpecifics.createModuleDescriptor(moduleName)
            kotlinSpecifics.kotlinBuildIns.builtInsModule = moduleDescriptor
            val libraryProto = parseModuleHeader(kotlinSpecifics.base64toStream(library))
            val provider = createKonanPackageFragmentProvider(
                    libraryProto.packageFragmentNameList,
                    {it -> parsePackageFragment(kotlinSpecifics.base64toStream(packageLoader(it))) },
                    kotlinSpecifics,
                    moduleDescriptor)

            moduleDescriptor.initialize(provider)
            return moduleDescriptor
        }

        private fun createKonanPackageFragmentProvider(
                fragmentNames: List<String>,
                packageLoader: (String) -> KonanLinkData.PackageFragment,
                kotlinSpecifics: KotlinSpecifics,
                module: ModuleDescriptor): PackageFragmentProvider {

            val packageFragments = fragmentNames.map {
                KonanPackageFragment(it, packageLoader, kotlinSpecifics.storageManager, module)
            }
            val provider: PackageFragmentProvider = PackageFragmentProviderImpl(packageFragments)
            val components = kotlinSpecifics.createDeserializationComponents(provider, module)

            for (packageFragment in packageFragments) {
                packageFragment.components = components
            }

            return provider
        }
    }
}



class SplitLibraryReader(override val libDir: File, currentAbiVersion: Int,
                         override val target: String) :
        FileBasedLibraryReader(libDir, currentAbiVersion, SplitMetadataReader(libDir)), SplitLibraryScheme {

    public constructor(path: String, currentAbiVersion: Int, target: String) :
            this(File(path), currentAbiVersion, target)

    init {
        unpackIfNeeded()
    }

    // TODO: Search path processing is also goes somewhere around here.
    fun unpackIfNeeded() {
        // TODO: Clarify the policy here.
        if (libDir.exists) {
            if (libDir.isDirectory) return
        }
        if (!klibFile.exists) {
            error("Could not find neither $libDir nor $klibFile.")
        }
        if (klibFile.isFile) {
            klibFile.unzipAs(libDir)

            if (!libDir.exists) error("Could not unpack $klibFile as $libDir.")
        } else {
            error("Expected $klibFile to be a regular file.")
        }
    }

    override val bitcodePaths: List<String>
        get() = (kotlinDir.listFiles + nativeDir.listFiles).map{it.absolutePath}
}