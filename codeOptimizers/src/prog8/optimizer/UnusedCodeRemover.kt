package prog8.optimizer

import prog8.ast.*
import prog8.ast.base.DataType
import prog8.ast.base.VarDeclType
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstModification
import prog8.compilerinterface.CallGraph
import prog8.compilerinterface.ICompilationTarget
import prog8.compilerinterface.IErrorReporter
import prog8.compilerinterface.isIOAddress


class UnusedCodeRemover(private val program: Program,
                        private val errors: IErrorReporter,
                        private val compTarget: ICompilationTarget
): AstWalker() {

    private val callgraph = CallGraph(program)

    override fun before(module: Module, parent: Node): Iterable<IAstModification> {
        return if (!module.isLibrary && (module.containsNoCodeNorVars || callgraph.unused(module)))
            listOf(IAstModification.Remove(module, parent as IStatementContainer))
        else
            noModifications
    }

    override fun before(breakStmt: Break, parent: Node): Iterable<IAstModification> {
        reportUnreachable(breakStmt)
        return emptyList()
    }

    override fun before(jump: Jump, parent: Node): Iterable<IAstModification> {
        reportUnreachable(jump)
        return emptyList()
    }

    override fun before(returnStmt: Return, parent: Node): Iterable<IAstModification> {
        reportUnreachable(returnStmt)
        return emptyList()
    }

    override fun before(functionCallStatement: FunctionCallStatement, parent: Node): Iterable<IAstModification> {
        if(functionCallStatement.target.nameInSource.last() == "exit")
            reportUnreachable(functionCallStatement)
        return emptyList()
    }

    private fun reportUnreachable(stmt: Statement) {
        when(val next = stmt.nextSibling()) {
            null, is Label, is Directive, is VarDecl, is InlineAssembly, is Subroutine -> {}
            else -> errors.warn("unreachable code", next.position)
        }
    }

    override fun after(scope: AnonymousScope, parent: Node): Iterable<IAstModification> {
        return deduplicateAssignments(scope.statements, scope)
    }

    override fun after(block: Block, parent: Node): Iterable<IAstModification> {
        if("force_output" !in block.options()) {
            if (block.containsNoCodeNorVars) {
                if(block.name != internedStringsModuleName)
                    errors.warn("removing unused block '${block.name}'", block.position)
                return listOf(IAstModification.Remove(block, parent as IStatementContainer))
            }
            if(callgraph.unused(block)) {
                if(block.statements.any{ it !is VarDecl || it.type==VarDeclType.VAR})
                    errors.warn("removing unused block '${block.name}'", block.position)
                return listOf(IAstModification.Remove(block, parent as IStatementContainer))
            }
        }

        return deduplicateAssignments(block.statements, block)
    }

    override fun after(subroutine: Subroutine, parent: Node): Iterable<IAstModification> {
        val forceOutput = "force_output" in subroutine.definingBlock.options()
        if (subroutine !== program.entrypoint && !forceOutput && !subroutine.inline && !subroutine.isAsmSubroutine) {
            if(callgraph.unused(subroutine)) {
                if(subroutine.containsNoCodeNorVars) {
                    if(!subroutine.definingModule.isLibrary)
                        errors.warn("removing empty subroutine '${subroutine.name}'", subroutine.position)
                    val removals = mutableListOf(IAstModification.Remove(subroutine, parent as IStatementContainer))
                    callgraph.calledBy[subroutine]?.let {
                        for(node in it)
                            removals.add(IAstModification.Remove(node, node.parent as IStatementContainer))
                    }
                    return removals
                }
                if(!subroutine.definingModule.isLibrary)
                    errors.warn("removing unused subroutine '${subroutine.name}'", subroutine.position)
                return listOf(IAstModification.Remove(subroutine, parent as IStatementContainer))
            }
        }

        return deduplicateAssignments(subroutine.statements, subroutine)
    }

    override fun after(decl: VarDecl, parent: Node): Iterable<IAstModification> {
        if(decl.type==VarDeclType.VAR) {
            val forceOutput = "force_output" in decl.definingBlock.options()
            if (!forceOutput && decl.origin==VarDeclOrigin.USERCODE && !decl.sharedWithAsm && !decl.definingBlock.isInLibrary) {
                val usages = callgraph.usages(decl)
                if (usages.isEmpty()) {
                    errors.warn("removing unused variable '${decl.name}'", decl.position)
                    return listOf(IAstModification.Remove(decl, parent as IStatementContainer))
                }
                else {
                    if(usages.size==1) {
                        val singleUse = usages[0].parent
                        if(singleUse is AssignTarget) {
                            val assignment = singleUse.parent as Assignment
                            if(assignment.value !is IFunctionCall) {
                                errors.warn("removing unused variable '${decl.name}'", decl.position)
                                return listOf(
                                    IAstModification.Remove(decl, parent as IStatementContainer),
                                    IAstModification.Remove(assignment, assignment.parent as IStatementContainer)
                                )
                            }
                        }
                    }
                }
            }
        }

        return noModifications
    }

    private fun deduplicateAssignments(statements: List<Statement>, scope: IStatementContainer): List<IAstModification> {
        // removes 'duplicate' assignments that assign the same target directly after another, unless it is a function call
        val linesToRemove = mutableListOf<Assignment>()
        val modifications = mutableListOf<IAstModification>()

        fun substituteZeroInBinexpr(expr: BinaryExpression, zero: NumericLiteralValue, assign1: Assignment, assign2: Assignment) {
            if(expr.left isSameAs assign2.target) {
                // X = X <oper> Right
                linesToRemove.add(assign1)
                modifications.add(IAstModification.ReplaceNode(
                    expr.left, zero, expr
                ))
            }
            if(expr.right isSameAs assign2.target) {
                // X = Left <oper> X
                linesToRemove.add(assign1)
                modifications.add(IAstModification.ReplaceNode(
                    expr.right, zero, expr
                ))
            }
            val leftBinExpr = expr.left as? BinaryExpression
            val rightBinExpr = expr.right as? BinaryExpression
            if(leftBinExpr!=null && rightBinExpr==null) {
                if(leftBinExpr.left isSameAs assign2.target) {
                    // X = (X <oper> Right) <oper> Something
                    linesToRemove.add(assign1)
                    modifications.add(IAstModification.ReplaceNode(
                        leftBinExpr.left, zero, leftBinExpr
                    ))
                }
                if(leftBinExpr.right isSameAs assign2.target) {
                    // X = (Left <oper> X) <oper> Something
                    linesToRemove.add(assign1)
                    modifications.add(IAstModification.ReplaceNode(
                        leftBinExpr.right, zero, leftBinExpr
                    ))
                }
            }
            if(leftBinExpr==null && rightBinExpr!=null) {
                if(rightBinExpr.left isSameAs assign2.target) {
                    // X = Something <oper> (X <oper> Right)
                    linesToRemove.add(assign1)
                    modifications.add(IAstModification.ReplaceNode(
                        rightBinExpr.left, zero, rightBinExpr
                    ))
                }
                if(rightBinExpr.right isSameAs assign2.target) {
                    // X = Something <oper> (Left <oper> X)
                    linesToRemove.add(assign1)
                    modifications.add(IAstModification.ReplaceNode(
                        rightBinExpr.right, zero, rightBinExpr
                    ))
                }
            }
        }

        fun substituteZeroInPrefixexpr(expr: PrefixExpression, zero: NumericLiteralValue, assign1: Assignment, assign2: Assignment) {
            if(expr.expression isSameAs assign2.target) {
                linesToRemove.add(assign1)
                modifications.add(IAstModification.ReplaceNode(
                    expr.expression, zero, expr
                ))
            }
        }

        fun substituteZeroInTypecast(expr: TypecastExpression, zero: NumericLiteralValue, assign1: Assignment, assign2: Assignment) {
            if(expr.expression isSameAs assign2.target) {
                linesToRemove.add(assign1)
                modifications.add(IAstModification.ReplaceNode(
                    expr.expression, zero, expr
                ))
            }
            val subCast = expr.expression as? TypecastExpression
            if(subCast!=null && subCast.expression isSameAs assign2.target) {
                linesToRemove.add(assign1)
                modifications.add(IAstModification.ReplaceNode(
                    subCast.expression, zero, subCast
                ))
            }
        }

        for (stmtPairs in statements.windowed(2, step = 1)) {
            val assign1 = stmtPairs[0] as? Assignment
            val assign2 = stmtPairs[1] as? Assignment
            if (assign1 != null && assign2 != null) {
                val cvalue1 = assign1.value.constValue(program)
                if(cvalue1!=null && cvalue1.number==0.0 && assign2.target.isSameAs(assign1.target, program) && assign2.isAugmentable) {
                    val value2 = assign2.value
                    val zero = VarDecl.defaultZero(value2.inferType(program).getOr(DataType.UNDEFINED), value2.position)
                    when(value2) {
                        is BinaryExpression -> substituteZeroInBinexpr(value2, zero, assign1, assign2)
                        is PrefixExpression -> substituteZeroInPrefixexpr(value2, zero, assign1, assign2)
                        is TypecastExpression -> substituteZeroInTypecast(value2, zero, assign1, assign2)
                        else -> {}
                    }
                } else {
                    if (assign1.target.isSameAs(assign2.target, program) && !assign1.target.isIOAddress(compTarget.machine))  {
                        if(assign2.target.identifier==null || !assign2.value.referencesIdentifier(assign2.target.identifier!!.nameInSource))
                            // only remove the second assignment if its value is a simple expression!
                            when(assign2.value) {
                                is PrefixExpression,
                                is BinaryExpression,
                                is TypecastExpression,
                                is FunctionCallExpression -> { /* don't remove */ }
                                else -> {
                                    if(assign1.value !is IFunctionCall)
                                        linesToRemove.add(assign1)
                                }
                            }
                    }
                }
            }
        }

        return modifications + linesToRemove.map { IAstModification.Remove(it, scope) }
    }
}
