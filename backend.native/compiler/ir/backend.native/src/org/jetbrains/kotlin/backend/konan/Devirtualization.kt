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

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.ir.ir2stringWhole
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.ir.IrReturnableBlock
import org.jetbrains.kotlin.backend.konan.ir.IrSuspendableExpression
import org.jetbrains.kotlin.backend.konan.ir.IrSuspensionPoint
import org.jetbrains.kotlin.backend.konan.llvm.findMainEntryPoint
import org.jetbrains.kotlin.backend.konan.llvm.isExported
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.*

// TODO: Exceptions, Arrays.

// Devirtualization analysis is performed using Variable Type Analysis algorithm.
// See zzz for details.
internal object Devirtualization {

    private val DEBUG = 0

    private class VariableValues {
        val elementData = HashMap<VariableDescriptor, MutableSet<IrExpression>>()

        fun addEmpty(variable: VariableDescriptor) =
                elementData.getOrPut(variable, { mutableSetOf<IrExpression>() })

        fun add(variable: VariableDescriptor, element: IrExpression) =
                elementData.get(variable)?.add(element)

        fun add(variable: VariableDescriptor, elements: Set<IrExpression>) =
                elementData.get(variable)?.addAll(elements)

        fun get(variable: VariableDescriptor): Set<IrExpression>? =
                elementData[variable]

        fun computeClosure() {
            elementData.forEach { key, _ ->
                add(key, computeValueClosure(key))
            }
        }

        // Computes closure of all possible values for given variable.
        private fun computeValueClosure(value: VariableDescriptor): Set<IrExpression> {
            val result = mutableSetOf<IrExpression>()
            val seen = mutableSetOf<VariableDescriptor>()
            dfs(value, seen, result)
            return result
        }

        private fun dfs(value: VariableDescriptor, seen: MutableSet<VariableDescriptor>, result: MutableSet<IrExpression>) {
            seen += value
            val elements = elementData[value]
                    ?: return
            for (element in elements) {
                if (element !is IrGetValue)
                    result += element
                else {
                    val descriptor = element.descriptor
                    if (descriptor is VariableDescriptor && !seen.contains(descriptor))
                        dfs(descriptor, seen, result)
                }
            }
        }
    }

    private fun IrTypeOperator.isCast() =
            this == IrTypeOperator.CAST || this == IrTypeOperator.IMPLICIT_CAST || this == IrTypeOperator.SAFE_CAST

    private class ExpressionValuesExtractor(val returnableBlockValues: Map<IrReturnableBlock, List<IrExpression>>,
                                            val suspendableExpressionValues: Map<IrSuspendableExpression, List<IrSuspensionPoint>>) {

        fun forEachValue(expression: IrExpression, block: (IrExpression) -> Unit) {
            if (expression.type.isUnit() || expression.type.isNothing()) return
            when (expression) {
                is IrReturnableBlock -> returnableBlockValues[expression]!!.forEach { forEachValue(it, block) }

                is IrSuspendableExpression ->
                    (suspendableExpressionValues[expression]!! + expression.result).forEach { forEachValue(it, block) }

                is IrSuspensionPoint -> {
                    forEachValue(expression.result, block)
                    forEachValue(expression.resumeResult, block)
                }

                is IrContainerExpression -> forEachValue(expression.statements.last() as IrExpression, block)

                is IrWhen -> expression.branches.forEach { forEachValue(it.result, block) }

                is IrMemberAccessExpression -> block(expression)

                is IrGetValue -> block(expression)

                is IrGetField -> block(expression)

                is IrVararg -> /* Sometimes, we keep vararg till codegen phase (for constant arrays). */
                    block(expression)

                is IrConst<*> -> block(expression)

                is IrTypeOperatorCall -> {
                    if (!expression.operator.isCast())
                        block(expression)
                    else { // Propagate cast to sub-values.
                        forEachValue(expression.argument) { value ->
                            with(expression) {
                                block(IrTypeOperatorCallImpl(startOffset, endOffset, type, operator, typeOperand, value))
                            }
                        }
                    }
                }

                is IrTry -> {
                    forEachValue(expression.tryResult, block)
                    expression.catches.forEach { forEachValue(it.result, block) }
                }

                is IrGetObjectValue -> block(expression)

                else -> TODO("Unknown expression: ${ir2string(expression)}")
            }
        }
    }

