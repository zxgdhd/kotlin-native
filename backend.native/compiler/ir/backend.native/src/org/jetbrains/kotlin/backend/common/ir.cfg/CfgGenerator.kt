package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type

/**
 * Created by jetbrains on 13/07/2017.
 */
class CfgGenerator {
    private val ir = Ir()
    private var currentBlock: Block = Block("Entry")
    private lateinit var currentFunction: Function

    fun log() = ir.log()


    private var currentContext: CfgCodeContext = TopLevelCodeContext

    private abstract inner class InnerScope(val outerContext: CfgCodeContext) : CfgCodeContext by outerContext

    private abstract inner class InnerScopeImpl : InnerScope(currentContext)

    private interface ScopeLifecycle {
        fun onEnter() {}

        fun onLeave() {}
    }

    private inner class LoopScope(val loop: IrLoop): InnerScopeImpl(), ScopeLifecycle {
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

    private inner class WhenClauseScope(val irBranch: IrBranch, val nextBlock: Block): InnerScopeImpl(), ScopeLifecycle {
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

    private inner class WhenScope(irWhen: IrWhen): InnerScopeImpl(), ScopeLifecycle {
        val isUnit = KotlinBuiltIns.isUnit(irWhen.type)
        val isNothing = KotlinBuiltIns.isNothing(irWhen.type)
        // TODO: Do we really need exitBlock in case of isUnit or isNothing?
        val exitBlock = currentFunction.newBlock()

        override fun onLeave() {
            currentBlock = exitBlock
        }

    }

    private inline fun <C, R> useScope(context: C, block: C.() -> R): R
            where C : CfgCodeContext, C : ScopeLifecycle {
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

    fun <T> useBlock(block: Block, body: Block.() -> T): T {
        //val oldBlock = currentBlock
        currentBlock = block
        return currentBlock.body()
//        try {
//            return currentBlock.body()
//        } finally {
//            currentBlock = oldBlock
//        }
    }

    fun selectFunction(irFunction: IrFunction, selectStatement: (IrStatement) -> Unit) {
        val func = Function(irFunction.descriptor.name.asString())
        irFunction.valueParameters
                .map { Variable(KtType(it.type), it.descriptor.name.asString()) }
                .let(func::addValueParameters)
        ir.newFunction(func)
        currentFunction = func
        if (irFunction.body != null && irFunction.body is IrBlockBody) {
            val newBlock = currentFunction.newBlock()
            currentBlock.addSuccessor(newBlock)
            currentBlock = newBlock
            func.enter = currentBlock
            (irFunction.body as IrBlockBody).statements.forEach(selectStatement)
        }
    }

    fun selectCall(irCall: IrCall, eval: (IrExpression) -> Operand): Operand
            = useBlock(currentBlock) {
                val callee = Variable(typePointer, irCall.descriptor.name.asString())
                val uses = listOf(callee) + irCall.getArguments().map { (_, expr) -> eval(expr) }
                val def = Variable(KtType(irCall.type), currentFunction.genVariableName())
                instruction(Opcode.call, def, *uses.toTypedArray())
                def
            }

    fun selectWhen(expression: IrWhen, eval: (IrExpression) -> Operand): Operand
            = useScope(WhenScope(expression)) {
                expression.branches.forEach {
                    val nextBlock = if (it == expression.branches.last()) exitBlock else currentFunction.newBlock()
                    selectWhenClause(it, nextBlock, exitBlock, eval)
                }
                // TODO: use actual data
                CfgUnit
            }

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, eval: (IrExpression) -> Operand): Operand
            = useScope(WhenClauseScope(irBranch, nextBlock)) {
                if (!isUnconditional()) {
                    useBlock(currentBlock) {
                        condBr(eval(irBranch.condition), clauseBlock, nextBlock)
                    }
                }

                useBlock(clauseBlock) {
                    val clauseExpr = eval(irBranch.result)
                    if (!isLastInstructionTerminal()) {
                        br(exitBlock)
                        CfgUnit
                    } else {
                        clauseExpr
                    }
                }
            }

    fun ret(operand: Operand) = useBlock(currentBlock) {
        ret(operand)
    }

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

    fun selectBreak(expression: IrBreak): Operand {
        currentContext.genBreak(expression)
        return CfgUnit
    }

    fun selectContinue(expression: IrContinue): Operand {
        currentContext.genContinue(expression)
        return CfgUnit
    }
}