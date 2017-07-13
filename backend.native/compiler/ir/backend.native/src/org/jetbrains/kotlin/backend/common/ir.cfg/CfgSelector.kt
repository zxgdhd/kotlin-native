package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    /**
     * Encapsulates result of expression and block which should be used next
     */

    val gen = CfgGenerator()

    fun select() {
        context.irModule!!.accept(this, null)
        gen.log()
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        gen.function(declaration, this::selectStatement)
        super.visitFunction(declaration)
    }


    //-------------------------------------------------------------------------//

    // TODO: maybe function arg is not needed
    private fun selectStatement(statement: IrStatement) {
        when (statement) {
            is IrExpression -> evaluateExpression(statement)
            else -> {
//                println("$statement is not supported yet")
            }
        }
    }

    private fun evaluateExpression(expression: IrExpression): Operand = when (expression) {
        is IrCall -> evaluateCall(expression)
        is IrConst<*> -> evaluateConst(expression)
//        is IrWhileLoop -> evaluateWhileLoop(expression, func)
        is IrReturn -> evaluateReturn(expression)
        is IrWhen -> evaluateWhen(expression)
        else -> {
            Constant(typeString, "unsupported")
        }
    }

    private fun evaluateWhen(expression: IrWhen): Operand {
        val isUnit = KotlinBuiltIns.isUnit(expression.type)
        val isNothing = KotlinBuiltIns.isNothing(expression.type)
        val coverAllCases = isUnconditional(expression.branches.last())


        expression.branches.dropLast(1).forEach {
            gen.whenClause(it, this::evaluateExpression)
        }
        return expression.branches.last().let {
            if (!coverAllCases) {
                gen.whenClause(it, this::evaluateExpression)
            } else {
                evaluateExpression(it.result)
            }
        }
    }

    // TODO: maybe it should be refactored to IrBranch or as extension method?
    private fun isUnconditional(branch: IrBranch): Boolean =
            branch.condition is IrConst<*>                            // If branch condition is constant.
                    && (branch.condition as IrConst<*>).value as Boolean  // If condition is "true"

    private fun evaluateReturn(irReturn: IrReturn): Operand {

        val target = irReturn.returnTarget
        val evaluated = evaluateExpression(irReturn.value)

        gen.ret(evaluated)
        // TODO: Introduce Unit type
        return if (target.returnsUnit()) {
            Null
        } else {
            evaluated
        }

    }

    private fun CallableDescriptor.returnsUnit() = returnType == context.builtIns.unitType && !isSuspend


    private fun evaluateWhileLoop(irWhileLoop: IrWhileLoop): Operand {
        TODO()
    }

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

    private fun evaluateCall(irCall: IrCall): Operand = gen.call(irCall, this::evaluateExpression)

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement) {
        element.acceptChildren(this, null)
    }
}

//-----------------------------------------------------------------------------//