    private fun KotlinType.erasure(): KotlinType {
        val descriptor = this.constructor.declarationDescriptor
        return when (descriptor) {
            is ClassDescriptor -> this
            is TypeParameterDescriptor -> {
                val upperBound = descriptor.upperBounds.singleOrNull() ?:
                        TODO("$descriptor : ${descriptor.upperBounds}")

                if (this.isMarkedNullable) {
                    // `T?`
                    upperBound.erasure().makeNullable()
                } else {
                    upperBound.erasure()
                }
            }
            else -> TODO(this.toString())
        }
    }

    private val KotlinType.isFinal get() = (constructor as ClassDescriptor).modality == Modality.FINAL

    private class FunctionTemplateBody(val nodes: List<Node>, val returns: Node.Proxy) {
        class Edge(val node: Node, val castToType: KotlinType?)

        sealed class Node {
            class Parameter(val index: Int): Node()

            class Const(val type: KotlinType): Node()

            class Call(val callee: FunctionDescriptor, val arguments: List<Edge>): Node()

            class VirtualCall(val callee: FunctionDescriptor, val arguments: List<Edge>): Node()

            class NewObject(val constructor: ConstructorDescriptor, val arguments: List<Edge>): Node()

            class Singleton(val type: KotlinType): Node()

            class FieldRead(val receiver: Edge?, val field: PropertyDescriptor): Node()

            class FieldWrite(val receiver: Edge?, val field: PropertyDescriptor, val value: Edge): Node()

            class Proxy(val values: List<Edge>): Node()
        }
    }

    private class FunctionTemplate(val numberOfParameters: Int, val body: FunctionTemplateBody) {

        companion object {
            fun create(parameters: List<ParameterDescriptor>,
                       expressions: List<IrExpression>,
                       variableValues: VariableValues,
                       returnValues: List<IrExpression>,
                       expressionValuesExtractor: ExpressionValuesExtractor) =
                    Builder(expressionValuesExtractor, variableValues, parameters, expressions, returnValues).template

            private class Builder(val expressionValuesExtractor: ExpressionValuesExtractor,
                                  val variableValues: VariableValues,
                                  parameters: List<ParameterDescriptor>,
                                  expressions: List<IrExpression>,
                                  returnValues: List<IrExpression>) {

                private val templateParameters = parameters.withIndex().associateBy(
                        { it.value },
                        { FunctionTemplateBody.Node.Parameter(it.index) }
                )

                private val nodes = mutableMapOf<IrExpression, FunctionTemplateBody.Node>()
                val template: FunctionTemplate

                init {
                    for (expression in expressions) {
                        getNode(expression)
                    }
                    val returns = FunctionTemplateBody.Node.Proxy(returnValues.map { expressionToEdge(it) })
                    template = FunctionTemplate(templateParameters.size, FunctionTemplateBody(nodes.values.toList(), returns))
                }

                private fun expressionToEdge(expression: IrExpression) =
                        if (expression is IrTypeOperatorCall && expression.operator.isCast())
                            FunctionTemplateBody.Edge(getNode(expression.argument), expression.typeOperand.erasure().makeNotNullable())
                        else FunctionTemplateBody.Edge(getNode(expression), null)

                private fun getNode(expression: IrExpression): FunctionTemplateBody.Node {
                    return nodes.getOrPut(expression) {
                        val values = mutableListOf<IrExpression>()
                        expressionValuesExtractor.forEachValue(expression) {
                            if (it !is IrGetValue)
                                values += it
                            else {
                                val descriptor = it.descriptor
                                if (descriptor is ParameterDescriptor)
                                    values += it
                                else {
                                    descriptor as VariableDescriptor
                                    variableValues.get(descriptor)?.forEach { values += it }
                                }
                            }
                        }
                        if (values.size != 1)
                            FunctionTemplateBody.Node.Proxy(values.map { expressionToEdge(it) })
                        else {
                            val value = values[0]
                            when (value) {
                                is IrGetValue -> templateParameters[value.descriptor as ParameterDescriptor]!!

                                is IrVararg,
                                is IrConst<*> -> FunctionTemplateBody.Node.Const(value.type)

                                is IrGetObjectValue -> FunctionTemplateBody.Node.Singleton(value.type)

                                is IrCall -> { // TODO: choose between Node.Call and Node.VirtualCall.
                                    val arguments = value.getArguments()
                                    val callee = value.descriptor.original
                                    if (callee is ConstructorDescriptor)
                                        FunctionTemplateBody.Node.NewObject(callee, arguments.map { expressionToEdge(it.second) })
                                    else
                                        FunctionTemplateBody.Node.Call(callee, arguments.map { expressionToEdge(it.second) })
                                }

                                is IrDelegatingConstructorCall ->
                                    FunctionTemplateBody.Node.Call(value.descriptor, value.getArguments().map { expressionToEdge(it.second) })

                                is IrGetField -> {
                                    val receiver = value.receiver?.let { expressionToEdge(it) }
                                    FunctionTemplateBody.Node.FieldRead(receiver, value.descriptor)
                                }

                                is IrSetField -> {
                                    val receiver = value.receiver?.let { expressionToEdge(it) }
                                    FunctionTemplateBody.Node.FieldWrite(receiver, value.descriptor, expressionToEdge(value.value))
                                }

                                is IrTypeOperatorCall -> {
                                    assert(!value.operator.isCast(), { "Casts should've been handled earlier" })
                                    expressionToEdge(value.argument) // Put argument as a separate vertex.
                                    FunctionTemplateBody.Node.Const(value.type) // All operators except casts are basically constants.
                                }

                                else -> TODO("Unknown expression: ${ir2stringWhole(value)}")
                            }
                        }
                    }
                }
            }
        }
    }

