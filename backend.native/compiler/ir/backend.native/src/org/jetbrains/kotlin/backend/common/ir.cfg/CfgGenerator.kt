package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.konan.ir.IrReturnableBlockImpl
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type
import sun.reflect.generics.scope.Scope

/**
 * Created by jetbrains on 13/07/2017.
 * TODO: move selection back to selector.
 */
class CfgGenerator {
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

    private inner class FunctionScope(val irFunction: IrFunction): InnerScopeImpl() {
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

    fun selectFunction(irFunction: IrFunction, selectStatement: (IrStatement) -> Unit) {
        useScope(FunctionScope(irFunction)) {
            irFunction.body?.let {
                useBlock(func.enter) {
                    when (it) {
                        is IrExpressionBody -> selectStatement(it.expression)
                        is IrBlockBody -> it.statements.forEach(selectStatement)
                        else -> throw TODO("unsupported function body type: $it")
                    }
                }
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun selectCall(irCall: IrCall, eval: (IrExpression) -> Operand): Operand
            = useBlock(currentBlock) {
                val callee = Variable(typePointer, irCall.descriptor.name.asString())
                val uses = listOf(callee) + irCall.getArguments().map { (_, expr) -> eval(expr) }
                val def = Variable(KtType(irCall.type), currentFunction.genVariableName())
                instruction(Opcode.call, def, *uses.toTypedArray())
                def
            }

    //-------------------------------------------------------------------------//

    fun selectWhen(expression: IrWhen, eval: (IrExpression) -> Operand): Operand
            = useScope(WhenScope(expression)) {
        val resultVar = Variable(KtType(expression.type), "TODO")
        expression.branches.forEach {
            val nextBlock = if (it == expression.branches.last()) exitBlock else currentFunction.newBlock()
            selectWhenClause(it, nextBlock, exitBlock, resultVar, eval)
        }
        resultVar
    }

    //-------------------------------------------------------------------------//

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, variable: Variable, eval: (IrExpression) -> Operand)
            = useScope(WhenClauseScope(irBranch, nextBlock)) {
                if (!isUnconditional()) {
                    useBlock(currentBlock) {
                        condBr(eval(irBranch.condition), clauseBlock, nextBlock)
                    }
                }

                useBlock(clauseBlock) {
                    val clauseExpr = eval(irBranch.result)
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

    fun selectWhile(irWhileLoop: IrWhileLoop, eval: (IrExpression) -> Operand, selectStatement: (IrStatement) -> Unit): Operand
            = useScope(LoopScope(irWhileLoop)) {
                useBlock(loopCheck) {
                    condBr(eval(irWhileLoop.condition), loopBody, loopExit)
                }
                useBlock(loopBody) {
                    irWhileLoop.body?.let(selectStatement)
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

    fun selectSetVariable(irSetVariable: IrSetVariable, eval: (IrExpression) -> Operand): Operand {
        val operand = eval(irSetVariable.value)
        variableMap[irSetVariable.descriptor] = operand
        val variable = Variable(KtType(irSetVariable.value.type), irSetVariable.descriptor.name.asString())
        useBlock(currentBlock) {
            mov(variable, operand)
        }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectVariable(irVariable: IrVariable, eval: (IrExpression) -> Operand): Unit {
        // TODO: add variables without initializers
        irVariable.initializer?.let {
            val operand = eval(it)
            val variable = Variable(KtType(irVariable.descriptor.type), irVariable.descriptor.name.asString())
            variableMap[irVariable.descriptor] = operand
            useBlock(currentBlock) {
                mov(variable, operand)
            }
        }

    }

    //-------------------------------------------------------------------------//

    fun selectVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
            = variableMap[irVariableSymbol.descriptor] ?: Null

    //-------------------------------------------------------------------------//

    fun selectValueSymbol(irValueSymbol: IrValueSymbol): Operand
            = variableMap[irValueSymbol.descriptor] ?: Null

    //-------------------------------------------------------------------------//

}