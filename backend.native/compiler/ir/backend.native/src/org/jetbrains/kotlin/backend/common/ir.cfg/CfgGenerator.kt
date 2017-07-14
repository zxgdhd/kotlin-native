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
    private var block: Block = Block("Entry")
    private lateinit var function: Function

    fun log() = ir.log()

    fun selectFunction(irFunction: IrFunction, selectStatement: (IrStatement) -> Unit) {
        val func = Function(irFunction.descriptor.name.asString())
        irFunction.valueParameters
                .map { Variable(KtType(it.type), it.descriptor.name.asString()) }
                .let(func::addValueParameters)
        ir.newFunction(func)
        function = func
        if (irFunction.body != null && irFunction.body is IrBlockBody) {
            val newBlock = function.newBlock()
            block.addSuccessor(newBlock)
            block = newBlock
            func.enter = block
            (irFunction.body as IrBlockBody).statements.forEach(selectStatement)
        }
    }

    fun selectCall(irCall: IrCall, eval: (IrExpression) -> Operand): Operand {

        val callee = Variable(typePointer, irCall.descriptor.name.asString())
        val uses = listOf(callee) + irCall.getArguments().map { (_, expr) -> eval(expr) }
        val def = Variable(KtType(irCall.type), function.genVariableName())
        block.newInstruction(Opcode.call, def, *uses.toTypedArray())
        return def
    }

    fun selectWhen(expression: IrWhen, eval: (IrExpression) -> Operand): Operand {
        val isUnit = KotlinBuiltIns.isUnit(expression.type)
        val isNothing = KotlinBuiltIns.isNothing(expression.type)
        // TODO: Do we really need commonSuccessor in case of isUnit or isNothing?
        val commonSuccessor = function.newBlock()

        expression.branches.dropLast(1).forEach {
            whenClause(it, commonSuccessor, eval)
        }
        return expression.branches.last().let {
            if (!isUnconditional(it)) {
                val clauseBlock = function.newBlock()
                val cond = eval(it.condition)

                block.condBr(cond, clauseBlock, commonSuccessor)
                block = clauseBlock
            }
            println(it.result)
            val clauseExpr = eval(it.result)
            println(clauseExpr)
            if (!block.isLastInstructionTerminal()) {
                block.br(commonSuccessor)
            }
            block = commonSuccessor
            clauseExpr
        }
    }

    private fun isUnconditional(branch: IrBranch): Boolean =
            branch.condition is IrConst<*>                            // If branch condition is constant.
                    && (branch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    private fun whenClause(irBranch: IrBranch, whenSuccessor: Block, eval: (IrExpression) -> Operand): Operand {

        val nextBlock = function.newBlock()
        val clauseBlock = function.newBlock()

        val cond = eval(irBranch.condition)
        block.condBr(cond, clauseBlock, nextBlock)

        block = clauseBlock
        val clauseExpr = eval(irBranch.result)
        if (!block.isLastInstructionTerminal()) {
            block.br(whenSuccessor)
        }

        block = nextBlock
        return clauseExpr
    }

    fun ret(operand: Operand) {
        block.ret(operand)
    }

    fun selectWhile(irWhileLoop: IrWhileLoop, eval: (IrExpression) -> Operand, selectStatement: (IrStatement) -> Unit): Operand {

        val conditionBlock = function.newBlock()
        val loopbodyBlock = function.newBlock()
        val afterloopBlock = function.newBlock()

        block.addSuccessor(conditionBlock)
        block = conditionBlock
        val condition = eval(irWhileLoop.condition)
        block.condBr(condition, loopbodyBlock, afterloopBlock)

        block = loopbodyBlock
        irWhileLoop.body?.let(selectStatement)
        block.br(conditionBlock)

        block = afterloopBlock
        return Constant(CfgUnit, 0)
    }
}