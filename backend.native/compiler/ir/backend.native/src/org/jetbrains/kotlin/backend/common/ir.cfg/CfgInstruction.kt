package org.jetbrains.kotlin.backend.common.ir.cfg

class Call(val callee: Function, def: Variable, val args: List<Operand>)
    : Instruction(args + listOf(callee.ptr), listOf(def))

class Invoke(val callee: Function, val def: Variable, val args: List<Operand>, val landingpad: Block)
    : Instruction((listOf(callee.ptr, landingpad.ptr) + args), listOf(def))

class Condbr(val condition: Operand, val targetTrue: Block, val targetFalse: Block)
    : Instruction(listOf(condition, targetFalse.ptr, targetTrue.ptr))

class Br(val target: Block)
    : Instruction(listOf(target.ptr))

class Ret(val value: Operand = CfgNull)
    : Instruction(listOf(value))

class Mov(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Load(def: Variable, address: Operand, offset: Constant)
    : Instruction(listOf(address, offset), listOf(def))

class Store(value: Operand, address: Operand, offset: Constant)
    : Instruction(listOf(value, address, offset))

class Landingpad(exception: Variable)
    : Instruction(listOf(exception))

class InstanceOf(val def: Variable, val value: Operand, val type: Type)
    : Instruction(listOf(value, Constant(TypePtr, type)), listOf(def))

class NotInstanceOf(val def: Variable, val value: Operand, val type: Type)
    : Instruction(listOf(value, Constant(TypePtr, type)), listOf(def))

class Alloc(def: Variable, val className: String)
    : Instruction(listOf(Constant(TypeClass, className)), listOf(def))

class Cast(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Sext(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Trunk(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Gstore(val fieldName: String, val initializer: Operand)
    : Instruction(listOf(Constant(TypeField, fieldName), initializer))

sealed class BinOp(def: Variable, op1: Operand, op2: Operand)
    : Instruction(listOf(op1, op2), listOf(def)) {
    class Add(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Sub(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Mul(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Sdiv(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Srem(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
}