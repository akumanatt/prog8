package prog8.ast

import prog8.ast.base.*
import prog8.ast.expressions.*
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

interface INameScope {
    val name: String
    val position: Position
    val statements: MutableList<Statement>
    val parent: Node

    fun linkParents(parent: Node)

    fun subScope(name: String): INameScope? {
        for(stmt in statements) {
            when(stmt) {
                // NOTE: if other nodes are introduced that are a scope, or contain subscopes, they must be added here!
                is ForLoop -> if(stmt.body.name==name) return stmt.body
                is UntilLoop -> if(stmt.body.name==name) return stmt.body
                is WhileLoop -> if(stmt.body.name==name) return stmt.body
                is BranchStatement -> {
                    if(stmt.truepart.name==name) return stmt.truepart
                    if(stmt.elsepart.containsCodeOrVars() && stmt.elsepart.name==name) return stmt.elsepart
                }
                is IfStatement -> {
                    if(stmt.truepart.name==name) return stmt.truepart
                    if(stmt.elsepart.containsCodeOrVars() && stmt.elsepart.name==name) return stmt.elsepart
                }
                is WhenStatement -> {
                    val scope = stmt.choices.firstOrNull { it.statements.name==name }
                    if(scope!=null)
                        return scope.statements
                }
                is INameScope -> if(stmt.name==name) return stmt
                else -> {}
            }
        }
        return null
    }

    fun getLabelOrVariable(name: String): Statement? {
        // this is called A LOT and could perhaps be optimized a bit more,
        // but adding a memoization cache didn't make much of a practical runtime difference
        for (stmt in statements) {
            if (stmt is VarDecl && stmt.name==name) return stmt
            if (stmt is Label && stmt.name==name) return stmt
            if (stmt is AnonymousScope) {
                val sub = stmt.getLabelOrVariable(name)
                if(sub!=null)
                    return sub
            }
        }
        return null
    }

    fun allDefinedSymbols(): List<Pair<String, Statement>>  {
        return statements.mapNotNull {
            when (it) {
                is Label -> it.name to it
                is VarDecl -> it.name to it
                is Subroutine -> it.name to it
                is Block -> it.name to it
                else -> null
            }
        }
    }

    fun lookup(scopedName: List<String>, localContext: Node) : Statement? {
        if(scopedName.size>1) {
            // a scoped name refers to a name in another module.
            // it's a qualified name, look it up from the root of the module's namespace (consider all modules in the program)
            for(module in localContext.definingModule().program.modules) {
                var scope: INameScope? = module
                for(name in scopedName.dropLast(1)) {
                    scope = scope?.subScope(name)
                    if(scope==null)
                        break
                }
                if(scope!=null) {
                    val result = scope.getLabelOrVariable(scopedName.last())
                    if(result!=null)
                        return result
                    return scope.subScope(scopedName.last()) as Statement?
                }
            }
            return null
        } else {
            // unqualified name
            // special case: the do....until statement can also look INSIDE the anonymous scope
            if(localContext.parent.parent is UntilLoop) {
                val symbolFromInnerScope = (localContext.parent.parent as UntilLoop).body.getLabelOrVariable(scopedName[0])
                if(symbolFromInnerScope!=null)
                    return symbolFromInnerScope
            }

            // find the scope the localContext is in, look in that first
            var statementScope = localContext
            while(statementScope !is ParentSentinel) {
                val localScope = statementScope.definingScope()
                val result = localScope.getLabelOrVariable(scopedName[0])
                if (result != null)
                    return result
                val subscope = localScope.subScope(scopedName[0]) as Statement?
                if (subscope != null)
                    return subscope
                // not found in this scope, look one higher up
                statementScope = statementScope.parent
            }
            return null
        }
    }

    fun containsCodeOrVars() = statements.any { it !is Directive || it.directive == "%asminclude" || it.directive == "%asm"}
    fun containsNoCodeNorVars() = !containsCodeOrVars()

    fun remove(stmt: Statement) {
        if(!statements.remove(stmt))
            throw FatalAstException("stmt to remove wasn't found in scope")
    }

