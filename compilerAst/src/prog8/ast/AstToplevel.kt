package prog8.ast

import prog8.ast.base.*
import prog8.ast.expressions.Expression
import prog8.ast.expressions.IdentifierReference
import prog8.ast.statements.*
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstVisitor
import prog8.parser.SourceCode

const val internedStringsModuleName = "prog8_interned_strings"


interface IAssignable {
    // just a tag for now
}

interface IFunctionCall {
    var target: IdentifierReference
    var args: MutableList<Expression>
}

interface IStatementContainer {
    val statements: MutableList<Statement>

    fun remove(stmt: Statement) {
        if(!statements.remove(stmt))
            throw FatalAstException("stmt to remove wasn't found in scope")
    }

    fun getAllLabels(label: String): List<Label> {
        val result = mutableListOf<Label>()

        fun find(scope: IStatementContainer) {
            scope.statements.forEach {
                when(it) {
                    is Label -> result.add(it)
                    is IStatementContainer -> find(it)
                    is IfElse -> {
                        find(it.truepart)
                        find(it.elsepart)
                    }
                    is UntilLoop -> find(it.body)
                    is RepeatLoop -> find(it.body)
                    is WhileLoop -> find(it.body)
                    is When -> it.choices.forEach { choice->find(choice.statements) }
                    else -> { /* do nothing */ }
                }
            }
        }

        find(this)
        return result
    }

    fun indexOfChild(stmt: Statement): Int {
        val idx = statements.indexOfFirst { it===stmt }
        if(idx>=0)
            return idx
        else
            throw FatalAstException("attempt to find a non-child")
    }

    fun isEmpty(): Boolean = statements.isEmpty()
    fun isNotEmpty(): Boolean = statements.isNotEmpty()

