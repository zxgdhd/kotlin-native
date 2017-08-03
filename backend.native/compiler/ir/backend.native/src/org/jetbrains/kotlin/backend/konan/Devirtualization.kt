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

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.ir.ir2stringWhole
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irCast
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.descriptors.target
import org.jetbrains.kotlin.backend.konan.ir.IrReturnableBlock
import org.jetbrains.kotlin.backend.konan.ir.IrSuspendableExpression
import org.jetbrains.kotlin.backend.konan.ir.IrSuspensionPoint
import org.jetbrains.kotlin.backend.konan.llvm.findMainEntryPoint
import org.jetbrains.kotlin.backend.konan.llvm.isExported
import org.jetbrains.kotlin.backend.konan.serialization.symbolName
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isSubclassOf
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.*

// TODO: Exceptions, Arrays.

// Devirtualization analysis is performed using Variable Type Analysis algorithm.
// See <TODO: link to the article> for details.
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

    private val KotlinType.isFinal get() = (constructor.declarationDescriptor as ClassDescriptor).modality == Modality.FINAL

    private class FunctionTemplateBody(val nodes: List<Node>, val returns: Node.Proxy) {
        class Edge(val node: Node, val castToType: KotlinType?)

        sealed class Node {
            class Parameter(val index: Int): Node()

            class Const(val type: KotlinType): Node()

            class StaticCall(val callee: FunctionDescriptor, val arguments: List<Edge>): Node()

            class VirtualCall(val callee: FunctionDescriptor, val arguments: List<Edge>, val callSite: IrCall?): Node()

            class NewObject(val constructor: ConstructorDescriptor, val arguments: List<Edge>): Node()

            class Singleton(val type: KotlinType): Node()

            class FieldRead(val receiver: Edge?, val field: PropertyDescriptor): Node()

            class FieldWrite(val receiver: Edge?, val field: PropertyDescriptor, val value: Edge): Node()

            class Proxy(val values: List<Edge>): Node()

            class Variable(val values: MutableList<Edge>): Node()
        }
    }

    private class FunctionTemplate(val numberOfParameters: Int, val body: FunctionTemplateBody) {

        companion object {
            fun create(descriptor: CallableDescriptor,
                       expressions: List<IrExpression>,
                       variableValues: VariableValues,
                       returnValues: List<IrExpression>,
                       expressionValuesExtractor: ExpressionValuesExtractor) =
                    Builder(expressionValuesExtractor, variableValues, descriptor, expressions, returnValues).template

            private class Builder(val expressionValuesExtractor: ExpressionValuesExtractor,
                                  val variableValues: VariableValues,
                                  val descriptor: CallableDescriptor,
                                  expressions: List<IrExpression>,
                                  returnValues: List<IrExpression>) {

                private val templateParameters =
                        ((descriptor as? FunctionDescriptor)?.allParameters ?: emptyList())
                                .withIndex()
                                .associateBy({ it.value }, { FunctionTemplateBody.Node.Parameter(it.index) })

                private val nodes = mutableMapOf<IrExpression, FunctionTemplateBody.Node>()
                private val variables = mutableMapOf<VariableDescriptor, FunctionTemplateBody.Node.Variable>()
                val template: FunctionTemplate

                init {
                    for (expression in expressions) {
                        getNode(expression)
                    }
                    val returns = FunctionTemplateBody.Node.Proxy(returnValues.map { expressionToEdge(it) })
                    val allNodes = (nodes.values + variables.values).distinct().toList()
                    template = FunctionTemplate(templateParameters.size, FunctionTemplateBody(allNodes, returns))
                }

                private fun expressionToEdge(expression: IrExpression) =
                        if (expression is IrTypeOperatorCall && expression.operator.isCast())
                            FunctionTemplateBody.Edge(getNode(expression.argument), expression.typeOperand.erasure().makeNotNullable())
                        else FunctionTemplateBody.Edge(getNode(expression), null)

                private fun getVariableNode(descriptor: VariableDescriptor): FunctionTemplateBody.Node {
                    var variable = variables[descriptor]
                    if (variable == null) {
                        val values = mutableListOf<FunctionTemplateBody.Edge>()
                        variable = FunctionTemplateBody.Node.Variable(values)
                        variables[descriptor] = variable
                        variableValues.elementData[descriptor]!!.forEach {
                            values += expressionToEdge(it)
                        }
                    }
                    return variable
                }

                private fun getNode(expression: IrExpression): FunctionTemplateBody.Node {
                    if (expression is IrGetValue) {
                        val descriptor = expression.descriptor
                        if (descriptor is ParameterDescriptor)
                            return templateParameters[descriptor]!!
                        return getVariableNode(descriptor as VariableDescriptor)
                    }
                    return nodes.getOrPut(expression) {
                        if (DEBUG > 1) {
                            println("Converting expression")
                            println(ir2stringWhole(expression))
                        }
                        val values = mutableListOf<IrExpression>()
                        expressionValuesExtractor.forEachValue(expression) { values += it }
                        if (values.size != 1)
                            FunctionTemplateBody.Node.Proxy(values.map { expressionToEdge(it) })
                        else {
                            val value = values[0]
                            if (value != expression) {
                                val edge = expressionToEdge(value)
                                if (edge.castToType == null)
                                    edge.node
                                else
                                    FunctionTemplateBody.Node.Proxy(listOf(edge))
                            } else {
                                when (value) {
                                    is IrGetValue -> getNode(value)

                                    is IrVararg,
                                    is IrConst<*> -> FunctionTemplateBody.Node.Const(value.type)

                                    is IrGetObjectValue -> FunctionTemplateBody.Node.Singleton(value.type)

                                    is IrCall -> {
                                        val arguments = value.getArguments()
                                        val callee = value.descriptor.target
                                        if (callee is ConstructorDescriptor)
                                            FunctionTemplateBody.Node.NewObject(
                                                    callee,
                                                    arguments.map { expressionToEdge(it.second) }
                                            )
                                        else {
                                            if (callee.isOverridable && value.superQualifier == null) {
                                                FunctionTemplateBody.Node.VirtualCall(
                                                        callee,
                                                        arguments.map { expressionToEdge(it.second) },
                                                        value
                                                )
                                            }
                                            else {
                                                val actualCallee = value.superQualifier.let {
                                                    if (it == null)
                                                        callee
                                                    else
                                                        it.unsubstitutedMemberScope.getOverridingOf(callee)?.target ?: callee
                                                }
                                                FunctionTemplateBody.Node.StaticCall(
                                                        actualCallee,
                                                        arguments.map { expressionToEdge(it.second) }
                                                )
                                            }
                                        }
                                    }

                                    is IrDelegatingConstructorCall -> {
                                        val thiz = IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                                                (descriptor as ConstructorDescriptor).constructedClass.thisAsReceiverParameter)
                                        val arguments = listOf(thiz) + value.getArguments().map { it.second }
                                        FunctionTemplateBody.Node.StaticCall(
                                                value.descriptor,
                                                arguments.map { expressionToEdge(it) }
                                        )
                                    }

                                    is IrGetField -> {
                                        val receiver = value.receiver?.let { expressionToEdge(it) }
                                        FunctionTemplateBody.Node.FieldRead(
                                                receiver,
                                                value.descriptor
                                        )
                                    }

                                    is IrSetField -> {
                                        val receiver = value.receiver?.let { expressionToEdge(it) }
                                        FunctionTemplateBody.Node.FieldWrite(
                                                receiver,
                                                value.descriptor,
                                                expressionToEdge(value.value)
                                        )
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

        private fun printNode(node: FunctionTemplateBody.Node, ids: Map<FunctionTemplateBody.Node, Int>) {
            when (node) {
                is FunctionTemplateBody.Node.Const ->
                    println("        CONST ${node.type}")

                is FunctionTemplateBody.Node.Parameter ->
                    println("        PARAM ${node.index}")

                is FunctionTemplateBody.Node.Singleton ->
                    println("        SINGLETON ${node.type}")

                is FunctionTemplateBody.Node.StaticCall -> {
                    println("        STATIC CALL ${node.callee}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.VirtualCall -> {
                    println("        VIRTUAL CALL ${node.callee}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.NewObject -> {
                    println("        NEW OBJECT ${node.constructor}")
                    node.arguments.forEach {
                        print("            ARG #${ids[it.node]}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }
                }

                is FunctionTemplateBody.Node.FieldRead -> {
                    println("        FIELD READ ${node.field}")
                    print("            RECEIVER #${node.receiver?.node?.let { ids[it] } ?: "null"}")
                    if (node.receiver?.castToType == null)
                        println()
                    else
                        println(" CASTED TO ${node.receiver.castToType}")
                }

                is FunctionTemplateBody.Node.FieldWrite -> {
                    println("        FIELD WRITE ${node.field}")
                    print("            RECEIVER #${node.receiver?.node?.let { ids[it] } ?: "null"}")
                    if (node.receiver?.castToType == null)
                        println()
                    else
                        println(" CASTED TO ${node.receiver.castToType}")
                    print("            VALUE #${ids[node.value.node]}")
                    if (node.value.castToType == null)
                        println()
                    else
                        println(" CASTED TO ${node.value.castToType}")
                }

                is FunctionTemplateBody.Node.Proxy -> {
                    println("        PROXY")
                    node.values.forEach {
                        print("            VAL #${ids[it.node]}")
                        if (it.castToType == null)
                            println()
                        else
                            println(" CASTED TO ${it.castToType}")
                    }

                }
            }
        }

        fun debugOutput() {
            val ids = body.nodes.withIndex().associateBy({ it.value }, { it.index })
            body.nodes.forEach {
                println("    NODE #${ids[it]}")
                printNode(it, ids)
            }
            println("    RETURNS")
            printNode(body.returns, ids)
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
                        templates.put(declaration.descriptor, analyze(declaration.descriptor, it))
                    }
                }

                override fun visitField(declaration: IrField) {
                    declaration.initializer?.let {
                        if (DEBUG > 1)
                            println("Analysing global field ${declaration.descriptor}")
                        templates.put(declaration.descriptor, analyze(declaration.descriptor, it))
                    }
                }

                private fun analyze(descriptor: CallableDescriptor,
                                    body: IrElement): FunctionTemplate {
                    // Find all interesting expressions, variables and functions.
                    val visitor = ElementFinderVisitor()
                    body.acceptVoid(visitor)

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

                    val functionTemplate = FunctionTemplate.create(descriptor,
                            visitor.expressions, visitor.variableValues,
                            visitor.returnValues, expressionValuesExtractor)

                    if (DEBUG > 1) {
                        println("FUNCTION TEMPLATE")
                        functionTemplate.debugOutput()
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

    private fun getInstantiatingClasses(functionTemplates: Map<CallableDescriptor, FunctionTemplate>)
            : Set<ClassDescriptor> {
        val instantiatingClasses = mutableSetOf<ClassDescriptor>()
        functionTemplates.values
                .asSequence()
                .flatMap { it.body.nodes.asSequence() }
                .forEach {
                    if (it is FunctionTemplateBody.Node.NewObject)
                        instantiatingClasses += it.constructor.constructedClass
                    else if (it is FunctionTemplateBody.Node.Singleton)
                        instantiatingClasses += it.type.constructor.declarationDescriptor as ClassDescriptor
                }
        return instantiatingClasses
    }

    private class InterproceduralAnalysis(val context: Context,
                                          val intraproceduralAnalysisResult: IntraproceduralAnalysisResult) {

        private class ConstraintGraph {

            val nodes = mutableListOf<Node>()
            val voidNode = Node.Const("Void").also { nodes.add(it) }
            val functions = mutableMapOf<CallableDescriptor, Function>()
            val classes = mutableMapOf<ClassDescriptor, Node>()
            val fields = mutableMapOf<PropertyDescriptor, Node>() // Do not distinguish receivers.
            val virtualCallSiteReceivers = mutableMapOf<IrCall, Pair<Node, CallableDescriptor>>()

            fun addNode(node: Node) = nodes.add(node)

            class Function(val descriptor: CallableDescriptor, val parameters: Array<Node>, val returns: Node)

            enum class TypeKind {
                CONCRETE,
                VIRTUAL
            }

            data class Type(val type: KotlinType, val kind: TypeKind) {
                companion object {
                    fun concrete(type: KotlinType) =
                            Type(type.erasure().makeNotNullable(), TypeKind.CONCRETE)

                    fun virtual(type: KotlinType) =
                            Type(type.erasure().makeNotNullable(), if (type.isFinal) TypeKind.CONCRETE else TypeKind.VIRTUAL)
                }
            }

            sealed class Node {
                val types = mutableSetOf<Type>()
                val edges = mutableListOf<Node>()
                val reversedEdges = mutableListOf<Node>()

                fun addEdge(node: Node) {
                    edges += node
                    node.reversedEdges += this
                }

                class Const(val name: String) : Node() {
                    constructor(name: String, type: Type): this(name) {
                        types += type
                    }

                    override fun toString(): String {
                        return "Const(name='$name')"
                    }
                }

                // Corresponds to an edge with a cast on it.
                class Cast(val castToType: KotlinType) : Node() {
                    override fun toString() = "Cast(castToType=$castToType)"
                }
            }

            class MultiNode(val nodes: Set<Node>)

            class Condensation(val topologicalOrder: List<MultiNode>)

            private inner class CondensationBuilder {
                private val visited = mutableSetOf<Node>()
                private val order = mutableListOf<Node>()
                private val nodeToMultiNodeMap = mutableMapOf<Node, MultiNode>()
                private val multiNodesOrder = mutableListOf<MultiNode>()

                fun build(): Condensation {
                    // First phase.
                    nodes.forEach {
                        if (!visited.contains(it))
                            findOrder(it)
                    }

                    // Second phase.
                    visited.clear()
                    val multiNodes = mutableListOf<MultiNode>()
                    order.reversed().forEach {
                        if (!visited.contains(it)) {
                            val nodes = mutableSetOf<Node>()
                            paint(it, nodes)
                            multiNodes += MultiNode(nodes)
                        }
                    }

                    // Topsort of built condensation.
                    multiNodes.forEach { multiNode ->
                        multiNode.nodes.forEach { nodeToMultiNodeMap.put(it, multiNode) }
                    }
                    visited.clear()
                    multiNodes.forEach {
                        if (!visited.contains(it.nodes.first()))
                            findMultiNodesOrder(it)
                    }

                    return Condensation(multiNodesOrder)
                }

                private fun findOrder(node: Node) {
                    visited += node
                    node.edges.forEach {
                        if (!visited.contains(it))
                            findOrder(it)
                    }
                    order += node
                }

                private fun paint(node: Node, multiNode: MutableSet<Node>) {
                    visited += node
                    multiNode += node
                    node.reversedEdges.forEach {
                        if (!visited.contains(it))
                            paint(it, multiNode)
                    }
                }

                private fun findMultiNodesOrder(node: MultiNode) {
                    visited.addAll(node.nodes)
                    node.nodes.forEach {
                        it.edges.forEach {
                            if (!visited.contains(it))
                                findMultiNodesOrder(nodeToMultiNodeMap[it]!!)
                        }
                    }
                    multiNodesOrder += node
                }
            }

            fun buildCondensation() = CondensationBuilder().build()
        }

        private val constraintGraph = ConstraintGraph()

        fun analyze(irModule: IrModuleFragment): Map<IrCall, DevirtualizedCallSite> {
            // Rapid Type Analysis: find all instantiations and conservatively estimate call graph.
            val functionTemplates = intraproceduralAnalysisResult.functionTemplates
            val instantiatingClasses = getInstantiatingClasses(functionTemplates)

            val nodesMap = mutableMapOf<FunctionTemplateBody.Node, ConstraintGraph.Node>()
            val variables = mutableMapOf<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>()
            functionTemplates.keys.forEach {
                buildFunctionConstraintGraph(it, nodesMap, variables, instantiatingClasses)
            }
            val rootSet = getRootSet(irModule)
            rootSet.filterIsInstance<FunctionDescriptor>()
                    .forEach {
                        if (constraintGraph.functions[it] == null)
                            println("BUGBUGBUG: $it")
                        val function = constraintGraph.functions[it]!!
                        it.allParameters.withIndex().forEach {
                            val parameterType = it.value.type.erasure().makeNotNullable()
                            val parameterClassDescriptor = parameterType.constructor.declarationDescriptor as ClassDescriptor
                            val node = constraintGraph.classes.getOrPut(parameterClassDescriptor) {
                                ConstraintGraph.Node.Const("Root", ConstraintGraph.Type.virtual(parameterType)).also {
                                    constraintGraph.addNode(it)
                                }
                            }
                            node.addEdge(function.parameters[it.index])
                        }
                    }
            if (DEBUG > 0) {
                println("CONSTRAINT GRAPH")
                val ids = constraintGraph.nodes.withIndex().associateBy({ it.value }, { it.index })
                constraintGraph.nodes.forEach {
                    println("    NODE #${ids[it]}: $it")
                    it.edges.forEach {
                        println("        EDGE: #${ids[it]}")
                    }
                    it.types.forEach { println("        TYPE: $it") }
                }
            }
            val condensation = constraintGraph.buildCondensation()
            if (DEBUG > 0) {
                println("CONDENSATION")
                val ids = constraintGraph.nodes.withIndex().associateBy({ it.value }, { it.index })
                condensation.topologicalOrder.reversed().forEachIndexed { index, multiNode ->
                    println("    MULTI-NODE #$index")
                    multiNode.nodes.forEach {
                        println("        #${ids[it]}: $it")
                    }
                }
            }
            condensation.topologicalOrder.reversed().forEachIndexed { index, multiNode ->
                val types = mutableSetOf<ConstraintGraph.Type>()
                val badTypes = mutableSetOf<ConstraintGraph.Type>()
                multiNode.nodes.forEach { types.addAll(it.types) }
                multiNode.nodes
                        .filterIsInstance<ConstraintGraph.Node.Cast>()
                        .forEach {
                            val castToType = it.castToType
                            var wasVirtualType = false
                            types.forEach {
                                if (!it.type.isSubtypeOf(castToType)) {
                                    badTypes += it
                                    if (it.kind == ConstraintGraph.TypeKind.VIRTUAL)
                                        wasVirtualType = true
                                }
                            }
                            if (wasVirtualType)
                                types += ConstraintGraph.Type.virtual(castToType)
                        }
                types -= badTypes
                if (DEBUG > 0) {
                    println("Types of multi-node #$index")
                    types.forEach { println("    $it") }
                }
                multiNode.nodes.forEach {
                    it.types.clear()
                    it.types += types
                    it.edges.forEach {
                        it.types += types
                    }
                }
            }
            val result = mutableMapOf<IrCall, Pair<DevirtualizedCallSite, CallableDescriptor>>()
            functionTemplates.values
                    .asSequence()
                    .flatMap { it.body.nodes.asSequence() }
                    .filterIsInstance<FunctionTemplateBody.Node.VirtualCall>()
                    .forEach {
                        val node = nodesMap[it]!!
                        if (it.callSite == null || node.types.isEmpty() || node.types.any { it.kind == ConstraintGraph.TypeKind.VIRTUAL })
                            return@forEach
                        val receiver = constraintGraph.virtualCallSiteReceivers[it.callSite]
                        if (receiver != null) {
                            val possibleReceivers = receiver.first.types
                                    .filterNot { it.type.isNothing() }
                                    .map { it.type.constructor.declarationDescriptor as ClassDescriptor }
                            result.put(it.callSite, DevirtualizedCallSite(possibleReceivers) to receiver.second)
                        }
                    }
            if (DEBUG > 0) {
                result.forEach { callSite, devirtualizedCallSite ->
                    if (devirtualizedCallSite.first.possibleReceivers.isNotEmpty()) {
                        println("FUNCTION: ${devirtualizedCallSite.second}")
                        println("CALL SITE: ${ir2stringWhole(callSite)}")
                        println("POSSIBLE RECEIVERS:")
                        devirtualizedCallSite.first.possibleReceivers.forEach { println("    $it") }
                        println()
                    }
                }
            }
            return result.asSequence().associateBy({ it.key }, { it.value.first })
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
                    if (!hasMain && declaration.descriptor.isExported() && declaration.descriptor.modality != Modality.ABSTRACT
                            && !declaration.descriptor.isExternal && declaration.descriptor.kind != CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                        // For a library take all visible functions.
                        rootSet += declaration.descriptor
                }
            }, data = null)
            return rootSet
        }

        private fun buildFunctionConstraintGraph(descriptor: CallableDescriptor,
                                                 nodes: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                                 variables: MutableMap<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>,
                                                 instantiatingClasses: Collection<ClassDescriptor>): ConstraintGraph.Function {
            constraintGraph.functions[descriptor]?.let { return it }

            val template = intraproceduralAnalysisResult.functionTemplates[descriptor] ?: createEmptyTemplate(descriptor)
            val body = template.body
            val parameters = Array<ConstraintGraph.Node>(template.numberOfParameters) {
                ConstraintGraph.Node.Const("Param#$it\$$descriptor").also {
                    constraintGraph.addNode(it)
                }
            }
            val returnsNode = ConstraintGraph.Node.Const("Returns\$$descriptor").also {
                constraintGraph.addNode(it)
            }
            val function = ConstraintGraph.Function(descriptor, parameters, returnsNode)
            constraintGraph.functions[descriptor] = function
            body.nodes.forEach { templateNodeToConstraintNode(function, it, nodes, variables, instantiatingClasses) }
            body.returns.values.forEach {
                edgeToConstraintNode(function, it, nodes, variables, instantiatingClasses).addEdge(returnsNode)
            }
            return function
        }

        private fun createEmptyTemplate(descriptor: CallableDescriptor): FunctionTemplate {
            val expressionValuesExtractor = ExpressionValuesExtractor(emptyMap(), emptyMap())
            return FunctionTemplate.create(descriptor, emptyList(), VariableValues(), emptyList(), expressionValuesExtractor)
        }

        private fun edgeToConstraintNode(function: ConstraintGraph.Function,
                                         edge: FunctionTemplateBody.Edge,
                                         functionNodesMap: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                         variables: MutableMap<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>,
                                         instantiatingClasses: Collection<ClassDescriptor>): ConstraintGraph.Node {
            val result = templateNodeToConstraintNode(function, edge.node, functionNodesMap, variables, instantiatingClasses)
            return edge.castToType?.let {
                val castNode = ConstraintGraph.Node.Cast(it)
                constraintGraph.addNode(castNode)
                result.addEdge(castNode)
                castNode
            } ?: result
        }

        /**
         * Takes a function template's node and creates a constraint graph node corresponding to it.
         * Also creates all necessary edges.
         */
        private fun templateNodeToConstraintNode(function: ConstraintGraph.Function,
                                                 node: FunctionTemplateBody.Node,
                                                 functionNodesMap: MutableMap<FunctionTemplateBody.Node, ConstraintGraph.Node>,
                                                 variables: MutableMap<FunctionTemplateBody.Node.Variable, ConstraintGraph.Node>,
                                                 instantiatingClasses: Collection<ClassDescriptor>)
                : ConstraintGraph.Node {

            fun edgeToConstraintNode(edge: FunctionTemplateBody.Edge): ConstraintGraph.Node =
                    edgeToConstraintNode(function, edge, functionNodesMap, variables, instantiatingClasses)

            fun doCall(callee: ConstraintGraph.Function, arguments: List<Any>): ConstraintGraph.Node {
                assert(callee.parameters.size == arguments.size, { "" })
                callee.parameters.forEachIndexed { index, parameter ->
                    val argument = arguments[index].let {
                        when (it) {
                            is ConstraintGraph.Node -> it
                            is FunctionTemplateBody.Edge -> edgeToConstraintNode(it)
                            else -> error("Unexpected argument: $it")
                        }
                    }
                    argument.addEdge(parameter)
                }
                return callee.returns
            }

            if (node is FunctionTemplateBody.Node.Variable) {
                var variableNode = variables[node]
                if (variableNode == null) {
                    variableNode = ConstraintGraph.Node.Const("Variable\$${function.descriptor}").also {
                        constraintGraph.addNode(it)
                    }
                    variables[node] = variableNode
                    for (value in node.values) {
                        edgeToConstraintNode(value).addEdge(variableNode)
                    }
                }
                return variableNode
            }

            return functionNodesMap.getOrPut(node) {
                when (node) {
                    is FunctionTemplateBody.Node.Const ->
                        ConstraintGraph.Node.Const("Const\$${function.descriptor}", ConstraintGraph.Type.concrete(node.type)).also {
                            constraintGraph.addNode(it)
                        }

                    is FunctionTemplateBody.Node.Parameter ->
                        function.parameters[node.index]

                    is FunctionTemplateBody.Node.StaticCall ->
                        doCall(buildFunctionConstraintGraph(node.callee, functionNodesMap, variables, instantiatingClasses), node.arguments)

                    is FunctionTemplateBody.Node.VirtualCall -> {
                        val callee = node.callee
                        val (receiverType, callees) = run {
                                val descriptor = callee
                                val receiverType = descriptor.dispatchReceiverParameter!!.type.erasure()
                                val receiver = receiverType.constructor.declarationDescriptor as ClassDescriptor
                                // TODO: optimize by building type hierarchy.
                                val callees = instantiatingClasses
                                        .filter { it.isSubclassOf(receiver) }
                                        .map { it.unsubstitutedMemberScope.getOverridingOf(descriptor) ?: descriptor }
                                        .distinct()
                                receiverType to callees
                        }
                        if (DEBUG > 0) {
                            println("Virtual call")
                            println("Caller: ${function.descriptor}")
                            println("Callee: $callee")
                            println("Possible callees:")
                            callees.forEach { println("$it") }
                            println()
                        }
                        if (callees.isEmpty())
                            constraintGraph.voidNode
                        else {
                            val calleeConstraintGraphs = callees.map {
                                buildFunctionConstraintGraph(it, functionNodesMap, variables, instantiatingClasses)
                            }
                            val receiverNode = edgeToConstraintNode(node.arguments[0])
                            val castedReceiver = ConstraintGraph.Node.Cast(receiverType).also {
                                constraintGraph.addNode(it)
                            }
                            receiverNode.edges += castedReceiver
                            val result = if (calleeConstraintGraphs.size == 1) {
                                doCall(calleeConstraintGraphs[0], listOf(castedReceiver) + node.arguments.drop(1))
                            } else {
                                val returns = ConstraintGraph.Node.Const("VirtualCallReturns\$${function.descriptor}").also {
                                    constraintGraph.addNode(it)
                                }
                                calleeConstraintGraphs.forEach { doCall(it, listOf(castedReceiver) + node.arguments.drop(1)).addEdge(returns) }
                                returns
                            }
                            node.callSite?.let { constraintGraph.virtualCallSiteReceivers[it] = castedReceiver to function.descriptor }
                            result
                        }
                    }

                    is FunctionTemplateBody.Node.NewObject -> {
                        val instanceNode = constraintGraph.classes.getOrPut(node.constructor.constructedClass) {
                            val instanceType = ConstraintGraph.Type.concrete(node.constructor.constructedClass.defaultType)
                            ConstraintGraph.Node.Const("Instance\$${function.descriptor}", instanceType).also {
                                constraintGraph.addNode(it)
                            }
                        }
                        val constructorNode = buildFunctionConstraintGraph(node.constructor, functionNodesMap, variables, instantiatingClasses)
                        doCall(constructorNode, listOf(instanceNode) + node.arguments)
                        instanceNode
                    }

                    is FunctionTemplateBody.Node.Singleton ->
                        constraintGraph.classes.getOrPut(node.type.constructor.declarationDescriptor as ClassDescriptor) {
                            ConstraintGraph.Node.Const("Singleton\$${function.descriptor}", ConstraintGraph.Type.concrete(node.type)).also {
                                constraintGraph.addNode(it)
                            }
                        }

                    is FunctionTemplateBody.Node.FieldRead ->
                        constraintGraph.fields.getOrPut(node.field) {
                            ConstraintGraph.Node.Const("FieldRead\$${function.descriptor}").also {
                                constraintGraph.addNode(it)
                            }
                        }

                    is FunctionTemplateBody.Node.FieldWrite -> {
                        val fieldNode = constraintGraph.fields.getOrPut(node.field) {
                            ConstraintGraph.Node.Const("FieldWrite\$${function.descriptor}").also {
                                constraintGraph.addNode(it)
                            }
                        }
                        edgeToConstraintNode(node.value).addEdge(fieldNode)
                        constraintGraph.voidNode
                    }

                    is FunctionTemplateBody.Node.Proxy ->
                        node.values.map { edgeToConstraintNode(it) }.let { values ->
                            ConstraintGraph.Node.Const("Proxy\$${function.descriptor}").also { node ->
                                constraintGraph.addNode(node)
                                values.forEach { it.addEdge(node) }
                            }
                        }

                    is FunctionTemplateBody.Node.Variable ->
                        error("Variables should've been handled earlier")
                }
            }
        }
    }

    internal class DevirtualizedCallSite(val possibleReceivers: List<ClassDescriptor>)

    internal fun analyze(irModule: IrModuleFragment, context: Context) : Map<IrCall, DevirtualizedCallSite> {
        val isStdlib = context.config.configuration[KonanConfigKeys.NOSTDLIB] == true
        val intraproceduralAnalysisResult = IntraproceduralAnalysis().analyze(irModule)
        if (isStdlib) {
        }
        return InterproceduralAnalysis(context, intraproceduralAnalysisResult).analyze(irModule)
    }

    internal fun devirtualize(irModule: IrModuleFragment, context: Context,
                              devirtualizedCallSites: Map<IrCall, DevirtualizedCallSite>) {
        irModule.transformChildrenVoid(object: IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val devirtualizedCallSite = devirtualizedCallSites[expression]
                val actualReceiver = devirtualizedCallSite?.possibleReceivers?.singleOrNull()
                        ?: return expression
                val actualReceiverType = actualReceiver.defaultType
                val startOffset = expression.startOffset
                val endOffset = expression.endOffset
                val irBuilder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, startOffset, endOffset)
                irBuilder.run {
                    val dispatchReceiver = irCast(expression.dispatchReceiver!!, actualReceiverType, actualReceiverType)
                    val callee = expression.descriptor.original
                    val actualCallee = actualReceiver.unsubstitutedMemberScope.getOverridingOf(callee) ?: callee
                    val actualCalleeSymbol = IrSimpleFunctionSymbolImpl(actualCallee)
                    val superQualifierSymbol = IrClassSymbolImpl(actualReceiver)
                    return when (expression) {
                        is IrCallImpl ->
                            IrCallImpl(
                                    startOffset          = startOffset,
                                    endOffset            = endOffset,
                                    type                 = expression.type,
                                    symbol               = actualCalleeSymbol,
                                    descriptor           = actualCallee,
                                    typeArguments        = expression.typeArguments,
                                    origin               = expression.origin,
                                    superQualifierSymbol = superQualifierSymbol).apply {
                                this.dispatchReceiver    = dispatchReceiver
                                this.extensionReceiver   = expression.extensionReceiver
                                callee.valueParameters.forEach {
                                    this.putValueArgument(it.index, expression.getValueArgument(it))
                                }
                            }

                        is IrGetterCallImpl ->
                            IrGetterCallImpl(
                                    startOffset          = startOffset,
                                    endOffset            = endOffset,
                                    symbol               = actualCalleeSymbol,
                                    descriptor           = actualCallee,
                                    typeArguments        = expression.typeArguments,
                                    origin               = expression.origin,
                                    superQualifierSymbol = superQualifierSymbol).apply {
                                this.dispatchReceiver    = dispatchReceiver
                                this.extensionReceiver   = expression.extensionReceiver
                                callee.valueParameters.forEach {
                                    this.putValueArgument(it.index, expression.getValueArgument(it))
                                }
                            }

                        is IrSetterCallImpl ->
                            IrSetterCallImpl(
                                    startOffset          = startOffset,
                                    endOffset            = endOffset,
                                    symbol               = actualCalleeSymbol,
                                    descriptor           = actualCallee,
                                    typeArguments        = expression.typeArguments,
                                    origin               = expression.origin,
                                    superQualifierSymbol = superQualifierSymbol).apply {
                                this.dispatchReceiver    = dispatchReceiver
                                this.extensionReceiver   = expression.extensionReceiver
                                callee.valueParameters.forEach {
                                    this.putValueArgument(it.index, expression.getValueArgument(it))
                                }
                            }

                        else -> error("Unexpected call type: ${ir2stringWhole(expression)}")
                    }
                }
            }
        })
    }
}