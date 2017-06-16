/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.backend.konan.library

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.serialization.deserialization.DeserializationComponents
import org.jetbrains.kotlin.serialization.deserialization.DeserializationConfiguration
import org.jetbrains.kotlin.storage.StorageManager
import java.io.InputStream

interface KotlinSpecifics {
    val languageVersionSettings: LanguageVersionSettings
    val storageManager: StorageManager
    val kotlinBuildIns: KotlinBuiltIns
    val deserializationConfiguration: DeserializationConfiguration

    fun createModuleDescriptor(moduleName: String): ModuleDescriptorImpl
    fun createDeserializationComponents(provider: PackageFragmentProvider, module: ModuleDescriptor): DeserializationComponents
    fun base64toStream(base64: String): InputStream //todo: remove me(!)
}

interface KonanLibraryReader {
    val libraryName: String
    val moduleName: String
    val bitcodePaths: List<String>
    fun moduleDescriptor(kotlinSpecifics: KotlinSpecifics): ModuleDescriptorImpl
}

interface MetadataReader {
    fun loadSerializedModule(currentAbiVersion: Int): NamedModuleData
    fun loadSerializedPackageFragment(fqName: String): String
}

class NamedModuleData(val name:String, val base64: String)