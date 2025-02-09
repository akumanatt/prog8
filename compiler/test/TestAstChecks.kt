package prog8tests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.codegen.target.C64Target
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.assertFailure
import prog8tests.helpers.assertSuccess
import prog8tests.helpers.compileText


class TestAstChecks: FunSpec({

    test("conditional expression w/float works even without tempvar to split it") {
        val text = """
            %import floats
            main {
                sub start() {
                    uword xx
                    if xx+99.99 == xx+1.234 {
                        xx++
                    }
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(C64Target, true, text, writeAssembly = true, errors=errors).assertSuccess()
        errors.errors.size shouldBe 0
        errors.warnings.size shouldBe 2
        errors.warnings[0] shouldContain "converted to float"
        errors.warnings[1] shouldContain "converted to float"
    }

    test("can't assign label or subroutine without using address-of") {
        val text = """
            main {
                sub start() {
            
            label:
                    uword @shared addr
                    addr = label
                    addr = thing
                    addr = &label
                    addr = &thing
                }
            
                sub thing() {
                }
            }
            """
        val errors = ErrorReporterForTests()
        compileText(C64Target, true, text, writeAssembly = true, errors=errors).assertFailure()
        errors.errors.size shouldBe 2
        errors.warnings.size shouldBe 0
        errors.errors[0] shouldContain ":7:28) assignment value is invalid"
        errors.errors[1] shouldContain ":8:28) assignment value is invalid"
    }

    test("can't do str or array expression without using address-of") {
        val text = """
            %import textio
            main {
                sub start() {
                    ubyte[] array = [1,2,3,4]
                    str s1 = "test"
                    ubyte ff = 1
                    txt.print(s1+ff)
                    txt.print(array+ff)
                    txt.print_uwhex(s1+ff, true)
                    txt.print_uwhex(array+ff, true)
                }
            }
            """
        val errors = ErrorReporterForTests()
        compileText(C64Target, false, text, writeAssembly = false, errors=errors).assertFailure()
        errors.errors.filter { it.contains("missing &") }.size shouldBe 4
    }

    test("str or array expression with address-of") {
        val text = """
            %import textio
            main {
                sub start() {
                    ubyte[] array = [1,2,3,4]
                    str s1 = "test"
                    ubyte ff = 1
                    txt.print(&s1+ff)
                    txt.print(&array+ff)
                    txt.print_uwhex(&s1+ff, true)
                    txt.print_uwhex(&array+ff, true)
                    ; also good:
                    ff = (s1 == "derp")
                    ff = (s1 != "derp")
                }
            }
            """
        compileText(C64Target, false, text, writeAssembly = false).assertSuccess()
    }
})
