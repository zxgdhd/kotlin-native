package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
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
import org.jetbrains.kotlin.types.typeUtil.isUnit

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {


    private val ir = Ir()
    private var currentBlock: Block = Block("Entry")
    private lateinit var currentFunction: Function

    val variableMap = mutableMapOf<ValueDescriptor, Operand>()

    private data class LoopLabels(val loop: IrLoop, val check: Block, val exit: Block)

    private val loopStack = mutableListOf<LoopLabels>()

    //-------------------------------------------------------------------------//

    fun select() {
        context.irModule!!.accept(this, null)
        context.log { ir.log(); "" }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        selectFunction(declaration)
        super.visitFunction(declaration)
    }

    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement): Operand = when (statement) {
        is IrCall -> selectCall(statement)
        is IrContainerExpression -> selectContainerExpression(statement)
        is IrConst<*> -> selectConst(statement)
        is IrWhileLoop -> selectWhile(statement)
        is IrBreak -> selectBreak(statement)
        is IrContinue -> selectContinue(statement)
        is IrReturn -> selectReturn(statement)
        is IrWhen -> selectWhen(statement)
        is IrSetVariable -> selectSetVariable(statement)
        is IrVariableSymbol -> selectVariableSymbol(statement)
        is IrValueSymbol -> selectValueSymbol(statement)
        is IrVariable -> selectVariable(statement)
        is IrGetValue -> selectGetValue(statement)
        else -> Constant(typeString, statement.toString())
    }

    //-------------------------------------------------------------------------//

    private fun selectGetValue(getValue: IrGetValue): Operand
            = variableMap[getValue.descriptor] ?: Null

    //-------------------------------------------------------------------------//

    private fun selectContainerExpression(expression: IrContainerExpression): Operand {

        expression.statements.dropLast(1).forEach {
            selectStatement(it)
        }
        return expression.statements.lastOrNull()
                ?.let { selectStatement(it) } ?: CfgUnit
    }

    //-------------------------------------------------------------------------//

    private fun selectReturn(irReturn: IrReturn): Operand {

        val target = irReturn.returnTarget
        val evaluated = selectStatement(irReturn.value)
        currentBlock.ret(evaluated)
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

    private fun selectConst(const: IrConst<*>): Constant = when(const.kind) {
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

    fun selectFunction(irFunction: IrFunction) {
        currentFunction = Function(irFunction.descriptor.name.asString())
        ir.newFunction(currentFunction)

        irFunction.valueParameters
                .map { Variable(KtType(it.type), it.descriptor.name.asString()) }
                .let(currentFunction::addValueParameters)
        irFunction.body?.let {
            currentBlock = currentFunction.enter
            when (it) {
                is IrExpressionBody -> selectStatement(it.expression)
                is IrBlockBody -> it.statements.forEach {
                    selectStatement(it)
                }
                else -> throw TODO("unsupported function body type: $it")
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun selectCall(irCall: IrCall): Operand {
        val callee = Variable(typePointer, irCall.descriptor.name.asString())
        val uses = listOf(callee) + irCall.getArguments().map { (_, expr) -> selectStatement(expr) }

        if (irCall.type.isUnit()) {
            currentBlock.instruction(Opcode.call, *uses.toTypedArray())
            return CfgUnit
        } else {
            val def = Variable(KtType(irCall.type), currentFunction.genVariableName())
            currentBlock.instruction(Opcode.call, def, *uses.toTypedArray())
            return def
        }
    }

    //-------------------------------------------------------------------------//

    fun selectWhen(expression: IrWhen): Operand {
        val resultVar = if (expression.type == context.builtIns.unitType) {
            null
        } else {
            Variable(KtType(expression.type), currentFunction.genVariableName())
        }
        val exitBlock = currentFunction.newBlock()

        expression.branches.forEach {
            val nextBlock = if (it == expression.branches.last()) exitBlock else currentFunction.newBlock()
            selectWhenClause(it, nextBlock, exitBlock, resultVar)
        }

        currentBlock = exitBlock
        return resultVar ?: CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun isUnconditional(irBranch: IrBranch): Boolean =
            irBranch.condition is IrConst<*>                            // If branch condition is constant.
                    && (irBranch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    //-------------------------------------------------------------------------//

    private fun selectWhenClause(irBranch: IrBranch, nextBlock: Block, exitBlock: Block, variable: Variable?) {

        currentBlock = if (isUnconditional(irBranch)) {
            currentBlock
        } else {
            currentFunction.newBlock().also {
                currentBlock.condBr(selectStatement(irBranch.condition), it, nextBlock)
            }
        }

        val clauseExpr = selectStatement(irBranch.result)
        with(currentBlock) {
            if (!isLastInstructionTerminal()) {
                variable?.let { mov(it, clauseExpr) }
                br(exitBlock)
            }
        }
        currentBlock = nextBlock
    }

    //-------------------------------------------------------------------------//

    fun selectWhile(irWhileLoop: IrWhileLoop): Operand {

        val loopCheck = currentFunction.newBlock("loop_check")
        val loopBody = currentFunction.newBlock("loop_body")
        val loopExit = currentFunction.newBlock("loop_exit")

        loopStack.push(LoopLabels(irWhileLoop, loopCheck, loopExit))

        currentBlock.addSuccessor(loopCheck)
        currentBlock = loopCheck
        currentBlock.condBr(selectStatement(irWhileLoop.condition), loopBody, loopExit)

        currentBlock = loopBody
        irWhileLoop.body?.let { selectStatement(it) }
        if (!currentBlock.isLastInstructionTerminal())
            currentBlock.br(loopCheck)

        loopStack.pop()
        currentBlock = loopExit
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectBreak(expression: IrBreak): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
                .let { (_, _, exit) -> currentBlock.br(exit) }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectContinue(expression: IrContinue): Operand {
        loopStack.reversed().first { (loop, _, _) -> loop == expression.loop }
                .let { (_, check, _) -> currentBlock.br(check) }
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectSetVariable(irSetVariable: IrSetVariable): Operand {
        val operand = selectStatement(irSetVariable.value)
        variableMap[irSetVariable.descriptor] = operand
        val variable = Variable(KtType(irSetVariable.value.type), irSetVariable.descriptor.name.asString())
        currentBlock.mov(variable, operand)
        return CfgUnit
    }

    //-------------------------------------------------------------------------//

    fun selectVariable(irVariable: IrVariable): Operand {
        val operand = irVariable.initializer?.let { selectStatement(it) } ?: Null
        val variable = Variable(KtType(irVariable.descriptor.type), irVariable.descriptor.name.asString())
        variableMap[irVariable.descriptor] = operand
        currentBlock.mov(variable, operand)
        return variable
    }

    //-------------------------------------------------------------------------//

    fun selectVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand
        = variableMap[irVariableSymbol.descriptor] ?: Null

    //-------------------------------------------------------------------------//

    fun selectValueSymbol(irValueSymbol: IrValueSymbol): Operand
        = variableMap[irValueSymbol.descriptor] ?: Null

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
            = element.acceptChildren(this, null)
}

