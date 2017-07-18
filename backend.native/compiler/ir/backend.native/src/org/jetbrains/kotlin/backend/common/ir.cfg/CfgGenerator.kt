package org.jetbrains.kotlin.backend.common.ir.cfg

import org.jetbrains.kotlin.backend.konan.ir.IrReturnableBlockImpl
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.type
import sun.reflect.generics.scope.Scope

/**
 * Created by jetbrains on 13/07/2017.
 * TODO: move selection back to selector.
 */
class CfgGenerator {


}