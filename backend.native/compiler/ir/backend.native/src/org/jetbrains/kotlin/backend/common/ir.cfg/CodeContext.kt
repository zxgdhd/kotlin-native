package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrContinue

interface CodeContext {
    fun genReturn(target: CallableDescriptor, value: Operand?)                    // Generates `return` [value] operation. value may be null iff target type is `Unit`.
    fun genBreak(destination: IrBreak)
    fun genContinue(destination: IrContinue)
    fun genCall(function: Operand, args: List<Operand>, resultLifetime: Lifetime): Operand
    fun genThrow(exception: Operand)
    fun genGetValue(descriptor: ValueDescriptor): Operand                         // Generates the code to obtain a value available in this context.
    fun onEnter()
    fun onLeave()

}

object TopLevelCodeContext : CodeContext {

    private fun unsupported(any: Any? = null): Nothing = throw UnsupportedOperationException(any?.toString() ?: "")
    override fun genReturn(target: CallableDescriptor, value: Operand?) = unsupported(target)
    override fun genBreak(destination: IrBreak) = unsupported(destination)
    override fun genContinue(destination: IrContinue) = unsupported(destination)
    override fun genCall(function: Operand, args: List<Operand>, resultLifetime: Lifetime): Operand = unsupported(function)
    override fun genThrow(exception: Operand) = unsupported(exception)
    override fun genGetValue(descriptor: ValueDescriptor): Operand = unsupported(descriptor)
    override fun onEnter() {}
    override fun onLeave() {}
}
