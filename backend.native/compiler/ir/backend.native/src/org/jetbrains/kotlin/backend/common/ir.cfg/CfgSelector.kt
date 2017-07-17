package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    val generate = CfgGenerator()

    fun select() {
        context.irModule!!.accept(this, null)
        context.log { generate.log(); "" }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        generate.selectFunction(declaration, this::selectStatement)
        super.visitFunction(declaration)
    }


    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement) {
        when (statement) {
            is IrExpression -> evaluateExpression(statement)
            is IrVariable -> generateVariable(statement)
        }
    }

    //-------------------------------------------------------------------------//

    private fun generateVariable(statement: IrVariable) = generate.selectVariable(statement, this::evaluateExpression)

    //-------------------------------------------------------------------------//

    private fun evaluateExpression(expression: IrExpression): Operand = when (expression) {
        is IrCall -> evaluateCall(expression)
        is IrContainerExpression -> evaluateContainerExpression(expression)
        is IrConst<*> -> evaluateConst(expression)
        is IrWhileLoop -> evaluateWhileLoop(expression)
        is IrBreak -> evaluateBreak(expression)
        is IrContinue -> generate.selectContinue(expression)
        is IrReturn -> evaluateReturn(expression)
        is IrWhen -> evaluateWhen(expression)
        is IrSetVariable -> evaluateSetVariable(expression)
//        is IrVariableSymbol -> evaluateVariableSymbol(expression)
//        is IrValueSymbol -> evaluateValueSymbol(expression)
        else -> {
            Constant(typeString, "unsupported")
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSetVariable(expression: IrSetVariable): Operand {
        return generate.selectSetVariable(expression, this::evaluateExpression)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateBreak(expression: IrBreak): Operand
            = generate.selectBreak(expression)

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

    private fun evaluateVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand = TODO()

    //-------------------------------------------------------------------------//

    private fun evaluateValueSymbol(irValueSymbol: IrValueSymbol): Operand = TODO()

    //-------------------------------------------------------------------------//

    private fun evaluateWhen(expression: IrWhen): Operand =
            generate.selectWhen(expression, this::evaluateExpression)

    //-------------------------------------------------------------------------//

    private fun evaluateReturn(irReturn: IrReturn): Operand {

        val target = irReturn.returnTarget
        val evaluated = evaluateExpression(irReturn.value)

        generate.ret(evaluated)
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
            = generate.selectWhile(irWhileLoop, this::evaluateExpression, this::selectStatement)

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
            = generate.selectCall(irCall, this::evaluateExpression)

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement)
            = element.acceptChildren(this, null)
}

//-----------------------------------------------------------------------------//