    fun searchSymbol(name: String): Statement? {
        if(this is Subroutine && isAsmSubroutine)
            return searchAsmParameter(name)

        // this is called quite a lot and could perhaps be optimized a bit more,
        // but adding a memoization cache didn't make much of a practical runtime difference...
        for (stmt in statements) {
            when(stmt) {
//                is INamedStatement -> {
//                    if(stmt.name==name) return stmt
//                }
                is VarDecl -> if(stmt.name==name) return stmt
                is Label -> if(stmt.name==name) return stmt
                is Subroutine -> if(stmt.name==name) return stmt
                is Block -> if(stmt.name==name) return stmt
                is AnonymousScope -> {
                    val found = stmt.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is IfElse -> {
                    val found = stmt.truepart.searchSymbol(name) ?: stmt.elsepart.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is ConditionalBranch -> {
                    val found = stmt.truepart.searchSymbol(name) ?: stmt.elsepart.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is ForLoop -> {
                    val found = stmt.body.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is WhileLoop -> {
                    val found = stmt.body.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is RepeatLoop -> {
                    val found = stmt.body.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is UntilLoop -> {
                    val found = stmt.body.searchSymbol(name)
                    if(found!=null)
                        return found
                }
                is When -> {
                    stmt.choices.forEach {
                        val found = it.statements.searchSymbol(name)
                        if(found!=null)
                            return found
                    }
                }
                else -> {
                    // NOTE: when other nodes containing AnonymousScope are introduced,
                    //       these should be added here as well to look into!
                }
            }
        }
        return null
    }

    val allDefinedSymbols: Sequence<Pair<String, Statement>>
        get() {
            return statements.asSequence().filterIsInstance<INamedStatement>().map { Pair(it.name, it as Statement) }
        }
}

interface INameScope: IStatementContainer, INamedStatement {
    fun subScope(name: String): INameScope?  = statements.firstOrNull { it is INameScope && it.name==name } as? INameScope

    fun lookup(scopedName: List<String>) : Statement? {
        return if(scopedName.size>1)
            lookupQualified(scopedName)
        else {
            lookupUnqualified(scopedName[0])
        }
    }

    private fun lookupQualified(scopedName: List<String>): Statement? {
        // a scoped name refers to a name in another namespace, and stars from the root.
        for(module in (this as Node).definingModule.program.modules) {
            val block = module.searchSymbol(scopedName[0])
            if(block!=null) {
                var statement = block
                for(name in scopedName.drop(1)) {
                    statement = (statement as? IStatementContainer)?.searchSymbol(name)
                    if(statement==null)
                        return null
                }
                return statement
            }
        }
        return null
    }

    private fun lookupUnqualified(name: String): Statement? {
        val builtinFunctionsNames = (this as Node).definingModule.program.builtinFunctions.names
        if(name in builtinFunctionsNames) {
            // builtin functions always exist, return a dummy placeholder for them
            val builtinPlaceholder = Label("builtin::$name", this.position)
            builtinPlaceholder.parent = ParentSentinel
            return builtinPlaceholder
        }

        // search for the unqualified name in the current scope (and possibly in any anonymousscopes it may contain)
        var statementScope = this
        while(statementScope !is GlobalNamespace) {
            val symbol = statementScope.searchSymbol(name)
            if(symbol!=null)
                return symbol
            else
                statementScope = (statementScope as Node).definingScope
        }
        return null
    }

//    private fun getNamedSymbol(name: String): Statement? =
//        statements.singleOrNull { it is INamedStatement && it.name==name }

    val containsCodeOrVars get() = statements.any { it !is Directive || it.directive == "%asminclude" || it.directive == "%asm" }
    val containsNoCodeNorVars get() = !containsCodeOrVars
}


interface Node {
    val position: Position
    var parent: Node             // will be linked correctly later (late init)
    fun linkParents(parent: Node)

    val definingModule: Module
        get() {
            if (this is Module)
                return this
            return findParentNode<Module>(this)!!
        }

    val definingSubroutine: Subroutine? get() = findParentNode<Subroutine>(this)

    val definingScope: INameScope
        get() {
            val scope = findParentNode<INameScope>(this)
            if (scope != null) {
                return scope
            }
            if (this is Label && this.name.startsWith("builtin::")) {
                return BuiltinFunctionScopePlaceholder
            }
            if (this is GlobalNamespace)
                return this
            throw FatalAstException("scope missing from $this")
        }

    val definingBlock: Block
        get() {
            if (this is Block)
                return this
            return findParentNode<Block>(this)!!
        }

    val containingStatement: Statement
        get() {
            if (this is Statement)
                return this
            return findParentNode<Statement>(this)!!
        }

    fun replaceChildNode(node: Node, replacement: Node)
    fun copy(): Node
}


open class Module(final override var statements: MutableList<Statement>,
                  final override val position: Position,
                  val source: SourceCode) : Node, INameScope {

    override lateinit var parent: Node
    lateinit var program: Program

    override val name = source.origin
            .substringBeforeLast(".")
            .substringAfterLast("/")
            .substringAfterLast("\\")

    val loadAddress: UInt by lazy {
        val address = (statements.singleOrNull { it is Directive && it.directive == "%address" } as? Directive)?.args?.single()?.int ?: 0u
        address
    }

    override fun linkParents(parent: Node) {
        require(parent is GlobalNamespace)
        this.parent = parent
        statements.forEach {it.linkParents(this)}
    }

    override val definingScope: INameScope
        get() = program.namespace
    override fun replaceChildNode(node: Node, replacement: Node) {
        require(node is Statement && replacement is Statement)
        val idx = statements.indexOfFirst { it===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    override fun copy(): Node = throw NotImplementedError("no support for duplicating a Module")

    override fun toString() = "Module(name=$name, pos=$position, lib=${isLibrary})"

    fun accept(visitor: IAstVisitor) = visitor.visit(this)
    fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    val isLibrary get() = source.isFromResources
}


class GlobalNamespace(val modules: Iterable<Module>): Node, INameScope {
    override val name = "<<<global>>>"
    override val position = Position("<<<global>>>", 0, 0, 0)
    override val statements = mutableListOf<Statement>()        // not used
    override var parent: Node = ParentSentinel

    override fun copy(): Node = throw NotImplementedError("no support for duplicating a GlobalNamespace")

    override fun lookup(scopedName: List<String>): Statement? {
        throw NotImplementedError("use lookup on actual ast node instead")
    }

    override fun linkParents(parent: Node) {
        modules.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        throw FatalAstException("cannot replace anything in the namespace")
    }
}

internal object BuiltinFunctionScopePlaceholder : INameScope {
    override val name = "<<builtin-functions-scope-placeholder>>"
    override var statements = mutableListOf<Statement>()
}
