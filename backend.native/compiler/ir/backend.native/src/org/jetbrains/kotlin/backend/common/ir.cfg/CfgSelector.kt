package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ir.IrReturnableBlockImpl
import org.jetbrains.kotlin.backend.konan.ir.IrSuspendableExpression
import org.jetbrains.kotlin.backend.konan.ir.IrSuspensionPoint
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    val generate = CfgGenerator()

    fun select() {
        context.irModule!!.accept(this, null)
        context.log { log(); "" }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        selectFunction(declaration)
        super.visitFunction(declaration)
    }


    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement) {
        when (statement) {
            is IrExpression -> evaluateExpression(statement)
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateExpression(expression: IrStatement): Operand = when (expression) {
        is IrCall -> evaluateCall(expression)
        is IrContainerExpression -> evaluateContainerExpression(expression)
        is IrConst<*> -> evaluateConst(expression)
        is IrWhileLoop -> evaluateWhileLoop(expression)
        is IrBreak -> evaluateBreak(expression)
        is IrContinue -> selectContinue(expression)
        is IrReturn -> evaluateReturn(expression)
        is IrWhen -> evaluateWhen(expression)
        is IrSetVariable -> evaluateSetVariable(expression)
        is IrVariableSymbol -> evaluateVariableSymbol(expression)
        is IrValueSymbol -> evaluateValueSymbol(expression)
        is IrVariable -> selectVariable(expression)
        else -> {
            Constant(typeString, "unsupported")
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSetVariable(expression: IrSetVariable): Operand {
        return selectSetVariable(expression)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateBreak(expression: IrBreak): Operand
            = selectBreak(expression)

    //-------------------------------------------------------------------------//

    private fun evaluateContainerExpression(expression: IrContainerExpression): Operand {

        expression.statements.dropLast(1).forEach(this::selectStatement)
        expression.statements.lastOrNull()?.let {
            if (it is IrExpression) {
                return evaluateExpression(it)
            } else {
                selectStatement(it)
            }
        }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun evaluateVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
            = selectVariableSymbol(irVariableSymbol)

    //-------------------------------------------------------------------------//

    private fun evaluateValueSymbol(irValueSymbol: IrValueSymbol): Operand
            = selectValueSymbol(irValueSymbol)

    //-------------------------------------------------------------------------//

    private fun evaluateWhen(expression: IrWhen): Operand =
            selectWhen(expression)

    //-------------------------------------------------------------------------//

    private fun evaluateReturn(irReturn: IrReturn): Operand {

        val target = irReturn.returnTarget
        val evaluated = evaluateExpression(irReturn.value)

        ret(evaluated)
        return if (target.returnsUnit()) {
            CfgUnit
        } else {
            evaluated
        }

    }

    //-------------------------------------------------------------------------//

    private fun CallableDescriptor.returnsUnit()
            = returnType == context.builtIns.unitType && !isSuspend


    //-------------------------------------------------------------------------//

    private fun evaluateWhileLoop(irWhileLoop: IrWhileLoop): Operand
            = selectWhile(irWhileLoop)

    //-------------------------------------------------------------------------//

    fun evaluateConst(const: IrConst<*>): Constant = when(const.kind) {
        IrConstKind.Null -> Null
        IrConstKind.Boolean -> Constant(typeBoolean, const.value as Boolean)
        IrConstKind.Char -> Constant(typeChar, const.value as Char)
        IrConstKind.Byte -> Constant(typeByte, const.value as Byte)
        IrConstKind.Short -> Constant(typeShort, const.value as Short)
        IrConstKind.Int -> Constant(typeInt, const.value as Int)
        IrConstKind.Long -> Constant(typeLong, const.value as Long)
        IrConstKind.String -> Constant(typeString, const.value as String)
        IrConstKind.Float -> Constant(typeFloat, const.value as Float)
        IrConstKind.Double -> Constant(typeDouble, const.value as Double)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateCall(irCall: IrCall): Operand
            = selectCall(irCall)

    //-------------------------------------------------------------------------//

    fun selectFunction(irFunction: IrFunction) {
        useScope(FunctionScope(irFunction)) {
            irFunction.body?.let {
                useBlock(func.enter) {
                    when (it) {
                        is IrExpressionBody -> evaluateExpression(it.expression)
                        is IrBlockBody -> it.statements.forEach {
                            evaluateExpression(it)
                        }
                        else -> throw TODO("unsupported function body type: $it")
                    }
                }
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun selectCall(irCall: IrCall): Operand
        = useBlock(currentBlock) {
        val callee = Variable(typePointer, irCall.descriptor.name.asString())
        val uses = listOf(callee) + irCall.getArguments().map { (_, expr) -> evaluateExpression(expr) }
        val def = Variable(KtType(irCall.type), currentFunction.genVariableName())
        instruction(Opcode.call, def, *uses.toTypedArray())
        def
    }

    //-------------------------------------------------------------------------//

    fun selectWhen(expression: IrWhen): Operand
        = useScope(WhenScope(expression)) {
        val resultVar = Variable(KtType(expression.type), currentFunction.genVariableName())
        expression.branches.forEach {
            val nextBlock = if (it == expression.branches.last()) exitBlock else currentFunction.newBlock()
            selectWhenClause(it, nextBlock, exitBlock, resultVar)
        }
        resultVar
    }

    //-------------------------------------------------------------------------//

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, variable: Variable)
        = useScope(WhenClauseScope(irBranch, nextBlock)) {
        if (!isUnconditional()) {
            useBlock(currentBlock) {
                condBr(evaluateExpression(irBranch.condition), clauseBlock, nextBlock)
            }
        }

        useBlock(clauseBlock) {
            val clauseExpr = evaluateExpression(irBranch.result)
            mov(variable, clauseExpr)
            if (!isLastInstructionTerminal()) {
                br(exitBlock)
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun ret(operand: Operand) = useBlock(currentBlock) {
        ret(operand)
    }

    /*
     TODO: rewrite as
     if (cond) br block, next
     block:
        ...
        if (cond) block, next
    next:
      */

    //-------------------------------------------------------------------------//

    fun selectWhile(irWhileLoop: IrWhileLoop): Operand
        = useScope(LoopScope(irWhileLoop)) {
        useBlock(loopCheck) {
            condBr(evaluateExpression(irWhileLoop.condition), loopBody, loopExit)
        }
        useBlock(loopBody) {
            irWhileLoop.body?.let { evaluateExpression(it) }

        }
        // Adding break after all statements
        useBlock(currentBlock) {
            if (!isLastInstructionTerminal())
                br(loopCheck)
        }
        CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectBreak(expression: IrBreak): Operand {
        currentContext.genBreak(expression)
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectContinue(expression: IrContinue): Operand {
        currentContext.genContinue(expression)
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectSetVariable(irSetVariable: IrSetVariable): Operand {
        val operand = evaluateExpression(irSetVariable.value)
        variableMap[irSetVariable.descriptor] = operand
        val variable = Variable(KtType(irSetVariable.value.type), irSetVariable.descriptor.name.asString())
        useBlock(currentBlock) {
            mov(variable, operand)
        }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectVariable(irVariable: IrVariable): Operand {
        val operand = irVariable.initializer?.let { evaluateExpression(it) } ?: Null
        val variable = Variable(KtType(irVariable.descriptor.type), irVariable.descriptor.name.asString())
        variableMap[irVariable.descriptor] = operand
        useBlock(currentBlock) {
            mov(variable, operand)
        }
        return variable
    }

    //-------------------------------------------------------------------------//

    fun selectVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
        = variableMap[irVariableSymbol.descriptor] ?: Null

    //-------------------------------------------------------------------------//

    fun selectValueSymbol(irValueSymbol: IrValueSymbol): Operand
        = variableMap[irValueSymbol.descriptor] ?: Null


    private val ir = Ir()
    private var currentBlock: Block = Block("Entry")
    private lateinit var currentFunction: Function

    fun log() = ir.log()
    val variableMap = mutableMapOf<ValueDescriptor, Operand>()

    //-------------------------------------------------------------------------//

    private var currentContext: CodeContext = TopLevelCodeContext

    private abstract inner class InnerScope(val outerContext: CodeContext) : CodeContext by outerContext

    private abstract inner class InnerScopeImpl : InnerScope(currentContext)

    //-------------------------------------------------------------------------//

    private inner class LoopScope(val loop: IrLoop): InnerScopeImpl() {
        val loopCheck = currentFunction.newBlock(tag="loop_check")
        val loopExit = currentFunction.newBlock(tag="loop_exit")
        val loopBody = currentFunction.newBlock(tag="loop_body")

        override fun onEnter() {
            currentBlock.addSuccessor(loopCheck)
        }

        override fun onLeave() {
            currentBlock = loopExit
        }

        override fun genBreak(destination: IrBreak) {
            if (destination.loop == loop) {
                currentBlock.br(loopExit)
            } else {
                super.genBreak(destination) // breaking outer loop
            }
        }

        override fun genContinue(destination: IrContinue) {
            if (destination.loop == loop) {
                currentBlock.br(loopCheck)
            } else {
                super.genContinue(destination)
            }
        }
    }

    //-------------------------------------------------------------------------//

    private inner class FunctionScope(irFunction: IrFunction): InnerScopeImpl() {
        val func = Function(irFunction.descriptor.name.asString())
        init {
            irFunction.valueParameters
                .map { Variable(KtType(it.type), it.descriptor.name.asString()) }
                .let(func::addValueParameters)
            ir.newFunction(func)
        }

        override fun onEnter() {
            currentFunction = func
        }
    }

    //-------------------------------------------------------------------------//

    private inner class WhenClauseScope(val irBranch: IrBranch, val nextBlock: Block): InnerScopeImpl() {
        var clauseBlock = currentFunction.newBlock()

        fun isUnconditional(): Boolean =
            irBranch.condition is IrConst<*>                            // If branch condition is constant.
                && (irBranch.condition as IrConst<*>).value as Boolean  // If condition is "true"

        override fun onEnter() {
            if (isUnconditional()) {
                clauseBlock = currentBlock
            }
        }

        override fun onLeave() {
            currentBlock = nextBlock
        }
    }

    //-------------------------------------------------------------------------//

    private inner class WhenScope(irWhen: IrWhen): InnerScopeImpl() {
        val isUnit = KotlinBuiltIns.isUnit(irWhen.type)
        val isNothing = KotlinBuiltIns.isNothing(irWhen.type)
        // TODO: Do we really need exitBlock in case of isUnit or isNothing?
        val exitBlock = currentFunction.newBlock()

        override fun onLeave() {
            currentBlock = exitBlock
        }

    }

    //-------------------------------------------------------------------------//

    private inline fun <C: CodeContext, R> useScope(context: C, block: C.() -> R): R {
        val prevContext = currentContext
        currentContext = context
        context.onEnter()
        try {
            return context.block()
        } finally {
            context.onLeave()
            currentContext = prevContext
        }
    }

    //-------------------------------------------------------------------------//

    fun <T> useBlock(block: Block, body: Block.() -> T): T {
        currentBlock = block
        return currentBlock.body()
    }

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
            = element.acceptChildren(this, null)
}

