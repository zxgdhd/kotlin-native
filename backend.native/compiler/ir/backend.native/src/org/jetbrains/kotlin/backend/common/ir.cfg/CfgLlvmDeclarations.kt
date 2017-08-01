package org.jetbrains.kotlin.backend.common.ir.cfg

import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.FunctionLlvmDeclarations
import org.jetbrains.kotlin.backend.konan.llvm.LlvmDeclarations
import org.jetbrains.kotlin.backend.konan.llvm.functionType
import org.jetbrains.kotlin.backend.konan.llvm.int8TypePtr
import org.jetbrains.kotlin.utils.keysToMap

internal fun createLlvmDeclarations(context: Context, functions: List<Function>, funcDependencies: List<Function>)
    = DeclarationsGenerator(context, functions, funcDependencies).generate()

internal class CfgLlvmDeclarations(
        val functions: Map<Function, FunctionLlvmDeclarations>
)

private class DeclarationsGenerator(val context: Context, val functions: List<Function>, val funcDependencies: List<Function>) {

    fun generate(): CfgLlvmDeclarations {
        val funcDeclarations = functions
                .keysToMap { createFunctionDeclaration(it, false) }
        val funcDependencies = funcDependencies
                .keysToMap { createFunctionDeclaration(it, true) }

        return CfgLlvmDeclarations(funcDeclarations + funcDependencies)
    }

    fun createFunctionDeclaration(function: Function, isExternal: Boolean): FunctionLlvmDeclarations {
        val llvmType = function.llvmType
        val llvmFunction = if (isExternal) {
            context.llvm.externalFunction(function.name, llvmType)
        } else {
            LLVMAddFunction(context.llvmModule, function.name, llvmType)!!
        }
        return FunctionLlvmDeclarations(llvmFunction)
    }
}

val Function.llvmType: LLVMTypeRef
    get() {
        val returnType = when(returnType) {
            TypeUnit -> LLVMVoidType()!!
            else -> returnType.llvmType
        }
        val paramTypes = parameters.map { it.type.llvmType }
        return functionType(returnType, paramTypes = *paramTypes.toTypedArray())
    }

val Type.llvmType: LLVMTypeRef
    get() = when(this) {
        Type.boolean -> LLVMInt1Type()!!
        Type.byte -> LLVMInt8Type()!!
        Type.short -> LLVMInt16Type()!!
        Type.int -> LLVMInt32Type()!!
        Type.long -> LLVMInt64Type()!!
        Type.float -> LLVMFloatType()!!
        Type.double -> LLVMDoubleType()!!
        Type.char -> LLVMInt16Type()!!
        is Type.ptr<*> -> int8TypePtr
    }