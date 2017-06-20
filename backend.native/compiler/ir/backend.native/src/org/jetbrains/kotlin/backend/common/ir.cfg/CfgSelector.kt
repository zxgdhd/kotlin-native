package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ir.IrReturnableBlockImpl
import org.jetbrains.kotlin.backend.konan.ir.IrSuspendableExpression
import org.jetbrains.kotlin.backend.konan.ir.IrSuspensionPoint
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid

//-----------------------------------------------------------------------------//

internal class CfgSelector(val context: Context): IrElementVisitorVoid {

    fun select() {
        context.irModule!!.accept(this, null)
    }

    //-------------------------------------------------------------------------//

    override fun visitFunction(declaration: IrFunction) {
        super.visitFunction(declaration)
        val statements = (declaration.body as IrBlockBody).statements
        statements.forEach { selectStatement(it) }
    }

    //-------------------------------------------------------------------------//

    private fun selectStatement(statement: IrStatement) {

        when (statement) {
//            is IrGetValue -> return evaluateGetValue(statement)
            else                     -> {
                TODO(ir2string(statement))
            }
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement) {
        super.visitElement(element, null)
    }
}

//-----------------------------------------------------------------------------//

