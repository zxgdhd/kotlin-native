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
import org.jetbrains.kotlin.backend.konan.llvm.getFields
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
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.*

// TODO: Exceptions, Arrays.

// Devirtualization analysis is performed using variation of the Cartesian Product Algorithm for type inference.
// See http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.93.4969&rep=rep1&type=pdf for details.
internal object Devirtualization2 {

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

        private sealed class Type {
            abstract fun cast(type: KotlinType): Type

            class Simple(val type: KotlinType): Type() {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other?.javaClass != javaClass) return false

                    other as Simple

                    if (type != other.type) return false

                    return true
                }

                override fun hashCode(): Int {
                    return type.hashCode()
                }

                override fun cast(type: KotlinType): Type {
                    assert(!TypeUtils.isNullableType(type), { "Nullable types should've been made non-nullable" })
                    if (this.type.isSubtypeOf(type)) return this
                    return Simple(type)
                }
            }

            class Instantiated(val type: KotlinType): Type() {
                val fields = mutableMapOf<PropertyDescriptor, Multiple>()
                val dependencies = mutableSetOf<ConstraintGraph.Node>()

                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other?.javaClass != javaClass) return false

                    other as Instantiated

                    if (type != other.type) return false
                    if (fields.size != other.fields.size) return false
                    return fields.all { other.fields[it.key] == it.value }

                    // TODO: Why this does not compile?
