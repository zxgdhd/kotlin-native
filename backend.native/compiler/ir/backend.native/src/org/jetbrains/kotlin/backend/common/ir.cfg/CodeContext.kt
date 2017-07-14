package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrContinue

/**
 * [V] - operand type
 * [B] - basic block type
 */
interface CodeContext<V, B> {

    /**
     * Generates `return` [value] operation.
     *
     * @param value may be null iff target type is `Unit`.
     */
    fun genReturn(target: CallableDescriptor, value: V?)

    fun genBreak(destination: IrBreak)

    fun genContinue(destination: IrContinue)

    fun genCall(function: V, args: List<V>, resultLifetime: Lifetime): V

    fun genThrow(exception: V)

    /**
     * Declares the variable.
     * @return index of declared variable.
     */
    fun genDeclareVariable(descriptor: VariableDescriptor, value: V?): Int

    /**
     * @return index of variable declared before, or -1 if no such variable has been declared yet.
     */
    fun getDeclaredVariable(descriptor: VariableDescriptor): Int

    /**
     * Generates the code to obtain a value available in this context.
     *
     * @return the requested value
     */
    fun genGetValue(descriptor: ValueDescriptor): V

    /**
     * Returns owning selectFunction scope.
     *
     * @return the requested value
     */
    fun functionScope(): CodeContext<V, B>? = null

    /**
     * Returns owning file scope.
     *
     * @return the requested value if in the file scope or null.
     */
    fun fileScope(): CodeContext<V, B>? = null

    /**
     * Returns owning class scope [ClassScope].
     *
     * @returns the requested value if in the class scope or null.
     */
    fun classScope(): CodeContext<V, B>? = null

    fun addResumePoint(bbLabel: B)
}

interface CfgCodeContext : CodeContext<Operand, Block>

object TopLevelCodeContext : CfgCodeContext {

    private fun unsupported(any: Any? = null): Nothing = throw UnsupportedOperationException(any?.toString() ?: "")

    override fun genReturn(target: CallableDescriptor, value: Operand?) = unsupported(target)

    override fun genBreak(destination: IrBreak) = unsupported(destination)

    override fun genContinue(destination: IrContinue) = unsupported(destination)

    override fun genCall(function: Operand, args: List<Operand>, resultLifetime: Lifetime): Operand = unsupported(function)

    override fun genThrow(exception: Operand) = unsupported(exception)

    override fun genDeclareVariable(descriptor: VariableDescriptor, value: Operand?): Int = unsupported(descriptor)

    override fun getDeclaredVariable(descriptor: VariableDescriptor): Int = unsupported(descriptor)

    override fun genGetValue(descriptor: ValueDescriptor): Operand = unsupported(descriptor)

    override fun addResumePoint(bbLabel: Block) = unsupported(bbLabel)
}

abstract class InnerScope<T, B>(val outerScope: CodeContext<T, B>) : CodeContext<T,B> by outerScope
