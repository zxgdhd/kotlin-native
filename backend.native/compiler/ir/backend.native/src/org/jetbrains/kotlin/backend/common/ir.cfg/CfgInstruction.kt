package org.jetbrains.kotlin.backend.common.ir.cfg

class Call(val callee: Function, val def: Variable, val args: List<Operand>)
    : Instruction(args + listOf(callee.ptr),
        defs = if (def.type != TypeUnit) listOf(def) else emptyList())

class Invoke(val callee: Function, val def: Variable, val args: List<Operand>, val landingpad: Block)
    : Instruction((listOf(callee.ptr, landingpad.ptr) + args),
        defs = if (def.type != TypeUnit) listOf(def) else emptyList())

class CallVirtual(val callee: Function, val def: Variable, val args: List<Operand>)
    : Instruction((listOf(callee.ptr) + args), listOf(def))

class CallInterface(val callee: Function, val def: Variable, val args: List<Operand>)
    : Instruction((listOf(callee.ptr) + args), listOf(def))

class Condbr(val condition: Operand, val targetTrue: Block, val targetFalse: Block)
    : Instruction(listOf(condition, targetFalse.ptr, targetTrue.ptr))

class Br(val target: Block)
    : Instruction(listOf(target.ptr))

class Ret(val value: Operand = CfgNull)
    : Instruction(listOf(value))

class Throw(val exception: Operand)
    : Instruction(listOf(exception))

class Load(def: Variable, address: Operand, offset: Constant)
    : Instruction(listOf(address, offset), listOf(def))

class Store(val value: Operand, val address: Variable, val offset: Constant = 0.cfg)
    : Instruction(listOf(value, address, offset))

class Landingpad(val exception: Variable)
    : Instruction(defs = listOf(exception))

class InstanceOf(val def: Variable, val value: Operand, val type: Type)
    : Instruction(listOf(value, Constant(Type.ptr(), type)), listOf(def))

class NotInstanceOf(val def: Variable, val value: Operand, val type: Type)
    : Instruction(listOf(value, Constant(Type.ptr(), type)), listOf(def))

// Allocate on stack
class Alloc(val def: Variable, val type: Type)
    : Instruction(listOf(Constant(type, type)), listOf(def))

// Allocate on heap
class AllocInstance(val def: Variable, val klass: Klass)
    : Instruction(listOf(Constant(Type.KlassPtr(klass), klass)), listOf(def))

class Cast(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Sext(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Trunk(def: Variable, use: Operand)
    : Instruction(listOf(use), listOf(def))

class Gstore(val fieldName: String, val initializer: Operand)
    : Instruction(listOf(Constant(Type.FieldPtr, fieldName), initializer))

class Bitcast(val def: Variable, val rawPointer: Operand, val pointerType: Type)
    : Instruction(listOf(rawPointer, Constant(Type.ptr(), pointerType)), listOf(def))

class Gep(val def: Variable, base: Operand, index: Operand)
    : Instruction(listOf(base, index), listOf(def))

class GT0(val def: Variable, val arg: Operand)
    : Instruction(listOf(arg), listOf(def))

class LT0(val def: Variable, val arg: Operand)
    : Instruction(listOf(arg), listOf(def))

sealed class BinOp(val def: Variable, val op1: Operand, val op2: Operand)
    : Instruction(listOf(op1, op2), listOf(def)) {
    class Add(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Sub(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Mul(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Sdiv(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class Srem(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class IcmpNE(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class IcmpEq(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
    class FcmpEq(def: Variable, op1: Operand, op2: Operand) : BinOp(def, op1, op2)
}