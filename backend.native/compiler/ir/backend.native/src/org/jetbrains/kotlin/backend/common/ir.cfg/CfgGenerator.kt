package org.jetbrains.kotlin.backend.common.ir.cfg


import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ValueType
import org.jetbrains.kotlin.backend.konan.correspondingValueType
import org.jetbrains.kotlin.backend.konan.isValueType
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.isPrimitiveNumberType
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.util.OperatorNameConventions

//-----------------------------------------------------------------------------//

internal open class CfgGenerator() {

    protected var currentFunction = Function("Outer")
    protected var currentBlock    = currentFunction.enter

    //-------------------------------------------------------------------------//

    protected fun newVariable(type: Type, name: String = currentFunction.genVariableName())
        = Variable(type, name)

    //-------------------------------------------------------------------------//

    protected fun newBlock(name: String = "block") = currentFunction.newBlock(name)

    //-------------------------------------------------------------------------//

    protected fun Block.inst(opcode: Opcode, defType: Type, vararg uses: Operand): Operand {
        if (defType == TypeUnit) {
            currentBlock.instruction(opcode, *uses)
            return CfgUnit
        } else {
            val def = newVariable(defType)
            currentBlock.instruction(opcode, def, *uses)
            return def
        }
    }

    //-------------------------------------------------------------------------//

    protected fun Block.inst(opcode: Opcode, vararg uses: Operand) {
        instruction(opcode, *uses)
    }

    //-------------------------------------------------------------------------//

    protected fun Block.br(target: Block) {
        addSuccessor(target)
        inst(Opcode.br, Constant(TypeBlock, target))
    }

    //-------------------------------------------------------------------------//

    protected fun Block.condbr(condition: Operand, targetTrue: Block, targetFalse: Block) {
        addSuccessor(targetTrue)
        addSuccessor(targetFalse)
        inst(Opcode.condbr, condition, Constant(TypeBlock, targetTrue), Constant(TypeBlock, targetTrue))
    }

    //-------------------------------------------------------------------------//

    protected fun Block.invoke(retType: Type, callee: Operand, vararg args: Operand): Operand {
        return inst(Opcode.invoke, retType, callee, *args)
    }

    //-------------------------------------------------------------------------//

    protected fun Block.instance_of (value: Operand, type: Type): Operand {
        val klass = Constant(type, 0)                                                       // Operand representing the Class.
        return inst(Opcode.instance_of, TypeBoolean, value, klass)                          // TODO implement instanceof
    }

    //-------------------------------------------------------------------------//

    protected fun Block.not_instance_of (value: Operand, type: Type): Operand {
        val klass = Constant(type, 0)                                                       // Operand representing the Class.
        return inst(Opcode.not_instance_of, TypeBoolean, value, klass)                      // TODO implement instanceof
    }

    //-------------------------------------------------------------------------//

    protected fun Block.ret()                        = inst(Opcode.ret)
    protected fun Block.ret(retValue: Operand)       = inst(Opcode.ret, retValue)
    protected fun Block.resume()                     = inst(Opcode.resume)
    protected fun Block.unreachable()                = inst(Opcode.unreachable)
    protected fun Block.call(defType: Type, vararg uses: Operand) = inst(Opcode.call, defType, *uses)

    protected fun Block.add  (defType: Type, use: Operand) = inst(Opcode.add,   defType, use)
    protected fun Block.trunk(defType: Type, use: Operand) = inst(Opcode.trunc, defType, use)
    protected fun Block.sext (defType: Type, use: Operand) = inst(Opcode.sext,  defType, use)
    protected fun Block.cast (defType: Type, use: Operand) = inst(Opcode.cast,  defType, use)

    protected fun Block.load(defType: Type, address: Operand, offset: Constant)   = inst(Opcode.load, defType, address, offset)
    protected fun Block.store(value: Operand, address: Operand, offset: Constant) = inst(Opcode.store, value, address, offset)

    protected fun Block.mov(defType: Type, use: Operand) = inst(Opcode.mov, defType, use)
    protected fun Block.mov(def: Variable, use: Operand) { instruction(Opcode.mov, def, use) }
}

