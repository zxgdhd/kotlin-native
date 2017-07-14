package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    val generate = CfgGenerator()

    fun select() {
        context.irModule!!.accept(this, null)
        context.log { generate.log(); "Complete" }
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        generate.selectFunction(declaration, this::selectStatement)
        super.visitFunction(declaration)
    }


    //-------------------------------------------------------------------------//

    // TODO: maybe selectFunction arg is not needed
    private fun selectStatement(statement: IrStatement) {
        when (statement) {
            is IrExpression -> evaluateExpression(statement)
            else -> {
//                println("$statement is not supported yet")
            }
        }
    }

    private fun evaluateExpression(expression: IrExpression): Operand {
        return when (expression) {
            is IrCall -> evaluateCall(expression)
            is IrContainerExpression -> evaluateContainerExpression(expression)
            is IrConst<*> -> evaluateConst(expression)
            is IrWhileLoop -> evaluateWhileLoop(expression)
            is IrReturn -> evaluateReturn(expression)
            is IrWhen -> evaluateWhen(expression)
    //        is IrVariableSymbol -> evaluateVariableSymbol(expression)
    //        is IrValueSymbol -> evaluateValueSymbol(expression)
            else -> {
                Constant(typeString, "unsupported")
            }
        }
    }

    private fun evaluateContainerExpression(expression: IrContainerExpression): Operand {

        expression.statements.dropLast(1).forEach(this::selectStatement)
        expression.statements.lastOrNull()?.let {
            if (it is IrExpression) {
                return evaluateExpression(it)
            } else {
                selectStatement(it)
            }
        }
        return Constant(CfgUnit, 0)
    }

    private fun evaluateVariableSymbol(irVariableSymbol: IrVariableSymbol): Operand = TODO()

    private fun evaluateValueSymbol(irValueSymbol: IrValueSymbol): Operand = TODO()

    private fun evaluateWhen(expression: IrWhen): Operand =
            generate.selectWhen(expression, this::evaluateExpression)

    private fun evaluateReturn(irReturn: IrReturn): Operand {

        val target = irReturn.returnTarget
        val evaluated = evaluateExpression(irReturn.value)

        generate.ret(evaluated)
        // TODO: Introduce CfgUnit type
        return if (target.returnsUnit()) {
            Null
        } else {
            evaluated
        }

    }

    private fun CallableDescriptor.returnsUnit()
            = returnType == context.builtIns.unitType && !isSuspend


    private fun evaluateWhileLoop(irWhileLoop: IrWhileLoop): Operand =
            generate.selectWhile(irWhileLoop, this::evaluateExpression, this::selectStatement)

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

