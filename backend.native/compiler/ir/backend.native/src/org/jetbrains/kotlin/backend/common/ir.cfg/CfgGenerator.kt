package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type

/**
 * Created by jetbrains on 13/07/2017.
 */
class CfgGenerator {
    private val ir = Ir()
    private lateinit var block: Block
    private lateinit var function: Function

    fun log() = ir.log()

    fun function(irFunction: IrFunction, statementFunc: (IrStatement) -> Unit) {
        val func = Function(irFunction.descriptor.name.asString())
        irFunction.valueParameters
                .map { Variable(KtType(it.type), it.descriptor.name.asString()) }
                .let(func::addValueParameters)
        ir.newFunction(func)
        function = func
        if (irFunction.body != null && irFunction.body is IrBlockBody) {
            block = func.newBlock()
            func.enter = block
            (irFunction.body as IrBlockBody).statements.forEach(statementFunc)
        }
    }

    fun call(irCall: IrCall, eval: (IrExpression) -> Operand): Operand {
        val callInstruction = Instruction(Opcode.call)
        callInstruction.addUse(Variable(typePointer, irCall.descriptor.name.asString()))
        irCall.getArguments().forEach { (_, expr) ->
            callInstruction.addUse(eval(expr))
        }
        val def = Variable(KtType(irCall.type), function.genVariableName())
        callInstruction.addDef(def)
        block.addInstruction(callInstruction)
        return def
    }

    fun whenClause(irBranch: IrBranch, eval: (IrExpression) -> Operand): Operand {
        val nextBlock = function.newBlock()
        val clauseBlock = function.newBlock()

        block.condBr(eval(irBranch.condition), clauseBlock, nextBlock)

        block = clauseBlock
        val clauseExpr = eval(irBranch.result)
        block = nextBlock
        return clauseExpr
    }

    fun ret(operand: Operand) {
        block.ret(operand)
    }
}