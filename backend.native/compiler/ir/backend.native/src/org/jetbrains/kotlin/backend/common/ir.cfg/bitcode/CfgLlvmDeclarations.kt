package org.jetbrains.kotlin.backend.common.ir.cfg.bitcode

import kotlinx.cinterop.toCValues
import llvm.*
import org.jetbrains.kotlin.backend.common.ir.cfg.*
import org.jetbrains.kotlin.backend.common.ir.cfg.Function
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.*

internal fun createCfgLlvmDeclarations(context: Context)
        = DeclarationsGenerator(context).generate()

internal class CfgLlvmDeclarations(
        val functions: Map<Function, FunctionLlvmDeclarations>,
        val classes: Map<Klass, KlassLlvmDeclarations>
)

internal data class KlassLlvmDeclarations(
        val bodyType: LLVMTypeRef,
        val typeInfoGlobal: StaticData.Global,
        val typeInfo: ConstPointer
)

private class DeclarationsGenerator(override val context: Context) : ContextUtils {

    private val String.internalName
        get() = this + "#internal"

    fun generate(): CfgLlvmDeclarations {
        val funcDeclarations = context.cfgDeclarations.funcMetas.filterValues { !it.isIntrinsic }.mapValues { createFunctionDeclaration(it.key, it.value) }
        val klassDeclarations = context.cfgDeclarations.classMetas.filterValues { !it.isExternal }.mapValues { createClassDeclaration(it.key, it.value) }
        return CfgLlvmDeclarations(funcDeclarations, klassDeclarations)
    }

    private fun createClassDeclaration(klass: Klass, meta: KlassMetaInfo): KlassLlvmDeclarations {
        val internalName = klass.name.internalName
        val bodyType = createClassBodyType("kclassbody:$internalName", klass.fields)

        val typeInfoPtr: ConstPointer
        val typeInfoGlobal: StaticData.Global

        val typeInfoSymbolName = if (meta.isExported) {
            klass.typeInfoSymbolName
        } else {
            "ktype:$internalName"
        }
        if (!meta.isAbstract) {
            val typeInfoGlobalName = "ktypeglobal:$internalName"
            val typeInfoWithVtableType = structType(
                    runtime.typeInfoType,
                    LLVMArrayType(int8TypePtr, meta.vtableSize)!!
            )
            typeInfoGlobal = staticData.createGlobal(typeInfoWithVtableType, typeInfoGlobalName, isExported = false)

            val llvmTypeInfoPtr = LLVMAddAlias(context.llvmModule,
                    kTypeInfoPtr,
                    typeInfoGlobal.pointer.getElementPtr(0).llvm,
                    typeInfoSymbolName)!!

            if (!meta.isExported) {
                LLVMSetLinkage(llvmTypeInfoPtr, LLVMLinkage.LLVMInternalLinkage)
            }

            typeInfoPtr = constPointer(llvmTypeInfoPtr)
        } else {
            typeInfoGlobal = staticData.createGlobal(runtime.typeInfoType,
                    typeInfoSymbolName,
                    isExported = meta.isExported)

            typeInfoPtr = typeInfoGlobal.pointer
        }
        return KlassLlvmDeclarations(bodyType, typeInfoGlobal, typeInfoPtr)
    }

    private fun createFunctionDeclaration(function: Function, meta: FunctionMetaInfo): FunctionLlvmDeclarations {
        val llvmType = getLlvmType(function)
        val llvmFunction = if (meta.isExternal) {
            context.llvm.externalFunction(meta.symbol, llvmType)
        } else {
            val symbolName = if (meta.isExported) {
                meta.symbol
            } else {
                "kfun:${function.name.internalName}"
            }
            LLVMAddFunction(context.llvmModule, symbolName, llvmType)!!
        }
        return FunctionLlvmDeclarations(llvmFunction)
    }

    private fun createClassBodyType(name: String, fields: List<Variable>): LLVMTypeRef {
        val fieldTypes = fields.map { getLlvmType(it.type) }
        val classType = LLVMStructCreateNamed(LLVMGetModuleContext(context.llvmModule), name)!!
        LLVMStructSetBody(classType, fieldTypes.toCValues(), fieldTypes.size, 0)
        return classType
    }
}

internal val Klass.typeInfoSymbolName: String
    get() = "ktype:$name"

internal fun RuntimeAware.getLlvmType(function: Function): LLVMTypeRef  {
    val returnType = getLlvmType(function.returnType)
    val paramTypes = function.parameters.map { getLlvmType(it.type) }.toMutableList()
    if (isObjectType(returnType))
        paramTypes += kObjHeaderPtrPtr
    return functionType(returnType, paramTypes = *paramTypes.toTypedArray())
}

internal fun RuntimeAware.getLlvmType(type: Type): LLVMTypeRef {
    return when(type) {
        Type.boolean -> LLVMInt1Type()!!
        Type.byte -> LLVMInt8Type()!!
        Type.short -> LLVMInt16Type()!!
        Type.int -> LLVMInt32Type()!!
        Type.long -> LLVMInt64Type()!!
        Type.float -> LLVMFloatType()!!
        Type.double -> LLVMDoubleType()!!
        Type.char -> LLVMInt16Type()!!
        is Type.KlassPtr -> {
            if (type.klass == unitKlass) voidType else kObjHeaderPtr
        }
        is Type.ptr -> int8TypePtr
    }
}