    fun getAllLabels(label: String): List<Label> {
        val result = mutableListOf<Label>()

        fun find(scope: INameScope) {
            scope.statements.forEach {
                when(it) {
                    is Label -> result.add(it)
                    is INameScope -> find(it)
                    is IfStatement -> {
                        find(it.truepart)
                        find(it.elsepart)
                    }
                    is UntilLoop -> find(it.body)
                    is RepeatLoop -> find(it.body)
                    is WhileLoop -> find(it.body)
                    is WhenStatement -> it.choices.forEach { choice->find(choice.statements) }
                    else -> { /* do nothing */ }
                }
            }
        }

        find(this)
        return result
    }

    fun nextSibling(stmt: Statement): Statement? {
        val nextIdx = statements.indexOfFirst { it===stmt } + 1
        return if(nextIdx < statements.size)
            statements[nextIdx]
        else
            null
    }

    fun previousSibling(stmt: Statement): Statement? {
        val previousIdx = statements.indexOfFirst { it===stmt } - 1
        return if(previousIdx>=0)
            statements[previousIdx]
        else
            null
    }

    fun indexOfChild(stmt: Statement): Int {
        val idx = statements.indexOfFirst { it===stmt }
        if(idx>=0)
            return idx
        else
            throw FatalAstException("attempt to find a non-child")
    }
}


interface Node {
    val position: Position
    var parent: Node             // will be linked correctly later (late init)
    fun linkParents(parent: Node)

    fun definingModule(): Module {
        if(this is Module)
            return this
        return findParentNode<Module>(this)!!
    }

    fun definingSubroutine(): Subroutine?  = findParentNode<Subroutine>(this)

    fun definingScope(): INameScope {
        val scope = findParentNode<INameScope>(this)
        if(scope!=null) {
            return scope
        }
        if(this is Label && this.name.startsWith("builtin::")) {
            return BuiltinFunctionScopePlaceholder
        }
        if(this is GlobalNamespace)
            return this
        throw FatalAstException("scope missing from $this")
    }

    fun definingBlock(): Block {
        if(this is Block)
            return this
        return findParentNode<Block>(this)!!
    }

    fun containingStatement(): Statement {
        if(this is Statement)
            return this
        return findParentNode<Statement>(this)!!
    }

    fun replaceChildNode(node: Node, replacement: Node)
}


/*********** Everything starts from here, the Program; zero or more modules *************/

class Program(val name: String,
              val modules: MutableList<Module>,
              val builtinFunctions: IBuiltinFunctions,
              val memsizer: IMemSizer): Node {
    val namespace = GlobalNamespace(modules, builtinFunctions.names)

    val mainModule: Module
        get() = modules.first { it.name!=internedStringsModuleName }
    val definedLoadAddress: Int
        get() = mainModule.loadAddress

    var actualLoadAddress: Int = 0
    private val internedStringsUnique = mutableMapOf<Pair<String, Boolean>, List<String>>()

    init {
        // insert a container module for all interned strings later
        if(modules.firstOrNull()?.name != internedStringsModuleName) {
            val internedStringsModule = Module(internedStringsModuleName, mutableListOf(), Position.DUMMY, null)
            modules.add(0, internedStringsModule)
            val block = Block(internedStringsModuleName, null, mutableListOf(), true, Position.DUMMY)
            internedStringsModule.statements.add(block)
            internedStringsModule.linkParents(this)
            internedStringsModule.program = this
        }
    }

    fun entrypoint(): Subroutine {
        val mainBlocks = allBlocks().filter { it.name=="main" }
        if(mainBlocks.size > 1)
            throw FatalAstException("more than one 'main' block")
        if(mainBlocks.isEmpty())
            throw FatalAstException("no 'main' block")
        return mainBlocks[0].subScope("start") as Subroutine
    }

    fun internString(string: StringLiteralValue): List<String> {
        // Move a string literal into the internal, deduplicated, string pool
        // replace it with a variable declaration that points to the entry in the pool.

        if(string.parent is VarDecl) {
            // deduplication can only be performed safely for known-const strings (=string literals OUTSIDE OF A VARDECL)!
            throw FatalAstException("cannot intern a string literal that's part of a vardecl")
        }

        fun getScopedName(string: StringLiteralValue): List<String> {
            val internedStringsBlock = modules
                .first { it.name == internedStringsModuleName }.statements
                .first { it is Block && it.name == internedStringsModuleName } as Block
            val varName = "string_${internedStringsBlock.statements.size}"
            val decl = VarDecl(
                VarDeclType.VAR, DataType.STR, ZeropageWish.NOT_IN_ZEROPAGE, null, varName, string,
                isArray = false, autogeneratedDontRemove = true, sharedWithAsm = false, position = string.position
            )
            internedStringsBlock.statements.add(decl)
            decl.linkParents(internedStringsBlock)
            return listOf(internedStringsModuleName, decl.name)
        }

        val key = Pair(string.value, string.altEncoding)
        val existing = internedStringsUnique[key]
        if (existing != null)
            return existing

        val scopedName = getScopedName(string)
        internedStringsUnique[key] = scopedName
        return scopedName
    }



    fun allBlocks(): List<Block> = modules.flatMap { it.statements.filterIsInstance<Block>() }

    override val position: Position = Position.DUMMY
    override var parent: Node
        get() = throw FatalAstException("program has no parent")
        set(_) = throw FatalAstException("can't set parent of program")

    override fun linkParents(parent: Node) {
        modules.forEach {
            it.linkParents(namespace)
        }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(node is Module && replacement is Module)
        val idx = modules.indexOfFirst { it===node }
        modules[idx] = replacement
        replacement.parent = this
    }
}

