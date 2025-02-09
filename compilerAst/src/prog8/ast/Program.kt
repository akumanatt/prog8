package prog8.ast

import prog8.ast.base.DataType
import prog8.ast.base.FatalAstException
import prog8.ast.base.Position
import prog8.ast.base.VarDeclType
import prog8.ast.expressions.ContainmentCheck
import prog8.ast.expressions.StringLiteralValue
import prog8.ast.statements.*
import prog8.compilerinterface.Encoding
import prog8.compilerinterface.IMemSizer
import prog8.compilerinterface.IStringEncoding
import prog8.parser.SourceCode

/*********** Everything starts from here, the Program; zero or more modules *************/

class Program(val name: String,
              val builtinFunctions: IBuiltinFunctions,
              val memsizer: IMemSizer,
              val encoding: IStringEncoding
) {
    private val _modules = mutableListOf<Module>()

    val modules: List<Module> = _modules
    val namespace: GlobalNamespace = GlobalNamespace(modules)

    init {
        // insert a container module for all interned strings later
        val internedStringsModule =
            Module(mutableListOf(), Position.DUMMY, SourceCode.Generated(internedStringsModuleName))
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
        module.program = this
        module.linkParents(namespace)
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
        get() = modules.first { it.name!= internedStringsModuleName }

    val definedLoadAddress: UInt
        get() = toplevelModule.loadAddress

    var actualLoadAddress = 0u
    private val internedStringsUnique = mutableMapOf<Pair<String, Encoding>, List<String>>()

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
                VarDeclType.VAR, VarDeclOrigin.STRINGLITERAL, DataType.STR, ZeropageWish.NOT_IN_ZEROPAGE, null, varName, string,
                isArray = false, sharedWithAsm = false, subroutineParameter = null, position = string.position
            )
            internedStringsBlock.statements.add(decl)
            decl.linkParents(internedStringsBlock)
            return listOf(internedStringsModuleName, decl.name)
        }

        val key = Pair(string.value, string.encoding)
        val existing = internedStringsUnique[key]
        if (existing != null)
            return existing

        val scopedName = getScopedName(string)
        internedStringsUnique[key] = scopedName
        return scopedName
    }
}
