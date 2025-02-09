package prog8.compilerinterface

import prog8.ast.base.FatalAstException
import prog8.ast.base.VarDeclType
import prog8.ast.expressions.IdentifierReference
import prog8.ast.expressions.NumericLiteralValue
import prog8.ast.statements.AssignTarget

fun AssignTarget.isIOAddress(machine: IMachineDefinition): Boolean {
    val memAddr = memoryAddress
    val arrayIdx = arrayindexed
    val ident = identifier
    when {
        memAddr != null -> {
            val addr = memAddr.addressExpression.constValue(definingModule.program)
            if(addr!=null)
                return machine.isIOAddress(addr.number.toUInt())
            return when (memAddr.addressExpression) {
                is IdentifierReference -> {
                    val decl = (memAddr.addressExpression as IdentifierReference).targetVarDecl(definingModule.program)
                    val result = if ((decl?.type == VarDeclType.MEMORY || decl?.type == VarDeclType.CONST) && decl.value is NumericLiteralValue)
                        machine.isIOAddress((decl.value as NumericLiteralValue).number.toUInt())
                    else
                        false
                    result
                }
                else -> false
            }
        }
        arrayIdx != null -> {
            val targetStmt = arrayIdx.arrayvar.targetVarDecl(definingModule.program)
            return if (targetStmt?.type == VarDeclType.MEMORY) {
                val addr = targetStmt.value as? NumericLiteralValue
                if (addr != null)
                    machine.isIOAddress(addr.number.toUInt())
                else
                    false
            } else false
        }
        ident != null -> {
            val decl = ident.targetVarDecl(definingModule.program) ?: throw FatalAstException("invalid identifier ${ident.nameInSource}")
            return if (decl.type == VarDeclType.MEMORY && decl.value is NumericLiteralValue)
                machine.isIOAddress((decl.value as NumericLiteralValue).number.toUInt())
            else
                false
        }
        else -> return false
    }
}