open class Module(override val name: String,
             override var statements: MutableList<Statement>,
             override val position: Position,
             val source: SourceCode?) : Node, INameScope {

    override lateinit var parent: Node
    lateinit var program: Program

    val loadAddress: Int by lazy {
        val address = (statements.singleOrNull { it is Directive && it.directive == "%address" } as? Directive)?.args?.single()?.int ?: 0
        address
    }

    override fun linkParents(parent: Node) {
        this.parent = parent
        statements.forEach {it.linkParents(this)}
    }

    override fun definingScope(): INameScope = program.namespace
    override fun replaceChildNode(node: Node, replacement: Node) {
        require(node is Statement && replacement is Statement)
        val idx = statements.indexOfFirst { it===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    override fun toString() = "Module(name=$name, pos=$position, lib=${isLibrary()})"

    fun accept(visitor: IAstVisitor) = visitor.visit(this)
    fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    fun isLibrary() = (source == null) || source.isFromResources
}


class GlobalNamespace(val modules: List<Module>, private val builtinFunctionNames: Set<String>): Node, INameScope {
    override val name = "<<<global>>>"
    override val position = Position("<<<global>>>", 0, 0, 0)
    override val statements = mutableListOf<Statement>()        // not used
    override var parent: Node = ParentSentinel

    override fun linkParents(parent: Node) {
        modules.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        throw FatalAstException("cannot replace anything in the namespace")
    }

    override fun lookup(scopedName: List<String>, localContext: Node): Statement? {
        if (scopedName.size == 1 && scopedName[0] in builtinFunctionNames) {
            // builtin functions always exist, return a dummy localContext for them
            val builtinPlaceholder = Label("builtin::${scopedName.last()}", localContext.position)
            builtinPlaceholder.parent = ParentSentinel
            return builtinPlaceholder
        }

        // special case: the do....until statement can also look INSIDE the anonymous scope
        if(localContext.parent.parent is UntilLoop) {
            val symbolFromInnerScope = (localContext.parent.parent as UntilLoop).body.lookup(scopedName, localContext)
            if(symbolFromInnerScope!=null)
                return symbolFromInnerScope
        }

        // lookup something from the module.
        return when (val stmt = localContext.definingModule().lookup(scopedName, localContext)) {
            is Label, is VarDecl, is Block, is Subroutine -> stmt
            null -> null
            else -> throw SyntaxError("invalid identifier target type", stmt.position)
        }
    }
}

object BuiltinFunctionScopePlaceholder : INameScope {
    override val name = "<<builtin-functions-scope-placeholder>>"
    override val position = Position("<<placeholder>>", 0, 0, 0)
    override var statements = mutableListOf<Statement>()
    override var parent: Node = ParentSentinel
    override fun linkParents(parent: Node) {}
}