    private class IntraproceduralAnalysisResult(val functionTemplates: Map<CallableDescriptor, FunctionTemplate>)

    private class IntraproceduralAnalysis {

        // Possible values of a returnable block.
        private val returnableBlockValues = mutableMapOf<IrReturnableBlock, MutableList<IrExpression>>()

        // All suspension points within specified suspendable expression.
        private val suspendableExpressionValues = mutableMapOf<IrSuspendableExpression, MutableList<IrSuspensionPoint>>()

        private val expressionValuesExtractor = ExpressionValuesExtractor(returnableBlockValues, suspendableExpressionValues)

        fun analyze(irModule: IrModuleFragment): IntraproceduralAnalysisResult {
            val templates = mutableMapOf<CallableDescriptor, FunctionTemplate>()
            irModule.accept(object : IrElementVisitorVoid {

                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitFunction(declaration: IrFunction) {
                    declaration.body?.let {
                        if (DEBUG > 1)
                            println("Analysing function ${declaration.descriptor}")
                        templates.put(declaration.descriptor, analyze(declaration.descriptor.allParameters, it))
                    }
                }

                override fun visitField(declaration: IrField) {
                    declaration.initializer?.let {
                        if (DEBUG > 1)
                            println("Analysing global field ${declaration.descriptor}")
                        templates.put(declaration.descriptor, analyze(emptyList(), it))
                    }
                }

                private fun analyze(parameters: List<ParameterDescriptor>, body: IrElement): FunctionTemplate {
                    // Find all interesting expressions, variables and functions.
                    val visitor = ElementFinderVisitor()
                    body.acceptVoid(visitor)
                    val functionTemplate = FunctionTemplate.create(parameters, visitor.expressions, visitor.variableValues,
                            visitor.returnValues, expressionValuesExtractor)

                    if (DEBUG > 1) {
                        println("FIRST PHASE")
                        visitor.variableValues.elementData.forEach { t, u ->
                            println("VAR $t:")
                            u.forEach {
                                println("    ${ir2stringWhole(it)}")
                            }
                        }
                        visitor.expressions.forEach { t ->
                            println("EXP ${ir2stringWhole(t)}")
                        }
                    }

                    // Compute transitive closure of possible values for variables.
                    visitor.variableValues.computeClosure()

                    if (DEBUG > 1) {
                        println("SECOND PHASE")
                        visitor.variableValues.elementData.forEach { t, u ->
                            println("VAR $t:")
                            u.forEach {
                                println("    ${ir2stringWhole(it)}")
                            }
                        }
                    }

                    return functionTemplate
                }
            }, data = null)

            return IntraproceduralAnalysisResult(templates)
        }

