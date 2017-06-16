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

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanConfigKeys
import org.jetbrains.kotlin.backend.konan.library.LinkData
import org.jetbrains.kotlin.backend.konan.llvm.base64Decode
import org.jetbrains.kotlin.backend.konan.llvm.base64Encode
import org.jetbrains.kotlin.backend.konan.llvm.isExported
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.serialization.KonanDescriptorSerializer
import org.jetbrains.kotlin.serialization.KonanLinkData
import org.jetbrains.kotlin.serialization.KonanLinkData.*
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.FlexibleTypeDeserializer
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * This is Konan specific part of public descriptor 
 * tree serialization and deserialization.
 *
 * It takes care of module and package fragment serializations.
 * The lower level (classes and members) serializations are delegated 
 * to the KonanDescriptorSerializer class.
 * The lower level deserializations are performed by the frontend
 * with MemberDeserializer class.
 */

typealias Base64 = String

fun byteArrayToBase64(byteArray: ByteArray): Base64 {
    val gzipped = ByteArrayOutputStream()
    val gzipStream = GZIPOutputStream(gzipped)
    gzipStream.write(byteArray)
    gzipStream.close()
    val base64 = base64Encode(gzipped.toByteArray())
    return base64
}

fun base64ToStream(base64: Base64): InputStream {
    val gzipped = base64Decode(base64)
    return GZIPInputStream(ByteArrayInputStream(gzipped))

}


/* ------------ Deserializer part ------------------------------------------*/

object NullFlexibleTypeDeserializer : FlexibleTypeDeserializer {
    override fun create(proto: ProtoBuf.Type, flexibleId: String, 
        lowerBound: SimpleType, upperBound: SimpleType): KotlinType {
            error("Illegal use of flexible type deserializer.")
        }
}

public fun parsePackageFragment(inputStream: InputStream): PackageFragment =
    PackageFragment.parseFrom(inputStream, KonanSerializerProtocol.extensionRegistry)

public fun parseModuleHeader(inputStream: InputStream): Library =
    Library.parseFrom(inputStream, KonanSerializerProtocol.extensionRegistry)


/* ------------ Serializer part ------------------------------------------*/

internal class KonanSerializationUtil(val context: Context) {

    val serializerExtension = KonanSerializerExtension(context)
    val topSerializer = KonanDescriptorSerializer.createTopLevel(serializerExtension)
    var classSerializer: KonanDescriptorSerializer = topSerializer

    fun serializeClass(packageName: FqName,
        builder: KonanLinkData.Classes.Builder,  
        classDescriptor: ClassDescriptor) {

        val previousSerializer = classSerializer

        // TODO: this is to filter out object{}. Change me.
        if (classDescriptor.isExported()) 
            classSerializer = KonanDescriptorSerializer.create(classDescriptor, serializerExtension)

        val classProto = classSerializer.classProto(classDescriptor).build()
            ?: error("Class not serialized: $classDescriptor")

        builder.addClasses(classProto)
        val index = classSerializer.stringTable.getFqNameIndex(classDescriptor)
        builder.addClassName(index)

        serializeClasses(packageName, builder, 
            classDescriptor.unsubstitutedInnerClassesScope
                .getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS))

        classSerializer = previousSerializer
    }

    fun serializeClasses(packageName: FqName, 
        builder: KonanLinkData.Classes.Builder, 
        descriptors: Collection<DeclarationDescriptor>) {

        for (descriptor in descriptors) {
            if (descriptor is ClassDescriptor) {
                serializeClass(packageName, builder, descriptor)
            }
        }
    }

    fun serializePackage(fqName: FqName, module: ModuleDescriptor) : 
        KonanLinkData.PackageFragment? {

        val packageView = module.getPackage(fqName)

        // TODO: ModuleDescriptor should be able to return 
        // the package only with the contents of that module, without dependencies
        val keep: (DeclarationDescriptor) -> Boolean = 
            { DescriptorUtils.getContainingModule(it) == module }

        val fragments = packageView.fragments
        if (fragments.filter(keep).isEmpty()) return null

        val classifierDescriptors = KonanDescriptorSerializer
            .sort(packageView.memberScope.getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS))
            .filter(keep)

        val members = fragments
                .flatMap { fragment -> DescriptorUtils.getAllDescriptors(fragment.getMemberScope()) }
                .filter(keep)

        val classesBuilder = KonanLinkData.Classes.newBuilder()

        serializeClasses(fqName, classesBuilder, classifierDescriptors)
        val classesProto = classesBuilder.build()

        val packageProto = topSerializer.packagePartProto(fqName, members).build()
            ?: error("Package fragments not serialized: $fragments")

        val strings = serializerExtension.stringTable
        val (stringTableProto, nameTableProto) = strings.buildProto()

        val fragmentBuilder = KonanLinkData.PackageFragment.newBuilder()

        val fragmentProto = fragmentBuilder
            .setPackage(packageProto)
            .setFqName(fqName.asString())
            .setClasses(classesProto)
            .setStringTable(stringTableProto)
            .setNameTable(nameTableProto)
            .build()

        return fragmentProto
    }

    private fun getPackagesFqNames(module: ModuleDescriptor): Set<FqName> {
        val fqNames = mutableSetOf<FqName>(FqName.ROOT)
        getSubPackagesFqNames(module.getPackage(FqName.ROOT), fqNames)
        return fqNames
    }
    private fun getSubPackagesFqNames(packageView: PackageViewDescriptor, result: MutableSet<FqName>) {
        val fqName = packageView.fqName
        if (!fqName.isRoot) {
            result.add(fqName)
        }

        for (descriptor in packageView.memberScope.getContributedDescriptors(
                DescriptorKindFilter.PACKAGES, MemberScope.ALL_NAME_FILTER)) {
            if (descriptor is PackageViewDescriptor) {
                getSubPackagesFqNames(descriptor, result)
            }
        }
    }
    internal fun serializeModule(moduleDescriptor: ModuleDescriptor): LinkData {
        val libraryProto = KonanLinkData.Library.newBuilder()
        val fragments = mutableListOf<String>()
        val fragmentNames = mutableListOf<String>()

        getPackagesFqNames(moduleDescriptor).forEach iteration@ {
            val packageProto = serializePackage(it, moduleDescriptor)
            if (packageProto == null) return@iteration

            libraryProto.addPackageFragmentName(it.asString())
            fragments.add(
                byteArrayToBase64(packageProto.toByteArray()))
            fragmentNames.add(it.asString())
        }
        val libraryAsByteArray = libraryProto.build().toByteArray()
        val library = byteArrayToBase64(libraryAsByteArray)
        val abiVersion = context.config.configuration.get(KonanConfigKeys.ABI_VERSION)!!
        val moduleName = moduleDescriptor.name.asString()
        return LinkData(abiVersion, library, moduleName, fragments, fragmentNames)
    }
}

