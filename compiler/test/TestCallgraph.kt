package prog8tests

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.ast.Program
import prog8.ast.statements.Block
import prog8.ast.statements.Subroutine
import prog8.codegen.target.C64Target
import prog8.compilerinterface.CallGraph
import prog8.parser.Prog8Parser.parseModule
import prog8.parser.SourceCode
import prog8tests.helpers.*

class TestCallgraph: FunSpec({
    test("testGraphForEmptySubs") {
        val sourcecode = """
            %import string
            main {
                sub start() {
                }
                sub empty() {
                }
            }
        """
        val result = compileText(C64Target, false, sourcecode).assertSuccess()
        val graph = CallGraph(result.program)

        graph.imports.size shouldBe 1
        graph.importedBy.size shouldBe 1
        val toplevelModule = result.program.toplevelModule
        val importedModule = graph.imports.getValue(toplevelModule).single()
        importedModule.name shouldBe "string"
        val importedBy = graph.importedBy.getValue(importedModule).single()
        importedBy.name.startsWith("on_the_fly_test") shouldBe true

        graph.unused(toplevelModule) shouldBe false
        graph.unused(importedModule) shouldBe false

        val mainBlock = toplevelModule.statements.filterIsInstance<Block>().single()
        for(stmt in mainBlock.statements) {
            val sub = stmt as Subroutine
            graph.calls shouldNotContainKey sub
            graph.calledBy shouldNotContainKey sub

            if(sub === result.program.entrypoint)
                withClue("start() should always be marked as used to avoid having it removed") {
                    graph.unused(sub) shouldBe false
                }
            else
                graph.unused(sub) shouldBe true
        }
    }

    test("reference to empty sub") {
        val sourcecode = """
            %import string
            main {
                sub start() {
                    uword xx = &empty
                    xx++
                }
                sub empty() {
                }
            }
        """
        val result = compileText(C64Target, false, sourcecode).assertSuccess()
        val graph = CallGraph(result.program)

        graph.imports.size shouldBe 1
        graph.importedBy.size shouldBe 1
        val toplevelModule = result.program.toplevelModule
        val importedModule = graph.imports.getValue(toplevelModule).single()
        importedModule.name shouldBe "string"
        val importedBy = graph.importedBy.getValue(importedModule).single()
        importedBy.name.startsWith("on_the_fly_test") shouldBe true

        graph.unused(toplevelModule) shouldBe false
        graph.unused(importedModule) shouldBe false

        val mainBlock = toplevelModule.statements.filterIsInstance<Block>().single()
        val startSub = mainBlock.statements.filterIsInstance<Subroutine>().single{it.name=="start"}
        val emptySub = mainBlock.statements.filterIsInstance<Subroutine>().single{it.name=="empty"}

        graph.calls shouldNotContainKey startSub
        graph.calledBy shouldNotContainKey emptySub
        withClue("empty doesn't call anything") {
            graph.calls shouldNotContainKey emptySub
        }
        withClue( "start doesn't get called (except as entrypoint ofc.)") {
            graph.calledBy shouldNotContainKey startSub
        }
    }

    test("allIdentifiers separates for different positions of the IdentifierReferences") {
        val sourcecode = """
            main {
                sub start() {
                    uword x1 = &empty
                    uword x2 = &empty
                    empty()
                }
                sub empty() {
                    %asm {{
                        nop
                    }}
                }
            }
        """
        val result = compileText(C64Target, false, sourcecode).assertSuccess()
        val graph = CallGraph(result.program)
        graph.allIdentifiers.size shouldBeGreaterThanOrEqual 9
        val empties = graph.allIdentifiers.keys.filter { it.nameInSource==listOf("empty") }
        empties.size shouldBe 3
        empties[0].position.line shouldBe 4
        empties[1].position.line shouldBe 5
        empties[2].position.line shouldBe 6
    }

    test("checking block and subroutine names usage in assembly code") {
        val source = """
            main {
                sub start() {
                    %asm {{
                        lda  #<blockname
                        lda  #<blockname.subroutine
            correctlabel:
                        nop
                    }}
                }
            
            }
            
            blockname {
                sub subroutine() {
                    @(1000) = 0
                }
            
                sub correctlabel() {
                    @(1000) = 0
                }
            }
            
            ; all block and subroutines below should NOT be found in asm because they're only substrings of the names in there
            locknam {
                sub rout() {
                    @(1000) = 0
                }
            
                sub orrectlab() {
                    @(1000) = 0
                }
            }"""
        val module = parseModule(SourceCode.Text(source))
        val program = Program("test", DummyFunctions, DummyMemsizer, DummyStringEncoder)
        program.addModule(module)
        val callgraph = CallGraph(program)
        val blockMain = program.allBlocks.single { it.name=="main" }
        val blockBlockname = program.allBlocks.single { it.name=="blockname" }
        val blockLocknam = program.allBlocks.single { it.name=="locknam" }
        val subStart = blockMain.statements.filterIsInstance<Subroutine>().single { it.name == "start" }
        val subSubroutine = blockBlockname.statements.filterIsInstance<Subroutine>().single { it.name == "subroutine" }
        val subCorrectlabel = blockBlockname.statements.filterIsInstance<Subroutine>().single { it.name == "correctlabel" }
        val subRout = blockLocknam.statements.filterIsInstance<Subroutine>().single { it.name == "rout" }
        val subOrrectlab = blockLocknam.statements.filterIsInstance<Subroutine>().single { it.name == "orrectlab" }
        callgraph.unused(blockMain) shouldBe false
        callgraph.unused(blockBlockname) shouldBe false
        callgraph.unused(blockLocknam) shouldBe true
        callgraph.unused(subStart) shouldBe false
        callgraph.unused(subSubroutine) shouldBe false
        callgraph.unused(subCorrectlabel) shouldBe false
        callgraph.unused(subRout) shouldBe true
        callgraph.unused(subOrrectlab) shouldBe true
    }

    test("recursion detection") {
        val source="""
            main {
                sub start() {
                    recurse1()
                }
                sub recurse1() {
                    recurse2()
                }
                sub recurse2() {
                    start()
                }
            }"""
        val module = parseModule(SourceCode.Text(source))
        val program = Program("test", DummyFunctions, DummyMemsizer, DummyStringEncoder)
        program.addModule(module)
        val callgraph = CallGraph(program)
        val errors = ErrorReporterForTests()
        callgraph.checkRecursiveCalls(errors)
        errors.errors.size shouldBe 0
        errors.warnings.size shouldBe 4
        errors.warnings[0] shouldContain "contains recursive subroutine calls"
        errors.warnings[1] shouldContain "start at"
        errors.warnings[2] shouldContain "recurse1 at"
        errors.warnings[3] shouldContain "recurse2 at"
    }

    test("no recursion warning if reference isn't a call") {
        val source="""
            main {
                sub start() {
                    recurse1()
                }
                sub recurse1() {
                    recurse2()
                }
                sub recurse2() {
                    uword @shared address = &start
                }
            }"""
        val module = parseModule(SourceCode.Text(source))
        val program = Program("test", DummyFunctions, DummyMemsizer, DummyStringEncoder)
        program.addModule(module)
        val callgraph = CallGraph(program)
        val errors = ErrorReporterForTests()
        callgraph.checkRecursiveCalls(errors)
        errors.errors.size shouldBe 0
        errors.warnings.size shouldBe 0
    }
})