        private inner class ElementFinderVisitor : IrElementVisitorVoid {

            val expressions = mutableListOf<IrExpression>()
            val variableValues = VariableValues()
            val returnValues = mutableListOf<IrExpression>()

            private val returnableBlocks = mutableMapOf<FunctionDescriptor, IrReturnableBlock>()
            private val suspendableExpressionStack = mutableListOf<IrSuspendableExpression>()

            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            private fun assignVariable(variable: VariableDescriptor, value: IrExpression) {
                expressionValuesExtractor.forEachValue(value) {
                    variableValues.add(variable, it)
                }
            }

            override fun visitExpression(expression: IrExpression) {
                when (expression) {
                    is IrMemberAccessExpression,
                    is IrGetField,
                    is IrGetObjectValue,
                    is IrVararg,
                    is IrConst<*>,
                    is IrTypeOperatorCall ->
                        expressions += expression
                }

                if (expression is IrReturnableBlock) {
                    returnableBlocks.put(expression.descriptor, expression)
                    returnableBlockValues.put(expression, mutableListOf())
                }
                if (expression is IrSuspendableExpression) {
                    suspendableExpressionStack.push(expression)
                    suspendableExpressionValues.put(expression, mutableListOf())
                }
                if (expression is IrSuspensionPoint)
                    suspendableExpressionValues[suspendableExpressionStack.peek()!!]!!.add(expression)
                super.visitExpression(expression)
                if (expression is IrReturnableBlock)
                    returnableBlocks.remove(expression.descriptor)
                if (expression is IrSuspendableExpression)
                    suspendableExpressionStack.pop()
            }

            override fun visitSetField(expression: IrSetField) {
                expressions += expression
                super.visitSetField(expression)
            }

            // TODO: hack to overcome bad code in InlineConstructorsTransformation.
            private val FQ_NAME_INLINE_CONSTRUCTOR = FqName("konan.internal.InlineConstructor")

            override fun visitReturn(expression: IrReturn) {
                val returnableBlock = returnableBlocks[expression.returnTarget]
                if (returnableBlock != null) {
                    returnableBlockValues[returnableBlock]!!.add(expression.value)
                } else { // Non-local return.
                    if (!expression.type.isUnit()) {
                        if (!expression.returnTarget.annotations.hasAnnotation(FQ_NAME_INLINE_CONSTRUCTOR)) // Not inline constructor.
                            returnValues += expression.value
                    }
                }
                super.visitReturn(expression)
            }

            override fun visitSetVariable(expression: IrSetVariable) {
                super.visitSetVariable(expression)
                assignVariable(expression.descriptor, expression.value)
            }

            override fun visitVariable(declaration: IrVariable) {
                variableValues.addEmpty(declaration.descriptor)
                super.visitVariable(declaration)
                declaration.initializer?.let { assignVariable(declaration.descriptor, it) }
            }
        }
    }

    private class InterproceduralAnalysis(val context: Context,
                                          val intraproceduralAnalysisResult: IntraproceduralAnalysisResult) {

        private fun MemberScope.getOverridingOf(function: FunctionDescriptor) = when (function) {
            is PropertyGetterDescriptor ->
                this.getContributedVariables(function.correspondingProperty.name, NoLookupLocation.FROM_BACKEND)
                        .firstOrNull { OverridingUtil.overrides(it, function.correspondingProperty) }?.getter

            is PropertySetterDescriptor ->
                this.getContributedVariables(function.correspondingProperty.name, NoLookupLocation.FROM_BACKEND)
                        .firstOrNull { OverridingUtil.overrides(it, function.correspondingProperty) }?.setter

            else -> this.getContributedFunctions(function.name, NoLookupLocation.FROM_BACKEND)
                    .firstOrNull { OverridingUtil.overrides(it, function) }
        }

        fun analyze(irModule: IrModuleFragment): Map<IrMemberAccessExpression, DevirtualizedCallSite> {
            val rootSet = getRootSet(irModule)

        }

        private fun getRootSet(irModule: IrModuleFragment): Set<CallableDescriptor> {
            val rootSet = mutableSetOf<CallableDescriptor>()
            val hasMain = context.config.configuration.get(KonanConfigKeys.PRODUCE) == CompilerOutputKind.PROGRAM
            if (hasMain)
                rootSet.add(findMainEntryPoint(context)!!)
            irModule.accept(object: IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitField(declaration: IrField) {
                    declaration.initializer?.let {
                        // Global field.
                        rootSet += declaration.descriptor
                    }
                }

                override fun visitFunction(declaration: IrFunction) {
                    if (!hasMain && declaration.descriptor.isExported())
                        // For a library take all visible functions.
                        rootSet += declaration.descriptor
                }
            }, data = null)
            return rootSet
        }
    }

    internal class DevirtualizedCallSite(val possibleCallees: List<FunctionDescriptor>)

    internal fun devirtualize(irModule: IrModuleFragment, context: Context)
            : Map<IrMemberAccessExpression, DevirtualizedCallSite> {
        val isStdlib = context.config.configuration[KonanConfigKeys.NOSTDLIB] == true

    }
}