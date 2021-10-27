package prog8.ast

import prog8.ast.base.*
import prog8.ast.expressions.Expression
import prog8.ast.expressions.IdentifierReference
import prog8.ast.expressions.StringLiteralValue
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
    val position: Position
    val parent: Node
    val statements: MutableList<Statement>
    fun linkParents(parent: Node)

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

    fun isEmpty(): Boolean = statements.isEmpty()
    fun isNotEmpty(): Boolean = statements.isNotEmpty()

    fun searchLabelOrVariableNotSubscoped(name: String): Statement? {         // TODO return INamedStatement instead?  and rename to searchSymbol ?
        // this is called quite a lot and could perhaps be optimized a bit more,
        // but adding a memoization cache didn't make much of a practical runtime difference...
        for (stmt in statements) {
            when(stmt) {
//                is INamedStatement -> {
//                    if(stmt.name==name) return stmt
//                }
                is VarDecl -> {
                    if(stmt.name==name) return stmt
                }
                is Label -> {
                    if(stmt.name==name) return stmt
                }
                is AnonymousScope -> {
                    val found = stmt.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is IfStatement -> {
                    val found = stmt.truepart.searchLabelOrVariableNotSubscoped(name) ?: stmt.elsepart.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is BranchStatement -> {
                    val found = stmt.truepart.searchLabelOrVariableNotSubscoped(name) ?: stmt.elsepart.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is ForLoop -> {
                    val found = stmt.body.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is WhileLoop -> {
                    val found = stmt.body.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is RepeatLoop -> {
                    val found = stmt.body.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is UntilLoop -> {
                    val found = stmt.body.searchLabelOrVariableNotSubscoped(name)
                    if(found!=null)
                        return found
                }
                is WhenStatement -> {
                    stmt.choices.forEach {
                        val found = it.statements.searchLabelOrVariableNotSubscoped(name)
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

    val allDefinedSymbols: List<Pair<String, Statement>>
        get() {
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
}

interface INameScope: IStatementContainer, INamedStatement {
    fun subScope(name: String): INameScope?  = statements.firstOrNull { it is INameScope && it.name==name } as? INameScope

    fun lookup(scopedName: List<String>, localContext: Node) : Statement? {             // TODO return INamedStatement instead?
        if(scopedName.size>1) {
            // a scoped name refers to a name in another module.
            // it's a qualified name, look it up from the root of the module's namespace (consider all modules in the program)
            for(module in localContext.definingModule.program.modules) {
                var scope: INameScope? = module
                for(name in scopedName.dropLast(1)) {
                    scope = scope?.subScope(name)
                    if(scope==null)
                        break
                }
                if(scope!=null) {
                    val result = scope.searchLabelOrVariableNotSubscoped(scopedName.last())
                    if(result!=null)
                        return result
                    return scope.subScope(scopedName.last()) as Statement?
                }
            }
            return null
        } else {
            // unqualified name
            // find the scope the localContext is in, look in that first
            var statementScope = localContext
            while(statementScope !is ParentSentinel) {
                val localScope = statementScope.definingScope
                val result = localScope.searchLabelOrVariableNotSubscoped(scopedName[0])
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
}


/*********** Everything starts from here, the Program; zero or more modules *************/

class Program(val name: String,
              val builtinFunctions: IBuiltinFunctions,
              val memsizer: IMemSizer): Node {
    private val _modules = mutableListOf<Module>()

    val modules: List<Module> = _modules
    val namespace: GlobalNamespace = GlobalNamespace(modules, builtinFunctions.names)

    init {
        // insert a container module for all interned strings later
        val internedStringsModule = Module(mutableListOf(), Position.DUMMY, SourceCode.Generated(internedStringsModuleName))
        val block = Block(internedStringsModuleName, null, mutableListOf(), true, Position.DUMMY)
        internedStringsModule.statements.add(block)

        _modules.add(0, internedStringsModule)
        internedStringsModule.linkParents(namespace)
        internedStringsModule.program = this
    }

    fun addModule(module: Module): Program {
        require(null == _modules.firstOrNull { it.name == module.name })
            { "module '${module.name}' already present" }
        _modules.add(module)
        module.linkIntoProgram(this)
        return this
    }

    fun removeModule(module: Module) = _modules.remove(module)

    fun moveModuleToFront(module: Module): Program {
        require(_modules.contains(module))
            { "Not a module of this program: '${module.name}'"}
        _modules.remove(module)
        _modules.add(0, module)
        return this
    }

    val allBlocks: List<Block>
        get() = modules.flatMap { it.statements.filterIsInstance<Block>() }

    val entrypoint: Subroutine
        get() {
            val mainBlocks = allBlocks.filter { it.name == "main" }
            return when (mainBlocks.size) {
                0 -> throw FatalAstException("no 'main' block")
                1 -> mainBlocks[0].subScope("start") as Subroutine
                else -> throw FatalAstException("more than one 'main' block")
            }
        }

    val toplevelModule: Module
        get() = modules.first { it.name!=internedStringsModuleName }

    val definedLoadAddress: Int
        get() = toplevelModule.loadAddress

    var actualLoadAddress: Int = 0
    private val internedStringsUnique = mutableMapOf<Pair<String, Boolean>, List<String>>()

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
        val idx = _modules.indexOfFirst { it===node }
        _modules[idx] = replacement
        replacement.linkIntoProgram(this)
    }
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

    val loadAddress: Int by lazy {
        val address = (statements.singleOrNull { it is Directive && it.directive == "%address" } as? Directive)?.args?.single()?.int ?: 0
        address
    }

    override fun linkParents(parent: Node) {
        require(parent is GlobalNamespace)
        this.parent = parent
        statements.forEach {it.linkParents(this)}
    }

    fun linkIntoProgram(program: Program) {
        this.program = program
        linkParents(program.namespace)
    }

    override val definingScope: INameScope
        get() = program.namespace
    override fun replaceChildNode(node: Node, replacement: Node) {
        require(node is Statement && replacement is Statement)
        val idx = statements.indexOfFirst { it===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    override fun toString() = "Module(name=$name, pos=$position, lib=${isLibrary})"

    fun accept(visitor: IAstVisitor) = visitor.visit(this)
    fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    val isLibrary get() = source.isFromResources
}


class GlobalNamespace(val modules: Iterable<Module>, private val builtinFunctionNames: Set<String>): Node, INameScope {
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

    override fun lookup(scopedName: List<String>, localContext: Node): Statement? {         // TODO return INamedStatement instead?
        if (scopedName.size == 1 && scopedName[0] in builtinFunctionNames) {
            // builtin functions always exist, return a dummy localContext for them
            val builtinPlaceholder = Label("builtin::${scopedName.last()}", localContext.position)
            builtinPlaceholder.parent = ParentSentinel
            return builtinPlaceholder
        }

        // lookup something from the module.
        return when (val stmt = localContext.definingModule.lookup(scopedName, localContext)) {
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


