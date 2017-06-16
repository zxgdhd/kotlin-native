package org.jetbrains.kotlin.backend.konan.library.impl

import org.jetbrains.kotlin.backend.konan.KonanBuiltIns
import org.jetbrains.kotlin.backend.konan.library.KotlinSpecifics
import org.jetbrains.kotlin.backend.konan.serialization.KonanSerializerProtocol
import org.jetbrains.kotlin.backend.konan.serialization.NullFlexibleTypeDeserializer
import org.jetbrains.kotlin.backend.konan.serialization.base64ToStream
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.NotFoundClasses
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.storage.StorageManager
import java.io.InputStream

class KonanKotlinSpecifics(override val languageVersionSettings: LanguageVersionSettings): KotlinSpecifics {
    override val storageManager: StorageManager = LockBasedStorageManager()
    override val kotlinBuildIns: KotlinBuiltIns = KonanBuiltIns(storageManager)
    override val deserializationConfiguration: DeserializationConfiguration =
            CompilerDeserializationConfiguration(languageVersionSettings)

    override fun createModuleDescriptor(moduleName: String): ModuleDescriptorImpl {
        return ModuleDescriptorImpl(Name.special(moduleName), storageManager, kotlinBuildIns)
    }

    override fun createDeserializationComponents(provider: PackageFragmentProvider, module: ModuleDescriptor): DeserializationComponents {
        val notFoundClasses = NotFoundClasses(storageManager, module)
        val annotationAndConstantLoader = AnnotationAndConstantLoaderImpl(module, notFoundClasses, KonanSerializerProtocol)
        return DeserializationComponents(
                storageManager, module, deserializationConfiguration,
                DeserializedClassDataFinder(provider),
                annotationAndConstantLoader,
                provider,
                LocalClassifierTypeSettings.Default,
                ErrorReporter.DO_NOTHING,
                LookupTracker.DO_NOTHING, NullFlexibleTypeDeserializer,
                emptyList(), notFoundClasses)
    }

    override fun base64toStream(base64: String): InputStream {
        return base64ToStream(base64)
    }
}