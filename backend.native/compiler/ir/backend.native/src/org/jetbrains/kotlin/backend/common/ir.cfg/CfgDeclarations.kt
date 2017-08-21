package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ValueType
import org.jetbrains.kotlin.backend.konan.correspondingValueType
import org.jetbrains.kotlin.backend.konan.descriptors.*
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.isUnit

class CfgDeclarations(
        val functions: MutableMap<FunctionDescriptor, Function> = mutableMapOf(),
        val classes: MutableMap<ClassDescriptor, Klass> = mutableMapOf(),
        val funcMetas: MutableMap<Function, FunctionMetaInfo> = mutableMapOf(),
        val classMetas: MutableMap<Klass, KlassMetaInfo> = mutableMapOf()
)

class KlassMetaInfo(
        val isExported: Boolean,
        val isExternal: Boolean,
        val isAbstract: Boolean,
        val vtableSize: Int
)

class FunctionMetaInfo(
        val isExported: Boolean,
        val isIntrinsic: Boolean,
        val isExternal: Boolean,
        val symbol: String
)

/**
 * Maps Declarations to CfgTypes and Functions
 */
internal interface TypeResolver : RuntimeAware {
    val context: Context

    override val runtime: Runtime
        get() = context.llvm.runtime

    private val classes: MutableMap<ClassDescriptor, Klass>
        get() = context.cfgDeclarations.classes

    private val functions: MutableMap<FunctionDescriptor, Function>
        get() = context.cfgDeclarations.functions

    private val classMetas: MutableMap<Klass, KlassMetaInfo>
        get() = context.cfgDeclarations.classMetas

    private val funcMetas: MutableMap<Function, FunctionMetaInfo>
        get() = context.cfgDeclarations.funcMetas

    fun isExternal(descriptor: DeclarationDescriptor) = descriptor.module != context.ir.irModule.descriptor

    val KotlinType.cfgType: Type
        get() = if (!isValueType()) {
                if (this.isUnit()) {
                    TypeUnit
                } else {
                    val classDescriptor = TypeUtils.getClassDescriptor(this)
                            ?: error("Cannot get class descriptor ${this.constructor}")
                    Type.KlassPtr(classDescriptor.cfgKlass)
                }
            } else when (correspondingValueType) {
                ValueType.BOOLEAN        -> Type.boolean
                ValueType.CHAR           -> Type.short
                ValueType.BYTE           -> Type.byte
                ValueType.SHORT          -> Type.short
                ValueType.INT            -> Type.int
                ValueType.LONG           -> Type.long
                ValueType.FLOAT          -> Type.float
                ValueType.DOUBLE         -> Type.double
                ValueType.NATIVE_PTR     -> Type.ptr()
                ValueType.NATIVE_POINTED -> Type.ptr()
                ValueType.C_POINTER      -> Type.ptr()
                null                     -> throw TODO("Null ValueType")
            }

    val ClassDescriptor.cfgKlass: Klass
        get() {
            if (this !in classes) {
                val klass = createCfgKlass(this)
                classes[this] = klass
                if (!isExternal(this)) {
                    getFields(this).forEach {
                        klass.fields += Variable(it.type.cfgType, it.toCfgName())
                    }
                    klass.superclass = getSuperClassOrAny().cfgKlass
                    klass.interfaces += this.implementedInterfaces.map { it.cfgKlass }
                    klass.methods += this.contributedMethods.map { it.cfgFunction }
                }
                classMetas[klass] = this.metaInfo
            }
            return classes[this]!!
        }

    private fun createCfgKlass(classDescriptor: ClassDescriptor): Klass {
        if (classDescriptor.isUnit()) {
            return unitKlass
        }
        return Klass(classDescriptor.toCfgName())
    }

    val FunctionDescriptor.cfgFunction: Function
        get() {
            if (this !in functions) {
                val returnType = when (this) {
                    is ConstructorDescriptor -> TypeUnit
                    else -> this.returnType?.cfgType ?: TypeUnit
                }
                val function = Function(this.toCfgName(), returnType)
                function.parameters += this.allParameters.map {
                    Variable(it.type.cfgType, it.name.asString())
                }
                functions[this] = function
                funcMetas[function] = this.metaInfo
            }
            return functions[this]!!
        }


    /**
     * All fields of the class instance.
     * The order respects the class hierarchy, i.e. a class [fields] contains superclass [fields] as a prefix.
     */
    private fun getFields(classDescriptor: ClassDescriptor): List<PropertyDescriptor> {
        val superClass = classDescriptor.getSuperClassNotAny() // TODO: what if Any has fields?
        val superFields = if (superClass != null) getFields(superClass) else emptyList()

        return superFields + getDeclaredFields(classDescriptor)
    }

    /**
     * Fields declared in the class.
     */
    private fun getDeclaredFields(classDescriptor: ClassDescriptor): List<PropertyDescriptor> {
        // TODO: Here's what is going on here:
        // The existence of a backing field for a property is only described in the IR,
        // but not in the PropertyDescriptor.
        //
        // We mark serialized properties with a Konan protobuf extension bit,
        // so it is present in DeserializedPropertyDescriptor.
        //
        // In this function we check the presence of the backing filed
        // two ways: first we check IR, then we check the protobuf extension.

        val irClass = context.ir.moduleIndexForCodegen.classes[classDescriptor]
        val fields = if (irClass != null) {
            val declarations = irClass.declarations

            declarations.mapNotNull {
                when (it) {
                    is IrProperty -> it.backingField?.descriptor
                    is IrField -> it.descriptor
                    else -> null
                }
            }
        } else {
            val properties = classDescriptor.unsubstitutedMemberScope.
                    getContributedDescriptors().
                    filterIsInstance<DeserializedPropertyDescriptor>()

            properties.mapNotNull { it.backingField }
        }
        return fields.sortedBy {
            it.fqNameSafe.localHash.value
        }
    }

    val CallableDescriptor.containingClass: Klass?
        get() {
            val dispatchReceiverParameter = this.dispatchReceiverParameter
            return if (dispatchReceiverParameter != null) {
                val containingClass = dispatchReceiverParameter.containingDeclaration
                classes[containingClass] ?: error(containingClass.toString())
            } else {
                null
            }
        }

    private val FunctionDescriptor.metaInfo: FunctionMetaInfo
        get() = FunctionMetaInfo(this.isExported(), this.isIntrinsic, this.module != context.ir.irModule.descriptor, if (this.isExported()) this.symbolName else this.name.asString())

    private val ClassDescriptor.metaInfo: KlassMetaInfo
        get() {
            val vtableSize = if (!isAbstract()) {
                context.getVtableBuilder(this).vtableEntries.size
            } else {
                0
            }
            return KlassMetaInfo(this.isExported(), isExternal(this), this.isAbstract(), vtableSize)
        }
}

//-------------------------------------------------------------------------//

fun MemberDescriptor.toCfgName() = fqNameSafe.asString()
    .replace("$", "")
    .replace("<", "")
    .replace(">", "")

