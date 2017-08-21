package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Klass
import org.jetbrains.kotlin.backend.common.ir.cfg.isAny
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.OverriddenFunctionDescriptor
import org.jetbrains.kotlin.backend.konan.descriptors.target
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe


internal class RTTIGenerator(override val context: Context) : BitcodeSelectionUtils {

    private inner class FieldTableRecord(val nameSignature: LocalHash, val fieldOffset: Int) :
            Struct(runtime.fieldTableRecordType, nameSignature, Int32(fieldOffset))

    private inner class MethodTableRecord(val nameSignature: LocalHash, val methodEntryPoint: ConstValue) :
            Struct(runtime.methodTableRecordType, nameSignature, methodEntryPoint)

    private inner class TypeInfo(name: ConstValue,
                                 size: Int, superType: ConstValue,
                                 objOffsets: ConstValue, objOffsetsCount: Int,
                                 interfaces: ConstValue, interfacesCount: Int,
                                 methods: ConstValue, methodsCount: Int,
                                 fields: ConstValue, fieldsCount: Int) :
            Struct(
                    runtime.typeInfoType,
                    name,
                    Int32(size),
                    superType,
                    objOffsets,
                    Int32(objOffsetsCount),
                    interfaces,
                    Int32(interfacesCount),
                    methods,
                    Int32(methodsCount),
                    fields,
                    Int32(fieldsCount)
            )

    private fun exportTypeInfoIfRequired(classDesc: ClassDescriptor, typeInfoGlobal: LLVMValueRef?) {
        val annot = classDesc.annotations.findAnnotation(FqName("konan.ExportTypeInfo"))
        if (annot != null) {
            val nameValue = annot.allValueArguments.values.single() as StringValue
            // TODO: use LLVMAddAlias?
            val global = addGlobal(nameValue.value, pointerType(runtime.typeInfoType), isExported = true)
            LLVMSetInitializer(global, typeInfoGlobal)
        }
    }

    private val arrayClasses = mapOf(
            "kotlin.Array"        to -LLVMABISizeOfType(llvmTargetData, kObjHeaderPtr).toInt(),
            "kotlin.ByteArray"    to -1,
            "kotlin.CharArray"    to -2,
            "kotlin.ShortArray"   to -2,
            "kotlin.IntArray"     to -4,
            "kotlin.LongArray"    to -8,
            "kotlin.FloatArray"   to -4,
            "kotlin.DoubleArray"  to -8,
            "kotlin.BooleanArray" to -1,
            "kotlin.String"       to -2
    )

    private fun getInstanceSize(classType: LLVMTypeRef?, className: String) =
        arrayClasses[className] ?: LLVMStoreSizeOfType(llvmTargetData, classType).toInt()

    fun generate(klass: Klass) {
        val (bodyType, typeInfoGlobal, _) = context.cfgLlvmDeclarations.classes[klass]
                ?: error(" No LLVM declaration for $klass")
        val className = klass.name
        val size = getInstanceSize(bodyType, className)

        val supertype = if (klass.superclass.isAny()) {
            NullPointer(runtime.typeInfoType)
        } else {
            klass.superclass.typeInfoPtr
        }

        val interfaces = klass.interfaces.map { it.typeInfoPtr }
        val interfacesPtr = staticData.placeGlobalConstArray("kintf:$className",
                pointerType(runtime.typeInfoType), interfaces)

        val objOffsets = getStructElements(bodyType).mapIndexedNotNull { index, type ->
            if (isObjectType(type)) {
                LLVMOffsetOfElement(llvmTargetData, bodyType, index)
            } else {
                null
            }
        }

        val objOffsetsPtr = staticData.placeGlobalConstArray("krefs:$className",
                int32Type, objOffsets.map { Int32(it.toInt()) })

        val fields = klass.fields.mapIndexed { index, field ->
            val nameSignature = field.name.localHash
            val fieldOffset = LLVMOffsetOfElement(llvmTargetData, bodyType, index)
            FieldTableRecord(nameSignature, fieldOffset.toInt())
        }.sortedBy { it.nameSignature.value }

        val fieldsPtr = staticData.placeGlobalConstArray("kfields:$className",
                runtime.fieldTableRecordType, fields)

        val methods = if (context.cfgDeclarations.classMetas[klass]!!.isAbstract) {
            emptyList()
        } else {
            context.getVtableBuilder(klass).methodTableEntries.map {
                val functionName = it.overriddenDescriptor.functionName
                val nameSignature = functionName.localHash
                val implementation = context.cfgDeclarations.functions[it.implementation]
                val entryPointAddress =   if (implementation == null) {
                    val func = context.llvm.externalFunction(it.implementation.symbolName, getLlvmFunctionType(it.implementation))
                    val result = LLVMConstBitCast(func, int8TypePtr)!!
                    constValue(result)
                } else {
                    implementation.entryPointAddress
                }
                MethodTableRecord(nameSignature, entryPointAddress)
            }.sortedBy { it.nameSignature.value }
        }

        val methodsPtr = staticData.placeGlobalConstArray("kmethods:$className",
                runtime.methodTableRecordType, methods)

        val typeInfo = TypeInfo(
                className.globalHash,
                size, supertype,
                objOffsetsPtr, objOffsets.size,
                interfacesPtr, interfaces.size,
                methodsPtr, methods.size,
                fieldsPtr, fields.size
        )

        val typeInfoGlobalValue = if (context.cfgDeclarations.classMetas[klass]!!.isAbstract) {
            typeInfo
        } else {
            val vtableEntries = context.getVtableBuilder(klass).vtableEntries.map {
                val implementation = context.cfgDeclarations.functions[it.implementation]
                if (implementation == null) {
                    val func = context.llvm.externalFunction(it.implementation.symbolName, getLlvmFunctionType(it.implementation))
                    val result = LLVMConstBitCast(func, int8TypePtr)!!
                    constValue(result)
                } else {
                    implementation.entryPointAddress
                }
            }
            val vtable = ConstArray(int8TypePtr, vtableEntries)
            Struct(typeInfo, vtable)
        }

        typeInfoGlobal.setInitializer(typeInfoGlobalValue)
        typeInfoGlobal.setConstant(true)

//        val descriptor = context.cfgDeclarations.classes
//                .filterValues { it == klass }
//                .map { it.key }
//                .firstOrNull() ?: error("No declaration for $klass")
//        exportTypeInfoIfRequired(descriptor, descriptor.llvmTypeInfoPtr)
    }

    internal val OverriddenFunctionDescriptor.implementation: FunctionDescriptor
        get() {
            val target = descriptor.target
            if (!needBridge) return target
            val bridgeOwner = if (inheritsBridge) {
                target // Bridge is inherited from superclass.
            } else {
                descriptor
            }
            return context.specialDeclarationsFactory.getBridgeDescriptor(OverriddenFunctionDescriptor(bridgeOwner, overriddenDescriptor))
        }
}