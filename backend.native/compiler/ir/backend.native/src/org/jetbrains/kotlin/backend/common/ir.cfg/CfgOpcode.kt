package org.jetbrains.kotlin.backend.common.ir.cfg

enum class Opcode {
    ret,                    // Terminators
    br,
    condbr,
    switch,
    indirectbr,
    invoke,
    resume,
    catchswitch,
    catchret,
    cleanupret,
    unreachable,

    add,                    // Integer binary operations
    sub,
    mul,
    udiv,
    sdiv,
    urem,
    srem,

    shl,                    // Bitwise binary operations
    lshr,
    ashr,
    and,
    or,
    xor,

    extractelement,         // Vector operations
    insertelement,
    shufflevector,

    extractvalue,           // Aggregate operations
    insertvalue,

    alloca,                 // Memory access and addressing operations
    load,
    store,
    gload,  // load global field
    gstore, // store global field
    fence,
    cmpxchg,
    atomicrmw,
    getelementptr,

    trunc,                  // Conversion operations
    zext,
    sext,
    fptrunc,
    fpext,
    fptoui,
    fptosi,
    uitofp,
    sitofp,
    ptrtoint,
    inttoptr,
    bitcast,
    addrspacecast,

    cmp,                    // Other operations
    phi,
    select,
    call,
    mov,
    landingpad,
    catchpad,
    cleanuppad,
    invalid,

    cast,                   // Type operations (workaround)
    integer_coercion,
    implicit_cast,
    implicit_not_null,
    coercion_to_unit,
    safe_cast,
    instance_of,
    not_instance_of
}

