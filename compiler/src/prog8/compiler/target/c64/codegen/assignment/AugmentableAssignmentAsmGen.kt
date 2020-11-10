package prog8.compiler.target.c64.codegen.assignment

import prog8.ast.Program
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.statements.Subroutine
import prog8.compiler.AssemblyError
import prog8.compiler.target.CompilationTarget
import prog8.compiler.target.CpuType
import prog8.compiler.target.c64.codegen.AsmGen
import prog8.compiler.target.c64.codegen.ExpressionsAsmGen
import prog8.compiler.toHex

internal class AugmentableAssignmentAsmGen(private val program: Program,
                                           private val assignmentAsmGen: AssignmentAsmGen,
                                           private val exprAsmGen: ExpressionsAsmGen,
                                           private val asmgen: AsmGen) {
    fun translate(assign: AsmAssignment) {
        require(assign.isAugmentable)
        require(assign.source.kind== SourceStorageKind.EXPRESSION)

        when (val value = assign.source.expression!!) {
            is PrefixExpression -> {
                // A = -A , A = +A, A = ~A, A = not A
                val itype = value.inferType(program)
                if(!itype.isKnown)
                    throw AssemblyError("unknown dt")
                val type = itype.typeOrElse(DataType.STRUCT)
                when (value.operator) {
                    "+" -> {}
                    "-" -> inplaceNegate(assign.target, type)
                    "~" -> inplaceInvert(assign.target, type)
                    "not" -> inplaceBooleanNot(assign.target, type)
                    else -> throw AssemblyError("invalid prefix operator")
                }
            }
            is TypecastExpression -> inplaceCast(assign.target, value, assign.position)
            is BinaryExpression -> inplaceBinary(assign.target, value)
            else -> throw AssemblyError("invalid aug assign value type")
        }
    }

    private fun inplaceBinary(target: AsmAssignTarget, binExpr: BinaryExpression) {
        val astTarget = target.origAstTarget!!
        if (binExpr.left isSameAs astTarget) {
            // A = A <operator> Something
            return inplaceModification(target, binExpr.operator, binExpr.right)
        }

        if (binExpr.operator in associativeOperators) {
            if (binExpr.right isSameAs astTarget) {
                // A = 5 <operator> A
                return inplaceModification(target, binExpr.operator, binExpr.left)
            }

            val leftBinExpr = binExpr.left as? BinaryExpression
            if (leftBinExpr?.operator == binExpr.operator) {
                // TODO better optimize the chained asm to avoid intermediate stores/loads?
                when {
                    binExpr.right isSameAs astTarget -> {
                        // A = (x <associative-operator> y) <same-operator> A
                        inplaceModification(target, binExpr.operator, leftBinExpr.left)
                        inplaceModification(target, binExpr.operator, leftBinExpr.right)
                        return
                    }
                    leftBinExpr.left isSameAs astTarget -> {
                        // A = (A <associative-operator> x) <same-operator> y
                        inplaceModification(target, binExpr.operator, leftBinExpr.right)
                        inplaceModification(target, binExpr.operator, binExpr.right)
                        return
                    }
                    leftBinExpr.right isSameAs astTarget -> {
                        // A = (x <associative-operator> A) <same-operator> y
                        inplaceModification(target, binExpr.operator, leftBinExpr.left)
                        inplaceModification(target, binExpr.operator, binExpr.right)
                        return
                    }
                }
            }
            val rightBinExpr = binExpr.right as? BinaryExpression
            if (rightBinExpr?.operator == binExpr.operator) {
                when {
                    binExpr.left isSameAs astTarget -> {
                        // A = A <associative-operator> (x <same-operator> y)
                        inplaceModification(target, binExpr.operator, rightBinExpr.left)
                        inplaceModification(target, binExpr.operator, rightBinExpr.right)
                        return
                    }
                    rightBinExpr.left isSameAs astTarget -> {
                        // A = y <associative-operator> (A <same-operator> x)
                        inplaceModification(target, binExpr.operator, binExpr.left)
                        inplaceModification(target, binExpr.operator, rightBinExpr.right)
                        return
                    }
                    rightBinExpr.right isSameAs astTarget -> {
                        // A = y <associative-operator> (x <same-operator> y)
                        inplaceModification(target, binExpr.operator, binExpr.left)
                        inplaceModification(target, binExpr.operator, rightBinExpr.left)
                        return
                    }
                }
            }
        }

        throw FatalAstException("assignment should be augmentable $binExpr")
    }

    private fun inplaceModification(target: AsmAssignTarget, operator: String, value: Expression) {
        val valueLv = (value as? NumericLiteralValue)?.number
        val ident = value as? IdentifierReference
        val memread = value as? DirectMemoryRead

        when(target.kind) {
            TargetStorageKind.VARIABLE -> {
                when (target.datatype) {
                    in ByteDatatypes -> {
                        when {
                            valueLv != null -> inplaceModification_byte_litval_to_variable(target.asmVarname, target.datatype, operator, valueLv.toInt())
                            ident != null -> inplaceModification_byte_variable_to_variable(target.asmVarname, target.datatype, operator, ident)
                            memread != null -> inplaceModification_byte_memread_to_variable(target.asmVarname, target.datatype, operator, memread)
                            value is TypecastExpression -> {
                                if (tryRemoveRedundantCast(value, target, operator)) return
                                inplaceModification_byte_value_to_variable(target.asmVarname, target.datatype, operator, value)
                            }
                            else -> inplaceModification_byte_value_to_variable(target.asmVarname, target.datatype, operator, value)
                        }
                    }
                    in WordDatatypes -> {
                        when {
                            valueLv != null -> inplaceModification_word_litval_to_variable(target.asmVarname, target.datatype, operator, valueLv.toInt())
                            ident != null -> inplaceModification_word_variable_to_variable(target.asmVarname, target.datatype, operator, ident)
                            memread != null -> inplaceModification_word_memread_to_variable(target.asmVarname, target.datatype, operator, memread)
                            value is TypecastExpression -> {
                                if (tryRemoveRedundantCast(value, target, operator)) return
                                inplaceModification_word_value_to_variable(target.asmVarname, target.datatype, operator, value)
                            }
                            else -> inplaceModification_word_value_to_variable(target.asmVarname, target.datatype, operator, value)
                        }
                    }
                    DataType.FLOAT -> {
                        when {
                            valueLv != null -> inplaceModification_float_litval_to_variable(target.asmVarname, operator, valueLv.toDouble(), target.scope!!)
                            ident != null -> inplaceModification_float_variable_to_variable(target.asmVarname, operator, ident, target.scope!!)
                            value is TypecastExpression -> {
                                if (tryRemoveRedundantCast(value, target, operator)) return
                                inplaceModification_float_value_to_variable(target.asmVarname, operator, value, target.scope!!)
                            }
                            else -> inplaceModification_float_value_to_variable(target.asmVarname, operator, value, target.scope!!)
                        }
                    }
                    else -> throw AssemblyError("weird type to do in-place modification on ${target.datatype}")
                }
            }
            TargetStorageKind.MEMORY -> {
                val memory = target.memory!!
                when (memory.addressExpression) {
                    is NumericLiteralValue -> {
                        val addr = (memory.addressExpression as NumericLiteralValue).number.toInt()
                        // re-use code to assign a variable, instead this time, use a direct memory address
                        when {
                            valueLv != null -> inplaceModification_byte_litval_to_variable(addr.toHex(), DataType.UBYTE, operator, valueLv.toInt())
                            ident != null -> inplaceModification_byte_variable_to_variable(addr.toHex(), DataType.UBYTE, operator, ident)
                            // TODO more specialized code for types such as memory read etc.
                            value is TypecastExpression -> {
                                if (tryRemoveRedundantCast(value, target, operator)) return
                                inplaceModification_byte_value_to_variable(addr.toHex(), DataType.UBYTE, operator, value)
                            }
                            else -> inplaceModification_byte_value_to_variable(addr.toHex(), DataType.UBYTE, operator, value)
                        }
                    }
                    is IdentifierReference -> {
                        val pointer = memory.addressExpression as IdentifierReference
                        when {
                            valueLv != null -> inplaceModification_byte_litval_to_memory(pointer, operator, valueLv.toInt())
                            ident != null -> inplaceModification_byte_variable_to_memory(pointer, operator, ident)
                            // TODO more specialized code for types such as memory read etc.
                            value is TypecastExpression -> {
                                if (tryRemoveRedundantCast(value, target, operator)) return
                                inplaceModification_byte_value_to_memory(pointer, operator, value)
                            }
                            else -> inplaceModification_byte_value_to_memory(pointer, operator, value)
                        }
                    }
                    else -> {
                        if(asmgen.options.slowCodegenWarnings)
                            println("warning: slow stack evaluation used (1): ${memory.addressExpression::class.simpleName} at ${memory.addressExpression.position}") // TODO optimize...
                        asmgen.translateExpression(memory.addressExpression)
                        asmgen.out("  jsr  prog8_lib.read_byte_from_address_on_stack |  sta  P8ZP_SCRATCH_B1")
                        val zp = CompilationTarget.instance.machine.zeropage
                        when {
                            valueLv != null -> inplaceModification_byte_litval_to_variable(zp.SCRATCH_B1.toHex(), DataType.UBYTE, operator, valueLv.toInt())
                            ident != null -> inplaceModification_byte_variable_to_variable(zp.SCRATCH_B1.toHex(), DataType.UBYTE, operator, ident)
                            memread != null -> inplaceModification_byte_memread_to_variable(zp.SCRATCH_B1.toHex(), DataType.UBYTE, operator, memread)
                            value is TypecastExpression -> {
                                if (tryRemoveRedundantCast(value, target, operator)) return
                                inplaceModification_byte_value_to_variable(zp.SCRATCH_B1.toHex(), DataType.UBYTE, operator, value)
                            }
                            else -> inplaceModification_byte_value_to_variable(zp.SCRATCH_B1.toHex(), DataType.UBYTE, operator, value)
                        }
                        asmgen.out("  lda  P8ZP_SCRATCH_B1 |  jsr  prog8_lib.write_byte_to_address_on_stack | inx")
                    }
                }
            }
            TargetStorageKind.ARRAY -> {
                if(asmgen.options.slowCodegenWarnings)
                    println("*** TODO optimize simple inplace array assignment ${target.array}  $operator=  $value")
                assignmentAsmGen.translateNormalAssignment(target.origAssign) // TODO get rid of this fallback for the most common cases here
            }
            TargetStorageKind.REGISTER -> TODO("reg in-place modification")
            TargetStorageKind.STACK -> TODO("stack in-place modification")
        }
    }

    private fun tryRemoveRedundantCast(value: TypecastExpression, target: AsmAssignTarget, operator: String): Boolean {
        if (target.datatype == value.type) {
            val childIDt = value.expression.inferType(program)
            if(!childIDt.isKnown)
                throw AssemblyError("unknown dt")
            val childDt = childIDt.typeOrElse(DataType.STRUCT)
            if (value.type!=DataType.FLOAT && (value.type.equalsSize(childDt) || value.type.largerThan(childDt))) {
                // this typecast is redundant here; the rest of the code knows how to deal with the uncasted value.
                // (works for integer types, not for float.)
                inplaceModification(target, operator, value.expression)
                return true
            }
        }
        return false
    }

    private fun inplaceModification_byte_value_to_memory(pointervar: IdentifierReference, operator: String, value: Expression) {
        if(asmgen.options.slowCodegenWarnings)
            println("warning: slow stack evaluation used (3):  @(${pointervar.nameInSource.last()}) $operator= ${value::class.simpleName} at ${value.position}") // TODO
        asmgen.translateExpression(value)
        val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> asmgen.out(" clc |  adc  P8ESTACK_LO+1,x")
            "-" -> asmgen.out(" sec |  sbc  P8ESTACK_LO+1,x")
            "*" -> asmgen.out("  pha |  lda  P8ESTACK_LO+1,x |  tay |  pla |  jsr  math.multiply_bytes |  ldy  #0")
            "/" -> asmgen.out("  pha |  lda  P8ESTACK_LO+1,x |  tay |  pla |  jsr  math.divmod_ub_asm |  tya |  ldy  #0")
            "%" -> asmgen.out("  pha |  lda  P8ESTACK_LO+1,x |  tay |  pla |  jsr  math.divmod_ub_asm |  ldy  #0")
            "<<" -> {
                asmgen.out("""
                    pha
                    lda  P8ESTACK_LO+1,x
                    bne  +
                    pla
                    rts
+                   tay
                    pla 
-                   asl  a
                    dey
                    bne  -
+""")
            }
            ">>" -> {
                asmgen.out("""
                    pha
                    lda  P8ESTACK_LO+1,x
                    bne  +
                    pla
                    rts
+                   tay
                    pla 
-                   lsr  a
                    dey
                    bne  -
+""")
            }
            "&" -> asmgen.out(" and  P8ESTACK_LO+1,x")
            "^" -> asmgen.out(" eor  P8ESTACK_LO+1,x")
            "|" -> asmgen.out(" ora  P8ESTACK_LO+1,x")
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
        if(ptrOnZp)
            asmgen.out("  sta  ($sourceName),y")
        else
            asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
        asmgen.out(" inx")
    }

    private fun inplaceModification_byte_variable_to_memory(pointervar: IdentifierReference, operator: String, value: IdentifierReference) {
        val otherName = asmgen.asmVariableName(value)
        val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)

        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> asmgen.out(" clc |  adc  $otherName")
            "-" -> asmgen.out(" sec |  sbc  $otherName")
            "*" -> asmgen.out("  ldy  $otherName |  jsr  math.multiply_bytes |  ldy  #0")
            "/" -> asmgen.out("  ldy  $otherName |  jsr  math.divmod_ub_asm |  tya |  ldy  #0")
            "%" -> asmgen.out("  ldy  $otherName |  jsr  math.divmod_ub_asm |  ldy  #0")
            "<<" -> {
                asmgen.out("""
                        ldy  $otherName
                        beq  + 
-                       asl  a
                        dey
                        bne  -
+""")
            }
            ">>" -> {
                asmgen.out("""
                        ldy  $otherName
                        beq  + 
-                       lsr  a
                        dey
                        bne  -
+""")
            }
            "&" -> asmgen.out(" and  $otherName")
            "^" -> asmgen.out(" eor  $otherName")
            "|" -> asmgen.out(" ora  $otherName")
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
        if(ptrOnZp)
            asmgen.out("  sta  ($sourceName),y")
        else
            asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
    }

    private fun inplaceModification_byte_litval_to_memory(pointervar: IdentifierReference, operator: String, value: Int) {
        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                asmgen.out(" clc |  adc  #$value")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "-" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                asmgen.out(" sec |  sbc  #$value")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "*" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                if(value in asmgen.optimizedByteMultiplications)
                    asmgen.out("  jsr  math.mul_byte_${value}")
                else
                    asmgen.out("  ldy  #$value |  jsr  math.multiply_bytes |  ldy  #0")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "/" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                if(value==0)
                    throw AssemblyError("division by zero")
                asmgen.out("  ldy  #$value |  jsr  math.divmod_ub_asm |  tya |  ldy  #0")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "%" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                if(value==0)
                    throw AssemblyError("division by zero")
                asmgen.out("  ldy  #$value |  jsr  math.divmod_ub_asm |  ldy  #0")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "<<" -> {
                if (value > 0) {
                    val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                    repeat(value) { asmgen.out(" asl  a") }
                    if(ptrOnZp)
                        asmgen.out("  sta  ($sourceName),y")
                    else
                        asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
                }
            }
            ">>" -> {
                if (value > 0) {
                    val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                    repeat(value) { asmgen.out(" lsr  a") }
                    if(ptrOnZp)
                        asmgen.out("  sta  ($sourceName),y")
                    else
                        asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
                }
            }
            "&" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                asmgen.out(" and  #$value")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "^" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                asmgen.out(" eor  #$value")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            "|" -> {
                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(pointervar)
                asmgen.out(" ora  #$value")
                if(ptrOnZp)
                    asmgen.out("  sta  ($sourceName),y")
                else
                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
            }
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
    }

    private fun inplaceModification_byte_value_to_variable(name: String, dt: DataType, operator: String, value: Expression) {
        // this should be the last resort for code generation for this,
        // because the value is evaluated onto the eval stack (=slow).
        if(asmgen.options.slowCodegenWarnings)
            println("warning: slow stack evaluation used (5):  $name $operator= ${value::class.simpleName} at ${value.position}") // TODO
        asmgen.translateExpression(value)
        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> asmgen.out(" lda  $name |  clc |  adc  P8ESTACK_LO+1,x |  sta  $name")
            "-" -> asmgen.out(" lda  $name |  sec |  sbc  P8ESTACK_LO+1,x |  sta  $name")
            "*" -> asmgen.out("  lda  P8ESTACK_LO+1,x |  ldy  $name |  jsr  math.multiply_bytes |  sta  $name")
            "/" -> {
                if(dt==DataType.UBYTE)
                    asmgen.out("  lda  P8ESTACK_LO+1,x |  tay |  lda  $name |  jsr  math.divmod_ub_asm |  sty  $name")
                else
                    asmgen.out("  lda  P8ESTACK_LO+1,x |  tay |  lda  $name |  jsr  math.divmod_b_asm |  sty  $name")
            }
            "%" -> {
                if(dt==DataType.BYTE)
                    throw AssemblyError("remainder of signed integers is not properly defined/implemented, use unsigned instead")
                asmgen.out("  lda  P8ESTACK_LO+1,x |  tay |  lda  $name |  jsr  math.divmod_ub_asm |  sta  $name")
            }
            "<<" -> {
                asmgen.translateExpression(value)       // todo directly into Y
                asmgen.out("""
                    inx
                    ldy  P8ESTACK_LO,x
                    beq  +
-                   asl  $name
                    dey
                    bne  -
+""")
            }
            ">>" -> {
                asmgen.translateExpression(value)   // todo directly into Y
                if(dt==DataType.UBYTE) {
                    asmgen.out("""
                        inx
                        ldy  P8ESTACK_LO,x
                        beq  +
-                       lsr  $name
                        dey
                        bne  -
+""")
                } else {
                    asmgen.out("""
                        inx
                        ldy  P8ESTACK_LO,x
                        beq  +
-                       lda  $name
                        asl  a
                        ror  $name
                        dey
                        bne  -
+""")
                }
            }
            "&" -> asmgen.out(" lda  $name |  and  P8ESTACK_LO+1,x |  sta  $name")
            "^" -> asmgen.out(" lda  $name |  eor  P8ESTACK_LO+1,x |  sta  $name")
            "|" -> asmgen.out(" lda  $name |  ora  P8ESTACK_LO+1,x |  sta  $name")
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
        asmgen.out(" inx")
    }

    private fun inplaceModification_byte_variable_to_variable(name: String, dt: DataType, operator: String, ident: IdentifierReference) {
        val otherName = asmgen.asmVariableName(ident)
        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> asmgen.out(" lda  $name |  clc |  adc  $otherName |  sta  $name")
            "-" -> asmgen.out(" lda  $name |  sec |  sbc  $otherName |  sta  $name")
            "*" -> asmgen.out(" lda  $name |  ldy  $otherName  |  jsr  math.multiply_bytes |  sta  $name")
            "/" -> {
                if(dt==DataType.BYTE) {
                    asmgen.out(" lda  $name |  ldy  $otherName  |  jsr  math.divmod_b_asm |  sty  $name")
                }
                else {
                    asmgen.out(" lda  $name |  ldy  $otherName  |  jsr  math.divmod_ub_asm |  sty  $name")
                }
            }
            "%" -> {
                if(dt==DataType.BYTE)
                    throw AssemblyError("remainder of signed integers is not properly defined/implemented, use unsigned instead")
                asmgen.out(" lda  $name |  ldy  $otherName  |  jsr  math.divmod_ub_asm |  sta  $name")
            }
            "<<" -> {
                asmgen.out("""
                    ldy  $otherName
                    beq  +
-                   asl  $name
                    dey
                    bne  -
+""")
            }
            ">>" -> {
                if(dt==DataType.UBYTE) {
                    asmgen.out("""
                        ldy  $otherName
                        beq  +
-                       lsr  $name
                        dey
                        bne  -
+""")
                } else {
                    asmgen.out("""
                        ldy  $otherName
                        beq  +
-                       lda  $name
                        asl  a
                        ror  $name
                        dey
                        bne  -
+""")
                }
            }
            "&" -> asmgen.out(" lda  $name |  and  $otherName |  sta  $name")
            "^" -> asmgen.out(" lda  $name |  eor  $otherName |  sta  $name")
            "|" -> asmgen.out(" lda  $name |  ora  $otherName |  sta  $name")
            "and" -> asmgen.out("""
                lda  $name
                and  $otherName
                beq  +
                lda  #1
+               sta  $name""")
            "or" -> asmgen.out("""
                lda  $name
                ora  $otherName
                beq  +
                lda  #1
+               sta  $name""")
            "xor" -> asmgen.out("""
                lda  $name
                eor  $otherName
                beq  +
                lda  #1
+               sta  $name""")
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
    }

    private fun inplaceModification_byte_litval_to_variable(name: String, dt: DataType, operator: String, value: Int) {
        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> asmgen.out(" lda  $name |  clc |  adc  #$value |  sta  $name")
            "-" -> asmgen.out(" lda  $name |  sec |  sbc  #$value |  sta  $name")
            "*" -> {
                if(value in asmgen.optimizedByteMultiplications)
                    asmgen.out("  lda  $name |  jsr  math.mul_byte_$value |  sta  $name")
                else
                    asmgen.out("  lda  $name |  ldy  #$value |  jsr  math.multiply_bytes |  sta  $name")
            }
            "/" -> {
                if (dt == DataType.UBYTE)
                    asmgen.out("  lda  $name |  ldy  #$value |  jsr  math.divmod_ub_asm |  sty  $name")
                else
                    asmgen.out("  lda  $name |  ldy  #$value |  jsr  math.divmod_b_asm |  sty  $name")
            }
            "%" -> {
                if(dt==DataType.BYTE)
                    throw AssemblyError("remainder of signed integers is not properly defined/implemented, use unsigned instead")
                asmgen.out("""
                    lda  $name
                    ldy  #$value
                    jsr  math.divmod_ub_asm
                    sta  $name""")
            }
            "<<" -> {
                if(value>=8) asmgen.out(" lda  #0 |  sta  $name")
                else repeat(value) { asmgen.out(" asl  $name") }
            }
            ">>" -> {
                if(value>0) {
                    if (dt == DataType.UBYTE) {
                        if(value>=8) asmgen.out(" lda  #0 |  sta  $name")
                        else repeat(value) { asmgen.out(" lsr  $name") }
                    } else {
                        when {
                            value>=8 -> asmgen.out("""
                                lda  $name
                                bmi  +
                                lda  #0
                                beq  ++
+                               lda  #-1
+                               sta  $name""")
                            value>3 -> asmgen.out("""
                                lda  $name
                                ldy  #$value
                                jsr  math.lsr_byte_A
                                sta  $name""")
                            else -> repeat(value) { asmgen.out(" lda  $name | asl  a |  ror  $name") }
                        }
                    }
                }
            }
            "&" -> asmgen.out(" lda  $name |  and  #$value |  sta  $name")
            "^" -> asmgen.out(" lda  $name |  eor  #$value |  sta  $name")
            "|" -> asmgen.out(" lda  $name |  ora  #$value |  sta  $name")
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
    }

    private fun inplaceModification_byte_memread_to_variable(name: String, dt: DataType, operator: String, memread: DirectMemoryRead) {
        when(operator) {
            "+" -> {
                exprAsmGen.translateDirectMemReadExpression(memread, false)
                asmgen.out("""
                    clc
                    adc  $name
                    sta  $name""")
            }
            "-" -> {
                exprAsmGen.translateDirectMemReadExpression(memread, false)
                asmgen.out("""
                    sta  P8ZP_SCRATCH_B1
                    lda  $name
                    sec
                    sbc  P8ZP_SCRATCH_B1
                    sta  $name""")
                // TODO: tuned code for more operators
            }
            else -> {
                inplaceModification_byte_value_to_variable(name, dt, operator, memread)
            }
        }
    }

    private fun inplaceModification_word_memread_to_variable(name: String, dt: DataType, operator: String, memread: DirectMemoryRead) {
        when(operator) {
            "+" -> {
                exprAsmGen.translateDirectMemReadExpression(memread, false)
                asmgen.out("""
                    clc
                    adc  $name
                    sta  $name
                    bcc  +
                    inc  $name+1
+""")
            }
            "-" -> {
                exprAsmGen.translateDirectMemReadExpression(memread, false)
                asmgen.out("""
                    sta  P8ZP_SCRATCH_B1
                    lda  $name
                    sec
                    sbc  P8ZP_SCRATCH_B1
                    sta  $name
                    bcc  +
                    dec  $name+1
+""")
                // TODO: tuned code for more operators
            }
            else -> {
                inplaceModification_word_value_to_variable(name, dt, operator, memread)
            }
        }
    }

    private fun inplaceModification_word_litval_to_variable(name: String, dt: DataType, operator: String, value: Int) {
        when (operator) {
            // note: ** (power) operator requires floats.
            "+" -> {
                when {
                    value==0 -> {}
                    value in 1..0xff -> asmgen.out("""
                        lda  $name
                        clc
                        adc  #$value
                        sta  $name
                        bcc  +
                        inc  $name+1
+""")
                    value==0x0100 -> asmgen.out(" inc  $name+1")
                    value==0x0200 -> asmgen.out(" inc  $name+1 |  inc  $name+1")
                    value==0x0300 -> asmgen.out(" inc  $name+1 |  inc  $name+1 |  inc  $name+1")
                    value==0x0400 -> asmgen.out(" inc  $name+1 |  inc  $name+1 |  inc  $name+1 |  inc  $name+1")
                    value and 255==0 -> asmgen.out(" lda  $name+1 |  clc |  adc  #>$value |  sta  $name+1")
                    else -> asmgen.out("""
                        lda  $name
                        clc
                        adc  #<$value
                        sta  $name
                        lda  $name+1
                        adc  #>$value
                        sta  $name+1""")
                }
            }
            "-" -> {
                when {
                    value==0 -> {}
                    value in 1..0xff -> asmgen.out("""
                        lda  $name
                        sec
                        sbc  #$value
                        sta  $name
                        bcs  +
                        dec  $name+1
+""")
                    value==0x0100 -> asmgen.out(" dec  $name+1")
                    value==0x0200 -> asmgen.out(" dec  $name+1 |  dec  $name+1")
                    value==0x0300 -> asmgen.out(" dec  $name+1 |  dec  $name+1 |  dec  $name+1")
                    value==0x0400 -> asmgen.out(" dec  $name+1 |  dec  $name+1 |  dec  $name+1 |  dec  $name+1")
                    value and 255==0 -> asmgen.out(" lda  $name+1 |  sec |  sbc  #>$value |  sta  $name+1")
                    else -> asmgen.out("""
                        lda  $name
                        sec
                        sbc  #<$value
                        sta  $name
                        lda  $name+1
                        sbc  #>$value
                        sta  $name+1""")
                }
            }
            "*" -> {
                // the mul code works for both signed and unsigned
                if(value in asmgen.optimizedWordMultiplications) {
                    asmgen.out("  lda  $name |  ldy  $name+1 |  jsr  math.mul_word_$value |  sta  $name |  sty  $name+1")
                } else {
                    asmgen.out("""
                        lda  $name
                        sta  P8ZP_SCRATCH_W1
                        lda  $name+1
                        sta  P8ZP_SCRATCH_W1+1
                        lda  #<$value
                        ldy  #>$value
                        jsr  math.multiply_words
                        lda  math.multiply_words.result
                        sta  $name
                        lda  math.multiply_words.result+1
                        sta  $name+1""")
                }
            }
            "/" -> {
                if(value==0)
                    throw AssemblyError("division by zero")
                if(dt==DataType.WORD) {
                    asmgen.out("""
                        lda  $name
                        ldy  $name+1
                        sta  P8ZP_SCRATCH_W1
                        sty  P8ZP_SCRATCH_W1+1
                        lda  #<$value
                        ldy  #>$value
                        jsr  math.divmod_w_asm
                        sta  $name
                        sty  $name+1
                    """)
                }
                else {
                    asmgen.out("""
                        lda  $name
                        ldy  $name+1
                        sta  P8ZP_SCRATCH_W1
                        sty  P8ZP_SCRATCH_W1+1
                        lda  #<$value
                        ldy  #>$value
                        jsr  math.divmod_uw_asm
                        sta  $name
                        sty  $name+1
                    """)
                }
            }
            "%" -> {
                if(value==0)
                    throw AssemblyError("division by zero")
                if(dt==DataType.WORD)
                    throw AssemblyError("remainder of signed integers is not properly defined/implemented, use unsigned instead")
                asmgen.out("""
                    lda  $name
                    ldy  $name+1
                    sta  P8ZP_SCRATCH_W1
                    sty  P8ZP_SCRATCH_W1+1
                    lda  #<$value
                    ldy  #>$value
                    jsr  math.divmod_uw_asm
                    lda  P8ZP_SCRATCH_W2
                    sta  $name
                    lda  P8ZP_SCRATCH_W2+2
                    sty  $name+1
                """)
            }
            "<<" -> {
                when {
                    value>=16 -> asmgen.out(" lda  #0 |  sta  $name |  sta  $name+1")
                    value==8 -> asmgen.out(" lda  $name |  sta  $name+1 |  lda  #0 |  sta  $name")
                    value>2 -> asmgen.out("""
                        ldy  #$value
-                       asl  $name
                        rol  $name+1
                        dey
                        bne  -
                    """)
                    else -> repeat(value) { asmgen.out(" asl  $name |  rol  $name+1") }
                }
            }
            ">>" -> {
                if (value > 0) {
                    if(dt==DataType.UWORD) {
                        when {
                            value>=16 -> asmgen.out(" lda  #0 |  sta  $name |  sta  $name+1")
                            value==8 -> asmgen.out(" lda  $name+1 |  sta  $name |  lda  #0 |  sta  $name+1")
                            value>2 -> asmgen.out("""
                                ldy  #$value
-                               lsr  $name+1
                                ror  $name
                                dey
                                bne  -""")
                            else -> repeat(value) { asmgen.out("  lsr  $name+1 |  ror  $name")}
                        }
                    } else {
                        when {
                            value>=16 -> asmgen.out("""
                                lda  $name+1
                                bmi  +
                                lda  #0
                                beq  ++
+                               lda  #-1
+                               sta  $name
                                sta  $name+1""")
                            value==8 -> asmgen.out("""
                                 lda  $name+1
                                 sta  $name
                                 bmi  +
                                 lda  #0
-                                sta  $name+1
                                 beq  ++
+                                lda  #-1
                                 sta  $name+1
+""")
                            value>2 -> asmgen.out("""
                                ldy  #$value
-                               lda  $name+1
                                asl  a
                                ror  $name+1
                                ror  $name
                                dey
                                bne  -""")
                            else -> repeat(value) { asmgen.out("  lda  $name+1 |  asl  a |  ror  $name+1 |  ror  $name") }
                        }
                    }
                }
            }
            "&" -> {
                when {
                    value == 0 -> {
                        if(CompilationTarget.instance.machine.cpu == CpuType.CPU65c02)
                            asmgen.out(" stz  $name |  stz  $name+1")
                        else
                            asmgen.out(" lda  #0 |  sta  $name |  sta  $name+1")
                    }
                    value and 255 == 0 -> {
                        if(CompilationTarget.instance.machine.cpu == CpuType.CPU65c02)
                            asmgen.out(" stz  $name")
                        else
                            asmgen.out(" lda  #0 |  sta  $name")
                        asmgen.out(" lda  $name+1 |  and  #>$value |  sta  $name+1")
                    }
                    value < 0x0100 -> {
                        asmgen.out(" lda  $name |  and  #$value |  sta  $name")
                        if(CompilationTarget.instance.machine.cpu == CpuType.CPU65c02)
                            asmgen.out("  stz  $name+1")
                        else
                            asmgen.out("  lda  #0 |  sta  $name+1")
                    }
                    else -> asmgen.out(" lda  $name |  and  #<$value |  sta  $name |  lda  $name+1 |  and  #>$value |  sta  $name+1")
                }
            }
            "^" -> {
                when {
                    value == 0 -> {}
                    value and 255 == 0 -> asmgen.out(" lda  $name+1 |  eor  #>$value |  sta  $name+1")
                    value < 0x0100 -> asmgen.out(" lda  $name |  eor  #$value |  sta  $name")
                    else -> asmgen.out(" lda  $name |  eor  #<$value |  sta  $name |  lda  $name+1 |  eor  #>$value |  sta  $name+1")
                }
            }
            "|" -> {
                when {
                    value == 0 -> {}
                    value and 255 == 0 -> asmgen.out(" lda  $name+1 |  ora  #>$value |  sta  $name+1")
                    value < 0x0100 -> asmgen.out(" lda  $name |  ora  #$value |  sta  $name")
                    else -> asmgen.out(" lda  $name |  ora  #<$value |  sta  $name |  lda  $name+1 |  ora  #>$value |  sta  $name+1")
                }
            }
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
    }

    private fun inplaceModification_word_variable_to_variable(name: String, dt: DataType, operator: String, ident: IdentifierReference) {
        val otherName = asmgen.asmVariableName(ident)
        val valueDt = ident.targetVarDecl(program.namespace)!!.datatype
        when (valueDt) {
            in ByteDatatypes -> {
                // the other variable is a BYTE type so optimize for that
                when (operator) {
                    // note: ** (power) operator requires floats.
                    "+" -> {
                        if(valueDt==DataType.UBYTE)
                            asmgen.out("""
                                lda  $name
                                clc
                                adc  $otherName
                                sta  $name
                                bcc  +
                                inc  $name+1
+""")
                        else
                            asmgen.out("""
                                ldy  #0
                                lda  $otherName
                                bpl  +
                                dey     ; sign extend
+                               clc
                                adc  $name
                                sta  $name
                                tya
                                adc  $name+1
                                sta  $name+1""")
                    }
                    "-" -> {
                        if(valueDt==DataType.UBYTE)
                            asmgen.out("""
                                lda  $name
                                sec
                                sbc  $otherName
                                sta  $name
                                bcs  +
                                dec  $name+1
+""")
                        else
                            asmgen.out("""
                                ldy  #0
                                lda  $otherName
                                bpl  +
                                dey     ; sign extend
+                               sty  P8ZP_SCRATCH_B1
                                lda  $name
                                sec
                                sbc  $otherName
                                sta  $name
                                lda  $name+1
                                sbc  P8ZP_SCRATCH_B1
                                sta  $name+1""")
                    }
                    "*" -> {
                        asmgen.out("""
                            lda  $otherName
                            sta  P8ZP_SCRATCH_W1
                            lda  #0
                            sta  P8ZP_SCRATCH_W1+1
                            lda  $name
                            ldy  $name+1
                            jsr  math.multiply_words
                            lda  math.multiply_words.result
                            sta  $name
                            lda  math.multiply_words.result+1
                            sta  $name+1""")
                    }
                    "/" -> TODO("div (u)wordvar/bytevar")
                    "%" -> TODO("(u)word remainder bytevar")
                    "<<" -> {
                        asmgen.out("""
                        ldy  $otherName
                        beq  +
-                       asl  $name
                        rol  $name+1
                        dey
                        bne  -
+""")
                    }
                    ">>" -> {
                        if(dt==DataType.UWORD) {
                            asmgen.out("""
                            ldy  $otherName
                            beq  +
-                           lsr  $name+1
                            ror  $name
                            dey
                            bne  -
+""")
                        } else {
                            asmgen.out("""
                            ldy  $otherName
                            beq  +
-                           lda  $name+1
                            asl  a
                            ror  $name+1
                            ror  $name
                            dey
                            bne  -
+""")
                        }
                    }
                    "&" -> {
                        asmgen.out(" lda  $otherName |  and  $name |  sta  $name")
                        if(dt in WordDatatypes)
                            asmgen.out(" lda  #0 |  sta  $name+1")
                    }
                    "^" -> asmgen.out(" lda  $otherName |  eor  $name |  sta  $name")
                    "|" -> asmgen.out(" lda  $otherName |  ora  $name |  sta  $name")
                    else -> throw AssemblyError("invalid operator for in-place modification $operator")
                }
            }
            in WordDatatypes -> {
                // the value is a proper 16-bit word, so use both bytes of it.
                when (operator) {
                    // note: ** (power) operator requires floats.
                    "+" -> asmgen.out(" lda  $name |  clc |  adc  $otherName |  sta  $name |  lda  $name+1 |  adc  $otherName+1 |  sta  $name+1")
                    "-" -> asmgen.out(" lda  $name |  sec |  sbc  $otherName |  sta  $name |  lda  $name+1 |  sbc  $otherName+1 |  sta  $name+1")
                    "*" -> {
                        asmgen.out("""
                            lda  $otherName
                            ldy  $otherName+1
                            sta  P8ZP_SCRATCH_W1
                            sty  P8ZP_SCRATCH_W1+1
                            lda  $name
                            ldy  $name+1
                            jsr  math.multiply_words
                            lda  math.multiply_words.result
                            sta  $name
                            lda  math.multiply_words.result+1
                            sta  $name+1
                        """)
                    }
                    "/" -> {
                        if(dt==DataType.WORD) {
                            asmgen.out("""
                                lda  $name
                                ldy  $name+1
                                sta  P8ZP_SCRATCH_W1
                                sty  P8ZP_SCRATCH_W1+1
                                lda  $otherName
                                ldy  $otherName+1
                                jsr  math.divmod_w_asm
                                sta  $name
                                sty  $name+1
                            """)
                        }
                        else {
                            asmgen.out("""
                                lda  $name
                                ldy  $name+1
                                sta  P8ZP_SCRATCH_W1
                                sty  P8ZP_SCRATCH_W1+1
                                lda  $otherName
                                ldy  $otherName+1
                                jsr  math.divmod_uw_asm
                                sta  $name
                                sty  $name+1
                            """)
                        }
                    }
                    "%" -> {
                        if(dt==DataType.WORD)
                            throw AssemblyError("remainder of signed integers is not properly defined/implemented, use unsigned instead")
                        asmgen.out("""
                            lda  $name
                            ldy  $name+1
                            sta  P8ZP_SCRATCH_W1
                            sty  P8ZP_SCRATCH_W1+1
                            lda  $otherName
                            ldy  $otherName+1
                            jsr  math.divmod_uw_asm
                            lda  P8ZP_SCRATCH_W2
                            sta  $name
                            lda  P8ZP_SCRATCH_W2+1
                            sta  $name+1
                        """)
                    }
                    "<<", ">>" -> throw AssemblyError("shift by a word value not supported, max is a byte")
                    "&" -> asmgen.out(" lda  $name |  and  $otherName |  sta  $name |  lda  $name+1 |  and  $otherName+1 |  sta  $name+1")
                    "^" -> asmgen.out(" lda  $name |  eor  $otherName |  sta  $name |  lda  $name+1 |  eor  $otherName+1 |  sta  $name+1")
                    "|" -> asmgen.out(" lda  $name |  ora  $otherName |  sta  $name |  lda  $name+1 |  ora  $otherName+1 |  sta  $name+1")
                    else -> throw AssemblyError("invalid operator for in-place modification $operator")
                }
            }
            else -> {
                throw AssemblyError("can only use integer datatypes here")
            }
        }
    }

    private fun inplaceModification_word_value_to_variable(name: String, dt: DataType, operator: String, value: Expression) {
        // this should be the last resort for code generation for this,
        // because the value is evaluated onto the eval stack (=slow).
        if(asmgen.options.slowCodegenWarnings)
            println("warning: slow stack evaluation used (4):  $name $operator= ${value::class.simpleName} at ${value.position}") // TODO
        asmgen.translateExpression(value)

        val valueiDt = value.inferType(program)
        if(!valueiDt.isKnown)
            throw AssemblyError("unknown dt")
        val valueDt = valueiDt.typeOrElse(DataType.STRUCT)

        fun multiplyWord() {
            asmgen.out("""
                lda  P8ESTACK_LO+1,x
                ldy  P8ESTACK_HI+1,x
                sta  P8ZP_SCRATCH_W1
                sty  P8ZP_SCRATCH_W1+1
                lda  $name
                ldy  $name+1
                jsr  math.multiply_words
                lda  math.multiply_words.result
                sta  $name
                lda  math.multiply_words.result+1
                sta  $name+1
            """)
        }

        fun divideWord() {
            if (dt == DataType.WORD) {
                asmgen.out("""
                    lda  $name
                    ldy  $name+1
                    sta  P8ZP_SCRATCH_W1
                    sty  P8ZP_SCRATCH_W1+1
                    lda  P8ESTACK_LO+1,x
                    ldy  P8ESTACK_HI+1,x
                    jsr  math.divmod_w_asm
                    sta  $name
                    sty  $name+1
                """)
            } else {
                asmgen.out("""
                    lda  $name
                    ldy  $name+1
                    sta  P8ZP_SCRATCH_W1
                    sty  P8ZP_SCRATCH_W1+1
                    lda  P8ESTACK_LO+1,x
                    ldy  P8ESTACK_HI+1,x
                    jsr  math.divmod_uw_asm
                    sta  $name
                    sty  $name+1
                """)
            }
        }

        fun remainderWord() {
            if(dt==DataType.WORD)
                throw AssemblyError("remainder of signed integers is not properly defined/implemented, use unsigned instead")
            asmgen.out("""
                lda  $name
                ldy  $name+1
                sta  P8ZP_SCRATCH_W1
                sty  P8ZP_SCRATCH_W1+1
                lda  P8ESTACK_LO+1,x
                ldy  P8ESTACK_HI+1,x
                jsr  math.divmod_uw_asm
                lda  P8ZP_SCRATCH_W2
                sta  $name
                lda  P8ZP_SCRATCH_W2+1
                sta  $name+1
            """)
        }

        when(valueDt) {
            in ByteDatatypes -> {
                // the other variable is a BYTE type so optimize for that
                when (operator) {
                    // note: ** (power) operator requires floats.
                    "+" -> {
                        if(valueDt==DataType.UBYTE)
                            asmgen.out("""
                                lda  $name
                                clc
                                adc  P8ESTACK_LO+1,x
                                sta  $name
                                bcc  +
                                inc  $name+1
+""")
                        else
                            asmgen.out("""
                                ldy  #0
                                lda  P8ESTACK_LO+1,x
                                bpl  +
                                dey         ; sign extend
+                               clc
                                adc  $name
                                sta  $name
                                tya
                                adc  $name+1
                                sta  $name+1""")
                    }
                    "-" -> {
                        if(valueDt==DataType.UBYTE)
                            asmgen.out("""
                                lda  $name
                                sec
                                sbc  P8ESTACK_LO+1,x
                                sta  $name
                                bcc  +
                                dec  $name+1
+""")
                        else
                            asmgen.out("""
                                ldy  #0
                                lda  P8ESTACK_LO+1,x
                                bpl  +
                                dey         ; sign extend
+                               sty  P8ZP_SCRATCH_B1
                                lda  $name
                                sec
                                sbc  P8ESTACK_LO+1,x
                                sta  $name
                                lda  $name+1
                                sbc  P8ZP_SCRATCH_B1
                                sta  $name+1""")
                    }
                    "*" -> {
                        // stack contains (u) byte value, sign extend that and proceed with regular 16 bit operation
                        asmgen.signExtendStackLsb(valueDt)
                        multiplyWord()
                    }
                    "/" -> {
                        // stack contains (u) byte value, sign extend that and proceed with regular 16 bit operation
                        asmgen.signExtendStackLsb(valueDt)
                        divideWord()
                    }
                    "%" -> {
                        // stack contains (u) byte value, sign extend that and proceed with regular 16 bit operation
                        asmgen.signExtendStackLsb(valueDt)
                        remainderWord()
                    }
                    "<<" -> {
                        asmgen.out("""
                            ldy  P8ESTACK_LO+1,x
                            beq  +
-                   	    asl  $name
                            rol  $name+1
                            dey
                            bne  -
+""")
                    }
                    ">>" -> {
                        if(dt==DataType.UWORD) {
                            asmgen.out("""
                            ldy  P8ESTACK_LO+1,x
                            beq  +
-                           lsr  $name+1
                            ror  $name
                            dey
                            bne  -
+""") }
                        else {
                            asmgen.out("""
                            ldy  P8ESTACK_LO+1,x
                            beq  +
-                           lda  $name+1
                            asl  a
                            ror  $name+1
                            ror  $name
                            dey
                            bne  -
+""")
                        }
                    }
                    "&" -> {
                        asmgen.out("  lda  P8ESTACK_LO+1,x |  and  $name |  sta  $name")
                        if(dt in WordDatatypes)
                            asmgen.out(" lda  #0 |  sta  $name+1")
                    }
                    "^" -> asmgen.out("  lda  P8ESTACK_LO+1,x |  eor  $name |  sta  $name")
                    "|" -> asmgen.out("  lda  P8ESTACK_LO+1,x |  ora  $name |  sta  $name")
                    else -> throw AssemblyError("invalid operator for in-place modification $operator")
                }
            }
            in WordDatatypes -> {
                // the value is a proper 16-bit word, so use both bytes of it.
                when (operator) {
                    // note: ** (power) operator requires floats.
                    "+" -> asmgen.out(" lda  $name |  clc |  adc  P8ESTACK_LO+1,x |  sta  $name |  lda  $name+1 |  adc  P8ESTACK_HI+1,x |  sta  $name+1")
                    "-" -> asmgen.out(" lda  $name |  sec |  sbc  P8ESTACK_LO+1,x |  sta  $name |  lda  $name+1 |  sbc  P8ESTACK_HI+1,x |  sta  $name+1")
                    "*" -> multiplyWord()
                    "/" -> divideWord()
                    "%" -> remainderWord()
                    "<<", ">>" -> throw AssemblyError("shift by a word value not supported, max is a byte")
                    "&" -> asmgen.out(" lda  $name |  and  P8ESTACK_LO+1,x |  sta  $name | lda  $name+1 |  and  P8ESTACK_HI+1,x  |  sta  $name+1")
                    "^" -> asmgen.out(" lda  $name |  eor  P8ESTACK_LO+1,x |  sta  $name | lda  $name+1 |  eor  P8ESTACK_HI+1,x  |  sta  $name+1")
                    "|" -> asmgen.out(" lda  $name |  ora  P8ESTACK_LO+1,x |  sta  $name | lda  $name+1 |  ora  P8ESTACK_HI+1,x  |  sta  $name+1")
                    else -> throw AssemblyError("invalid operator for in-place modification $operator")
                }
            }
            else -> {
                throw AssemblyError("can only use integer datatypes here")
            }
        }

        asmgen.out(" inx")
    }

    private fun inplaceModification_float_value_to_variable(name: String, operator: String, value: Expression, scope: Subroutine) {
        // this should be the last resort for code generation for this,
        // because the value is evaluated onto the eval stack (=slow).
        if(asmgen.options.slowCodegenWarnings)
            println("warning: slow stack evaluation used (2):  $name $operator= ${value::class.simpleName} at ${value.position}") // TODO
        asmgen.translateExpression(value)
        asmgen.out("  jsr  floats.pop_float_fac1")
        asmgen.saveRegister(CpuRegister.X, false, scope)
        when (operator) {
            "**" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.CONUPK
                    jsr  floats.FPWRT
                """)
            }
            "+" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FADD
                """)
            }
            "-" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FSUB
                """)
            }
            "*" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FMULT
                """)
            }
            "/" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FDIV
                """)
            }
            else -> throw AssemblyError("invalid operator for in-place float modification $operator")
        }
        asmgen.out("""
            ldx  #<$name
            ldy  #>$name
            jsr  floats.MOVMF
        """)
        asmgen.restoreRegister(CpuRegister.X, false)
    }

    private fun inplaceModification_float_variable_to_variable(name: String, operator: String, ident: IdentifierReference, scope: Subroutine) {
        val valueDt = ident.targetVarDecl(program.namespace)!!.datatype
        if(valueDt != DataType.FLOAT)
            throw AssemblyError("float variable expected")

        val otherName = asmgen.asmVariableName(ident)
        asmgen.saveRegister(CpuRegister.X, false, scope)
        when (operator) {
            "**" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.CONUPK
                    lda  #<$otherName
                    ldy  #>$otherName
                    jsr  floats.FPWR
                """)
            }
            "+" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.MOVFM
                    lda  #<$otherName
                    ldy  #>$otherName
                    jsr  floats.FADD
                """)
            }
            "-" -> {
                asmgen.out("""
                    lda  #<$otherName
                    ldy  #>$otherName
                    jsr  floats.MOVFM
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FSUB
                """)
            }
            "*" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.MOVFM
                    lda  #<$otherName
                    ldy  #>$otherName
                    jsr  floats.FMULT
                """)
            }
            "/" -> {
                asmgen.out("""
                    lda  #<$otherName
                    ldy  #>$otherName
                    jsr  floats.MOVFM
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FDIV
                """)
            }
            else -> throw AssemblyError("invalid operator for in-place float modification $operator")
        }
        // store Fac1 back into memory
        asmgen.out("""
            ldx  #<$name
            ldy  #>$name
            jsr  floats.MOVMF
        """)
        asmgen.restoreRegister(CpuRegister.X, false)
    }

    private fun inplaceModification_float_litval_to_variable(name: String, operator: String, value: Double, scope: Subroutine) {
        val constValueName = asmgen.getFloatAsmConst(value)
        asmgen.saveRegister(CpuRegister.X, false, scope)
        when (operator) {
            "**" -> {
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.CONUPK
                    lda  #<$constValueName
                    ldy  #>$constValueName
                    jsr  floats.FPWR
                """)
            }
            "+" -> {
                if (value == 0.0)
                    return
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.MOVFM
                    lda  #<$constValueName
                    ldy  #>$constValueName
                    jsr  floats.FADD
                """)
            }
            "-" -> {
                if (value == 0.0)
                    return
                asmgen.out("""
                    lda  #<$constValueName
                    ldy  #>$constValueName
                    jsr  floats.MOVFM
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FSUB
                """)
            }
            "*" -> {
                // assume that code optimization is already done on the AST level for special cases such as 0, 1, 2...
                asmgen.out("""
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.MOVFM
                    lda  #<$constValueName
                    ldy  #>$constValueName
                    jsr  floats.FMULT
                """)
            }
            "/" -> {
                if (value == 0.0)
                    throw AssemblyError("division by zero")
                asmgen.out("""
                    lda  #<$constValueName
                    ldy  #>$constValueName
                    jsr  floats.MOVFM
                    lda  #<$name
                    ldy  #>$name
                    jsr  floats.FDIV
                """)
            }
            else -> throw AssemblyError("invalid operator for in-place float modification $operator")
        }
        // store Fac1 back into memory
        asmgen.out("""
            ldx  #<$name
            ldy  #>$name
            jsr  floats.MOVMF
        """)
        asmgen.restoreRegister(CpuRegister.X, false)
    }

    private fun inplaceCast(target: AsmAssignTarget, cast: TypecastExpression, position: Position) {
        val outerCastDt = cast.type
        val innerCastDt = (cast.expression as? TypecastExpression)?.type
        if (innerCastDt == null) {
            // simple typecast where the value is the target
            when (target.datatype) {
                DataType.UBYTE, DataType.BYTE -> { /* byte target can't be casted to anything else at all */ }
                DataType.UWORD, DataType.WORD -> {
                    when (outerCastDt) {
                        DataType.UBYTE, DataType.BYTE -> {
                            when(target.kind) {
                                TargetStorageKind.VARIABLE -> {
                                    if(CompilationTarget.instance.machine.cpu == CpuType.CPU65c02)
                                        asmgen.out(" stz  ${target.asmVarname}+1")
                                    else
                                        asmgen.out(" lda  #0 |  sta  ${target.asmVarname}+1")
                                }
                                TargetStorageKind.ARRAY -> {
                                    asmgen.loadScaledArrayIndexIntoRegister(target.array!!, target.datatype, CpuRegister.Y, true)
                                    if(CompilationTarget.instance.machine.cpu == CpuType.CPU65c02)
                                        asmgen.out("  stz  ${target.asmVarname},y")
                                    else
                                        asmgen.out("  lda  #0 |  sta  ${target.asmVarname},y")
                                }
                                TargetStorageKind.STACK -> {
                                    if(CompilationTarget.instance.machine.cpu == CpuType.CPU65c02)
                                        asmgen.out(" stz  P8ESTACK_HI+1,x")
                                    else
                                        asmgen.out(" lda  #0 |  sta  P8ESTACK_HI+1,x")
                                }
                                else -> throw AssemblyError("weird target")
                            }
                        }
                        DataType.UWORD, DataType.WORD, in IterableDatatypes -> {}
                        DataType.FLOAT -> throw AssemblyError("can't cast float in-place")
                        else -> throw AssemblyError("weird cast type")
                    }
                }
                DataType.FLOAT -> {
                    if (outerCastDt != DataType.FLOAT)
                        throw AssemblyError("in-place cast of a float makes no sense")
                }
                else -> throw AssemblyError("invalid cast target type")
            }
        } else {
            // typecast with nested typecast, that has the target as a value
            // calculate singular cast that is required
            val castDt = if (outerCastDt largerThan innerCastDt) innerCastDt else outerCastDt
            val value = (cast.expression as TypecastExpression).expression
            val resultingCast = TypecastExpression(value, castDt, false, position)
            inplaceCast(target, resultingCast, position)
        }
    }

    private fun inplaceBooleanNot(target: AsmAssignTarget, dt: DataType) {
        when (dt) {
            DataType.UBYTE -> {
                when(target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        asmgen.out("""
                            lda  ${target.asmVarname}
                            beq  +
                            lda  #1
+                           eor  #1
                            sta  ${target.asmVarname}""")
                    }
                    TargetStorageKind.MEMORY -> {
                        val mem = target.memory!!
                        when (mem.addressExpression) {
                            is NumericLiteralValue -> {
                                val addr = (mem.addressExpression as NumericLiteralValue).number.toHex()
                                asmgen.out("""
                                    lda  $addr
                                    beq  +
                                    lda  #1
+                                   eor  #1
                                    sta  $addr""")
                            }
                            is IdentifierReference -> {
                                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(mem.addressExpression as IdentifierReference)
                                asmgen.out("""
                                    beq  +
                                    lda  #1
+                                   eor  #1""")
                                if(ptrOnZp)
                                    asmgen.out("  sta  ($sourceName),y")
                                else
                                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
                            }
                            else -> {
                                if(asmgen.options.slowCodegenWarnings)
                                    println("warning: slow stack evaluation used (6): ${mem.addressExpression::class.simpleName} at ${mem.addressExpression.position}") // TODO
                                asmgen.translateExpression(mem.addressExpression)
                                asmgen.out("""
                                    jsr  prog8_lib.read_byte_from_address_on_stack
                                    beq  +
                                    lda  #1
+                                   eor  #1                                    
                                    jsr  prog8_lib.write_byte_to_address_on_stack
                                    inx""")
                            }
                        }
                    }
                    TargetStorageKind.ARRAY -> TODO("in-place not of ubyte array")
                    TargetStorageKind.REGISTER -> TODO("reg not")
                    TargetStorageKind.STACK -> TODO("stack not")
                }
            }
            DataType.UWORD -> {
                when(target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        asmgen.out("""
                            lda  ${target.asmVarname}
                            ora  ${target.asmVarname}+1
                            beq  +
                            lda  #1
+                           eor  #1
                            sta  ${target.asmVarname}
                            lsr  a
                            sta  ${target.asmVarname}+1""")
                    }
                    TargetStorageKind.MEMORY -> throw AssemblyError("no asm gen for uword-memory not")
                    TargetStorageKind.ARRAY -> TODO("in-place not of uword array")
                    TargetStorageKind.REGISTER -> TODO("reg not")
                    TargetStorageKind.STACK -> TODO("stack not")
                }
            }
            else -> throw AssemblyError("boolean-not of invalid type")
        }
    }

    private fun inplaceInvert(target: AsmAssignTarget, dt: DataType) {
        when (dt) {
            DataType.UBYTE -> {
                when(target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        asmgen.out("""
                            lda  ${target.asmVarname}
                            eor  #255
                            sta  ${target.asmVarname}""")
                    }
                    TargetStorageKind.MEMORY -> {
                        val memory = target.memory!!
                        when (memory.addressExpression) {
                            is NumericLiteralValue -> {
                                val addr = (memory.addressExpression as NumericLiteralValue).number.toHex()
                                asmgen.out("""
                                    lda  $addr
                                    eor  #255
                                    sta  $addr""")
                            }
                            is IdentifierReference -> {
                                val (ptrOnZp, sourceName) = asmgen.loadByteFromPointerIntoA(memory.addressExpression as IdentifierReference)
                                asmgen.out("  eor  #255")
                                if(ptrOnZp)
                                    asmgen.out("  sta  ($sourceName),y")
                                else
                                    asmgen.out("  sta  (P8ZP_SCRATCH_W1),y")
                            }
                            else -> {
                                if(asmgen.options.slowCodegenWarnings)
                                    println("warning: slow stack evaluation used (7): ${memory.addressExpression::class.simpleName} at ${memory.addressExpression.position}") // TODO
                                asmgen.translateExpression(memory.addressExpression)
                                asmgen.out("""
                                    jsr  prog8_lib.read_byte_from_address_on_stack
                                    eor  #255
                                    jsr  prog8_lib.write_byte_to_address_on_stack
                                    inx""")
                            }
                        }
                    }
                    TargetStorageKind.ARRAY -> TODO("in-place invert ubyte array")
                    TargetStorageKind.REGISTER -> TODO("reg invert")
                    TargetStorageKind.STACK -> TODO("stack invert")
                }
            }
            DataType.UWORD -> {
                when(target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        asmgen.out("""
                            lda  ${target.asmVarname}
                            eor  #255
                            sta  ${target.asmVarname}
                            lda  ${target.asmVarname}+1
                            eor  #255
                            sta  ${target.asmVarname}+1""")
                    }
                    TargetStorageKind.MEMORY -> throw AssemblyError("no asm gen for uword-memory invert")
                    TargetStorageKind.ARRAY -> TODO("in-place invert uword array")
                    TargetStorageKind.REGISTER -> TODO("reg invert")
                    TargetStorageKind.STACK -> TODO("stack invert")
                }
            }
            else -> throw AssemblyError("invert of invalid type")
        }
    }

    private fun inplaceNegate(target: AsmAssignTarget, dt: DataType) {
        when (dt) {
            DataType.BYTE -> {
                when (target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        asmgen.out("""
                            lda  #0
                            sec
                            sbc  ${target.asmVarname}
                            sta  ${target.asmVarname}""")
                    }
                    TargetStorageKind.MEMORY -> throw AssemblyError("can't in-place negate memory ubyte")
                    TargetStorageKind.ARRAY -> TODO("in-place negate byte array")
                    TargetStorageKind.REGISTER -> TODO("reg negate")
                    TargetStorageKind.STACK -> TODO("stack negate")
                }
            }
            DataType.WORD -> {
                when(target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        asmgen.out("""
                            lda  #0
                            sec
                            sbc  ${target.asmVarname}
                            sta  ${target.asmVarname}
                            lda  #0
                            sbc  ${target.asmVarname}+1
                            sta  ${target.asmVarname}+1""")
                    }
                    TargetStorageKind.ARRAY -> TODO("in-place negate word array")
                    TargetStorageKind.MEMORY -> throw AssemblyError("no asm gen for word memory negate")
                    TargetStorageKind.REGISTER -> TODO("reg negate")
                    TargetStorageKind.STACK -> TODO("stack negate")
                }
            }
            DataType.FLOAT -> {
                when(target.kind) {
                    TargetStorageKind.VARIABLE -> {
                        // simply flip the sign bit in the float
                        asmgen.out("""
                            lda  ${target.asmVarname}+1
                            eor  #$80
                            sta  ${target.asmVarname}+1
                        """)
                    }
                    TargetStorageKind.ARRAY -> TODO("in-place negate float array")
                    TargetStorageKind.STACK -> TODO("stack float negate")
                    else -> throw AssemblyError("weird target kind for float")
                }
            }
            else -> throw AssemblyError("negate of invalid type")
        }
    }

}
