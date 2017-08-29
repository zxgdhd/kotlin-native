package org.jetbrains.kotlin.backend.common.ir.cfg


// !!! Not a "serious" analysis. Just some experiments :)
// Determines instructions whose def is used as return value
fun analyzeReturns(ir: Ir): Map<Function, List<Instruction>> {
    val result = mutableMapOf<Function, List<Instruction>>()
    ir.functions.values.forEach { result[it] = analyzeReturns(it) }
    return result
}

// marks KlassPtr variables that are returned
fun analyzeReturns(function: Function) = function.blocks
        .flatMap { it.instructions }
        .filter { it is Ret }
        .flatMap { it.uses }
        .filter { it is Variable }
        .filter { it.type is Type.KlassPtr }
        .flatMap { it.defs }