//                    fields.forEach { field, fieldType ->
//                        val otherFieldType = other.fields[field] ?: return false
//                    }
                }

                override fun hashCode(): Int {
                    var result = type.hashCode()
                    val fieldsHashCodes = fields.map { it.key.hashCode() * 31 + it.value.hashCode() }.sorted()
                    fieldsHashCodes.forEach { result = result * 31 + it }
                    return result
                }

                override fun cast(type: KotlinType): Type {
                    assert(!TypeUtils.isNullableType(type), { "Nullable types should've been made non-nullable" })
                    if (this.type.isSubtypeOf(type)) return this
                    return Simple(type)
                }
            }

            class Multiple : Type() {
                val types = mutableSetOf<Type>()

                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other?.javaClass != javaClass) return false

                    other as Multiple

                    if (types.size != other.types.size) return false

                    return types.all { other.types.contains(it) }
                }

                override fun hashCode(): Int {
                    val hashCodes = types.map { it.hashCode() }.sorted()
                    var result = 0
                    hashCodes.forEach { result = result * 31 + it }
                    return result
                }

                override fun cast(type: KotlinType): Type {
                    var changed = false
                    val castTypes = types.map {
                        val castedType = it.cast(type)
                        if (castedType != it) changed = true
                        castedType
                    }
                    if (!changed) return this
                    return Multiple().also { types.addAll(castTypes) }
                }
            }

            class Globals: Type() {
                val fields = mutableMapOf<PropertyDescriptor, Multiple>()

                override fun cast(type: KotlinType): Type {
                    error("There should not be a cast from fictitious Globals type.")
                }
            }

            companion object {
                val Globals = Globals()
            }
        }

        // A contour is a specialized function template.
        private class Contour(val descriptor: CallableDescriptor, val parameterTypes: List<Type>) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other?.javaClass != javaClass) return false

                other as Contour

                if (descriptor != other.descriptor) return false
                if (parameterTypes.size != other.parameterTypes.size) return false
                return parameterTypes.withIndex().all { it.value == other.parameterTypes[it.index] }
            }

            override fun hashCode(): Int {
                var result = descriptor.hashCode()
                parameterTypes.forEach { result = result * 31 + it.hashCode() }
                return result
            }
        }

        private val KotlinType.hasPolymorphicFields: Boolean
            get() = context.getFields(constructor as ClassDescriptor).any { !it.type.isFinal }

        private fun KotlinType.getInstanceType() =
                if (hasPolymorphicFields)
                    Type.Instantiated(this)
                else Type.Simple(this)

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

        private class ConstraintGraph {

            val nodes = mutableListOf<Node>()

            val globalsNode = Node.Const(Type.Globals)

            // All contours used in a program.
            val contours = mutableMapOf<Contour, Node.Contour>()

            fun addNode(node: Node) = nodes.add(node)

            sealed class Node(val type: Type) {

                val edges = mutableListOf<Node>()

                // Corresponds to a whole contour.
                class Contour(val contour: InterproceduralAnalysis.Contour) : Node(Type.Multiple())

                class Const(type: Type) : Node(type)

                abstract class Call(type: Type, val callee: FunctionDescriptor, val arguments: List<Node>) : Node(type) {
                    var initialized = false
                    val contours = mutableSetOf<InterproceduralAnalysis.Contour>()
                }

                class StaticCall(callee: FunctionDescriptor, arguments: List<Node>)
                    : Call(Type.Multiple(), callee, arguments)

                class VirtualCall(callee: FunctionDescriptor, arguments: List<Node>)
                    : Call(Type.Multiple(), callee, arguments)

                class NewObject(type: Type, constructor: ConstructorDescriptor, arguments: List<Node>)
                    : Call(type, constructor, arguments)

                class FieldRead(val receiver: Node, val field: PropertyDescriptor) : Node(Type.Multiple())

                class FieldWrite(val receiver: Node, val field: PropertyDescriptor, val value: Node) : Node(Type.Multiple())

                // Corresponds to an edge with a cast on it.
                class Cast(val castToType: KotlinType) : Node(Type.Multiple())
            }
        }

        private val constraintGraph = ConstraintGraph()

        private val singletons = mutableMapOf<KotlinType, Type>()

        fun analyze(irModule: IrModuleFragment): Map<IrMemberAccessExpression, DevirtualizedCallSite> {
            val rootSet = getRootSet(irModule)
            val fictitiousRootNode = ConstraintGraph.Node.Const(Type.Multiple())
            constraintGraph.addNode(fictitiousRootNode)
            rootSet.forEach {
                val types = ((it as? FunctionDescriptor)?.allParameters ?: emptyList<ParameterDescriptor>())
                        .map { it.type.erasure().makeNotNullable() }
                val contour = Contour(it, types.map { Type.Simple(it) })
                addCallSite(fictitiousRootNode, contour)
            }
            return mutableMapOf()
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

        private class RefreshedNode(val node: ConstraintGraph.Node, val newType: Type)

        private fun addCallSite(callSite: ConstraintGraph.Node, callee: Contour) {
            var calleeNode = constraintGraph.contours[callee]
            if (calleeNode != null) {
                calleeNode.edges += callSite
                // TODO: propagate callee type to callSite.
                return
            }
            calleeNode = ConstraintGraph.Node.Contour(callee)
            val template = intraproceduralAnalysisResult.functionTemplates[callee.descriptor]!!
            val body = template.body
            val contourNodes = mutableMapOf<FunctionTemplateBody.Node, ConstraintGraph.Node>()
            val localRoots = mutableListOf<ConstraintGraph.Node>(constraintGraph.globalsNode)
            body.nodes.forEach { templateNodeToConstraintNode(callee, it, contourNodes, localRoots) }
            val returnsNode = templateNodeToConstraintNode(callee, body.returns, contourNodes, localRoots)
            returnsNode.edges += calleeNode
            calleeNode.edges += callSite
            constraintGraph.contours.put(callee, calleeNode)

            for (root in localRoots) {
                // A root within a function can be either a Node.Const or a Node.Call with no arguments.
                if (root is ConstraintGraph.Node.Call && !root.initialized) {
                    root.initialized = true
                    addCallSite(root, Contour(root.callee, emptyList()))
                }
                // TODO: in case of a call do we need this? Seems like all the work should've been done by the previous call to addCallSite.
                root.edges.forEach { propagateType(it, root.type) }
            }
        }

        private fun propagateType(node: ConstraintGraph.Node, type: Type) {
            if (type is Type.Multiple) {
                type.types.forEach { propagateType(node, it) }
                return
            }
            val nodeType = node.type
            assert(nodeType is Type.Multiple, { "Unexpected node type: $nodeType" })
            nodeType as Type.Multiple
            if (nodeType.types.contains(type)) return
            nodeType.types += type
            node.edges.forEach { propagateType(node, it, type) }
        }

        private fun propagateType(from: ConstraintGraph.Node, to: ConstraintGraph.Node, type: Type) {
            when (to) {
                is ConstraintGraph.Node.Contour -> {
                    // Set contour's return value.
                    propagateType(to, type)
                }

                is ConstraintGraph.Node.Const -> {
                    // Const here means a proxy node transferring its type along the edges.
                    propagateType(to, type)
                }

                is ConstraintGraph.Node.Cast -> {
                    propagateType(to, type.cast(to.castToType))
                }

                is ConstraintGraph.Node.Call -> {
                    if (to.arguments.any { it.type.let { it is Type.Multiple && it.types.isEmpty() } }) // Empty type set.
                        return
                    val argumentTypes = Array<Type>(to.arguments.size) { Type.Globals /* Just a place holder. */ }
                    if (!to.initialized) {
                        to.initialized = true
                        // Compute cartesian product of all arguments.
                        generateCartesianProduct(to, 0, argumentTypes, -1)
                    } else {
                        val index = to.arguments.indexOf(from)
                        assert(index >= 0, { "Unexpected argument: $from" })
                        argumentTypes[index] = type
                        // Compute cartesian product of all arguments except current.
                        generateCartesianProduct(to, 0, argumentTypes, index)
                    }
                }

                is ConstraintGraph.Node.FieldRead -> {
                    assert(from == to.receiver, { "FieldRead has only one incoming edge - receiver, but actually is: $from" })
                    val fieldType = to.field.type.erasure().makeNotNullable()
                    if (fieldType.isFinal)
                        propagateType(to, Type.Simple(fieldType))
                    else {
                        when (type) {
                            is Type.Instantiated -> {
                                val actualFieldType = type.fields[to.field]
                                if (actualFieldType != null) {
                                    propagateType(to, actualFieldType)
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun generateCartesianProduct(callSite: ConstraintGraph.Node.Call, index: Int, argumentTypes: Array<Type>, skipIndex: Int) {
            if (index >= argumentTypes.size) {
                doCall(callSite, argumentTypes.toList())
                return
            }
            if (index == skipIndex) {
                generateCartesianProduct(callSite, index + 1, argumentTypes, skipIndex)
                return
            }
            val argumentType = callSite.arguments[index].type
            if (argumentType !is Type.Multiple) {
                argumentTypes[index] = argumentType
                generateCartesianProduct(callSite, index + 1, argumentTypes, skipIndex)
            } else {
                argumentType.types.forEach {
                    argumentTypes[index] = it
                    generateCartesianProduct(callSite, index + 1, argumentTypes, skipIndex)
                }
            }
        }

        private fun doCall(callSite: ConstraintGraph.Node.Call, argumentTypes: List<Type>) {
            val callee = callSite.callee
            val actualCallee = if (callSite !is ConstraintGraph.Node.VirtualCall)
                callee
            else {
                val receiverType = argumentTypes.first().let {
                    when (it) {
                        is Type.Simple -> it.type
                        is Type.Instantiated -> it.type
                        else -> error("Unexpected type: $it")
                    }
                }
                val receiverScope = (receiverType.constructor.declarationDescriptor as ClassDescriptor).unsubstitutedMemberScope
                receiverScope.getOverridingOf(callee) ?: callee
            }
            val contour = Contour(actualCallee, argumentTypes)
            if (callSite.contours.contains(contour)) return
            addCallSite(callSite, contour)
        }

        /**
         * Takes a function template's node and creates a constraint graph node corresponding to it.
         * Also creates all necessary edges.
         */
        private fun templateNodeToConstraintNode(contour: Contour,
                                                 node: FunctionTemplateBody.Node,
                                                 contourNodes: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                                 roots: MutableList<ConstraintGraph.Node>)
                : ConstraintGraph.Node {

            fun edgeToConstraintNode(edge: FunctionTemplateBody.Edge): ConstraintGraph.Node {
                val result = templateNodeToConstraintNode(contour, edge.node, contourNodes, roots)
                return edge.castToType?.let {
                    val castNode = ConstraintGraph.Node.Cast(it)
                    constraintGraph.addNode(castNode)
                    result.edges += castNode
                    castNode
                } ?: result
            }

            fun createConstraintNode(edges: List<FunctionTemplateBody.Edge>,
                                     factory: (List<ConstraintGraph.Node>) -> ConstraintGraph.Node) =
                    edges.map { edgeToConstraintNode(it) }.let { nodes ->
                        factory(nodes).also { caller ->
                            constraintGraph.addNode(caller)
                            nodes.forEach { it.edges += caller }
                        }
                    }

            return contourNodes.getOrPut(node) {
                when (node) {
                    is FunctionTemplateBody.Node.Const ->
                        ConstraintGraph.Node.Const(Type.Simple(node.type)).also {
                            constraintGraph.addNode(it)
                            roots += it
                        }

                    is FunctionTemplateBody.Node.Parameter ->
                        ConstraintGraph.Node.Const(contour.parameterTypes[node.index]).also {
                            constraintGraph.addNode(it)
                            roots += it
                        }

                    is FunctionTemplateBody.Node.Call ->
                        createConstraintNode(node.arguments) { arguments ->
                            ConstraintGraph.Node.StaticCall(node.callee, arguments).also {
                                if (arguments.isEmpty())
                                    roots += it
                            }
                        }

                    is FunctionTemplateBody.Node.VirtualCall ->
                        createConstraintNode(node.arguments) { ConstraintGraph.Node.VirtualCall(node.callee, it) }

                    is FunctionTemplateBody.Node.NewObject ->
                        createConstraintNode(node.arguments) { arguments ->
                            val instanceType = node.constructor.constructedClass.defaultType.getInstanceType()
                            val instanceNode = ConstraintGraph.Node.Const(instanceType).also {
                                constraintGraph.addNode(it)
                                roots += it
                            }
                            ConstraintGraph.Node.NewObject(instanceType, node.constructor, listOf(instanceNode) + arguments)
                        }

                    is FunctionTemplateBody.Node.Singleton ->
                        ConstraintGraph.Node.Const(singletons.getOrPut(node.type) { node.type.getInstanceType() }).also {
                            constraintGraph.addNode(it)
                            roots += it
                        }

                    is FunctionTemplateBody.Node.FieldRead -> {
                        val receiver = node.receiver?.let { edgeToConstraintNode(it) } ?: constraintGraph.globalsNode
                        ConstraintGraph.Node.FieldRead(receiver, node.field).also {
                            constraintGraph.addNode(it)
                            receiver.edges += it
                        }
                    }

                    is FunctionTemplateBody.Node.FieldWrite -> {
                        val receiver = node.receiver?.let { edgeToConstraintNode(it) } ?: constraintGraph.globalsNode
                        val value = edgeToConstraintNode(node.value)
                        ConstraintGraph.Node.FieldWrite(receiver, node.field, value).also {
                            constraintGraph.addNode(it)
                            receiver.edges += it
                            value.edges += it
                        }
                    }

                    is FunctionTemplateBody.Node.Proxy ->
                        createConstraintNode(node.values) { ConstraintGraph.Node.Const(Type.Multiple()) }
                }
            }
        }
    }

    internal class DevirtualizedCallSite(val possibleCallees: List<FunctionDescriptor>)

    internal fun devirtualize(irModule: IrModuleFragment, context: Context)
            : Map<IrMemberAccessExpression, DevirtualizedCallSite> {
        val isStdlib = context.config.configuration[KonanConfigKeys.NOSTDLIB] == true
        return mapOf()
    }
}