package prog8tests

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.instanceOf
import prog8.ast.base.DataType
import prog8.ast.base.Position
import prog8.ast.expressions.*
import prog8.ast.statements.ForLoop
import prog8.ast.statements.VarDecl
import prog8.codegen.target.C64Target
import prog8.codegen.target.Cx16Target
import prog8.compilerinterface.Encoding
import prog8tests.helpers.*
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.assertFailure
import prog8tests.helpers.assertSuccess
import prog8tests.helpers.compileText


/**
 * ATTENTION: this is just kludge!
 * They are not really unit tests, but rather tests of the whole process,
 * from source file loading all the way through to running 64tass.
 */
class TestCompilerOnRanges: FunSpec({

    test("testUByteArrayInitializerWithRange_char_to_char") {
        val platform = Cx16Target
        val result = compileText(platform, false, """
            main {
                sub start() {
                    ubyte[] cs = @'a' to 'z' ; values are computed at compile time 
                    cs[0] = 23 ; keep optimizer from removing it
                }
            }
        """).assertSuccess()

        val program = result.program
        val startSub = program.entrypoint
        val decl = startSub
            .statements.filterIsInstance<VarDecl>()[0]
        val rhsValues = (decl.value as ArrayLiteralValue)
            .value // Array<Expression>
            .map { (it as NumericLiteralValue).number.toInt() }
        val expectedStart = platform.encodeString("a", Encoding.SCREENCODES)[0].toInt()
        val expectedEnd = platform.encodeString("z", Encoding.PETSCII)[0].toInt()
        val expectedStr = "$expectedStart .. $expectedEnd"

        val actualStr = "${rhsValues.first()} .. ${rhsValues.last()}"
        withClue(".first .. .last") {
            actualStr shouldBe expectedStr
        }
        withClue("rangeExpr.size()") {
            (rhsValues.last() - rhsValues.first() + 1) shouldBe (expectedEnd - expectedStart + 1)
        }
    }

    test("testFloatArrayInitializerWithRange_char_to_char") {
        val platform = C64Target
        val result = compileText(platform, optimize = false, """
            %import floats
            main {
                sub start() {
                    float[] cs = 'a' to 'z' ; values are computed at compile time 
                    cs[0] = 23 ; keep optimizer from removing it
                }
            }
        """).assertSuccess()

        val program = result.program
        val startSub = program.entrypoint
        val decl = startSub
            .statements.filterIsInstance<VarDecl>()[0]
        val rhsValues = (decl.value as ArrayLiteralValue)
            .value // Array<Expression>
            .map { (it as NumericLiteralValue).number.toInt() }
        val expectedStart = platform.encodeString("a", Encoding.PETSCII)[0].toInt()
        val expectedEnd = platform.encodeString("z", Encoding.PETSCII)[0].toInt()
        val expectedStr = "$expectedStart .. $expectedEnd"

        val actualStr = "${rhsValues.first()} .. ${rhsValues.last()}"
        withClue(".first .. .last") {
            actualStr shouldBe expectedStr
        }
        withClue("rangeExpr.size()") {
            rhsValues.size shouldBe (expectedEnd - expectedStart + 1)
        }
    }

    context("floatArrayInitializerWithRange") {
        val combos = cartesianProduct(
            listOf("", "42", "41"),                 // sizeInDecl
            listOf("%import floats", ""),           // optEnableFloats
            listOf(Cx16Target, C64Target),          // platform
            listOf(false, true)                     // optimize
        )

        combos.forEach {
            val (sizeInDecl, optEnableFloats, platform, optimize) = it
            val displayName =
                when (sizeInDecl) {
                    "" -> "no"
                    "42" -> "correct"
                    else -> "wrong"
                } + " array size given" +
                        ", " + (if (optEnableFloats == "") "without" else "with") + " %option enable_floats" +
                        ", ${platform.name}, optimize: $optimize"

            test(displayName) {
                val result = compileText(platform, optimize, """
                    $optEnableFloats
                    main {
                        sub start() {
                            float[$sizeInDecl] cs = 1 to 42 ; values are computed at compile time
                            cs[0] = 23 ; keep optimizer from removing it
                        }
                    }
                """)
                if (optEnableFloats != "" && (sizeInDecl=="" || sizeInDecl=="42"))
                    result.assertSuccess()
                else
                    result.assertFailure()

            }
        }
    }

    test("testForLoopWithRange_char_to_char") {
        val platform = Cx16Target
        val result = compileText(platform, optimize = true, """
            main {
                sub start() {
                    ubyte i
                    for i in @'a' to 'f' {
                        i += i ; keep optimizer from removing it
                    }
                }
            }
        """).assertSuccess()

        val program = result.program
        val startSub = program.entrypoint
        val iterable = startSub
            .statements.filterIsInstance<ForLoop>()
            .map { it.iterable }[0]
        val rangeExpr = iterable as RangeExpression

        val expectedStart = platform.encodeString("a", Encoding.SCREENCODES)[0].toInt()
        val expectedEnd = platform.encodeString("f", Encoding.PETSCII)[0].toInt()
        val expectedStr = "$expectedStart .. $expectedEnd"

        val intProgression = rangeExpr.toConstantIntegerRange()
        val actualStr = "${intProgression?.first} .. ${intProgression?.last}"
        withClue(".first .. .last") {
            actualStr shouldBe expectedStr
        }
        withClue("rangeExpr.size()") {
            rangeExpr.size() shouldBe (expectedEnd - expectedStart + 1)
        }
    }

    test("testForLoopWithRange_bool_to_bool") {
        val platform = Cx16Target
        val result = compileText(platform, optimize = true, """
            main {
                sub start() {
                    ubyte i
                    for i in false to true {
                        i += i ; keep optimizer from removing it
                    }
                }
            }
        """).assertSuccess()

        val program = result.program
        val startSub = program.entrypoint
        val rangeExpr = startSub
            .statements.filterIsInstance<ForLoop>()
            .map { it.iterable }
            .filterIsInstance<RangeExpression>()[0]

        rangeExpr.size() shouldBe 2
        val intProgression = rangeExpr.toConstantIntegerRange()
        intProgression?.first shouldBe 0
        intProgression?.last shouldBe 1
    }

    test("testForLoopWithRange_ubyte_to_ubyte") {
        val platform = Cx16Target
        val result = compileText(platform, optimize = true, """
            main {
                sub start() {
                    ubyte i
                    for i in 1 to 9 {
                        i += i ; keep optimizer from removing it
                    }
                }
            }
        """).assertSuccess()

        val program = result.program
        val startSub = program.entrypoint
        val rangeExpr = startSub
            .statements.filterIsInstance<ForLoop>()
            .map { it.iterable }
            .filterIsInstance<RangeExpression>()[0]

        rangeExpr.size() shouldBe 9
        val intProgression = rangeExpr.toConstantIntegerRange()
        intProgression?.first shouldBe 1
        intProgression?.last shouldBe 9
    }

    test("testForLoopWithRange_str_downto_str") {
        val errors = ErrorReporterForTests()
        compileText(Cx16Target, true, """
            main {
                sub start() {
                    ubyte i
                    for i in "start" downto "end" {
                        i += i ; keep optimizer from removing it
                    }
                }
            }
        """, errors, false).assertFailure()
        errors.errors.size shouldBe 2
        errors.errors[0] shouldContain ".p8:5:30) range expression from value must be integer"
        errors.errors[1] shouldContain ".p8:5:45) range expression to value must be integer"
    }

    test("testForLoopWithIterable_str") {
        val result = compileText(Cx16Target, false, """
            main {
                sub start() {
                    ubyte i
                    for i in "something" {
                        i += i ; keep optimizer from removing it
                    }
                }
            }
        """).assertSuccess()

        val program = result.program
        val startSub = program.entrypoint
        val iterable = startSub
            .statements.filterIsInstance<ForLoop>()
            .map { it.iterable }
            .filterIsInstance<IdentifierReference>()[0]

        iterable.inferType(program).getOr(DataType.UNDEFINED) shouldBe DataType.STR
    }

    test("testRangeExprNumericSize") {
        val expr = RangeExpression(
            NumericLiteralValue.optimalInteger(10, Position.DUMMY),
            NumericLiteralValue.optimalInteger(20, Position.DUMMY),
            NumericLiteralValue.optimalInteger(2, Position.DUMMY),
            Position.DUMMY)
        expr.size() shouldBe 6
        expr.toConstantIntegerRange()
    }

    test("range with negative step should be constvalue") {
        val result = compileText(C64Target, false, """
            main {
                sub start() {
                    ubyte[] array = 100 to 50 step -2
                    ubyte xx
                    for xx in 100 to 50 step -2 {
                    }
                }
            }
        """).assertSuccess()
        val statements = result.program.entrypoint.statements
        val array = (statements[0] as VarDecl).value
        array shouldBe instanceOf<ArrayLiteralValue>()
        (array as ArrayLiteralValue).value.size shouldBe 26
        val forloop = (statements.dropLast(1).last() as ForLoop)
        forloop.iterable shouldBe instanceOf<RangeExpression>()
        (forloop.iterable as RangeExpression).step shouldBe NumericLiteralValue(DataType.UBYTE, -2.0, Position.DUMMY)
    }

    test("range with start/end variables should be ok") {
        val result = compileText(C64Target, false, """
            main {
                sub start() {
                    byte from = 100
                    byte end = 50
                    byte xx
                    for xx in from to end step -2 {
                    }
                }
            }
        """).assertSuccess()
        val statements = result.program.entrypoint.statements
        val forloop = (statements.dropLast(1).last() as ForLoop)
        forloop.iterable shouldBe instanceOf<RangeExpression>()
        (forloop.iterable as RangeExpression).step shouldBe NumericLiteralValue(DataType.UBYTE, -2.0, Position.DUMMY)
    }


    test("for statement on all possible iterable expressions") {
        compileText(C64Target, false, """
            main {
                sub start() {
                    ubyte xx
                    uword ww
                    str name = "irmen"
                    ubyte[] values = [1,2,3,4,5,6,7]
                    uword[] wvalues = [1000,2000,3000]
            
                    for xx in name {
                        xx++
                    }
            
                    for xx in values {
                        xx++
                    }
            
                    for xx in 10 to 20 step 3 {
                        xx++
                    }
            
                    for xx in "abcdef" {
                        xx++
                    }
            
                    for xx in [2,4,6,8] {
                        xx++
                    }
                    
                    for ww in [9999,8888,7777] {
                        xx++
                    }

                    for ww in wvalues {
                        xx++
                    }
                }
            }""", writeAssembly = true).assertSuccess()
    }

    test("if containment check on all possible iterable expressions") {
        compileText(C64Target, false, """
            main {
                sub start() {
                    ubyte xx
                    uword ww
                    str name = "irmen"
                    ubyte[] values = [1,2,3,4,5,6,7]
                    uword[] wvalues = [1000,2000,3000]
            
                    if 'm' in name {
                        xx++
                    }
            
                    if 5 in values {
                        xx++
                    }
            
                    if 16 in 10 to 20 step 3 {
                        xx++
                    }
            
                    if 'b' in "abcdef" {
                        xx++
                    }
            
                    if 8 in [2,4,6,8] {
                        xx++
                    }

                    if xx in name {
                        xx++
                    }
            
                    if xx in values {
                        xx++
                    }
            
                    if xx in 10 to 20 step 3 {
                        xx++
                    }
            
                    if xx in "abcdef" {
                        xx++
                    }
            
                    if xx in [2,4,6,8] {
                        xx++
                    }
                    
                    if ww in [9999,8888,7777] {
                        xx++
                    }

                    if ww in wvalues {
                        xx++
                    }                    
                }
            }""", writeAssembly = true).assertSuccess()
    }

    test("containment check in expressions") {
        compileText(C64Target, false, """
            main {
                sub start() {
                    ubyte xx
                    uword ww
                    str name = "irmen"
                    ubyte[] values = [1,2,3,4,5,6,7]
                    uword[] wvalues = [1000,2000,3000]
            
                    xx = 'm' in name
                    xx = 5 in values
                    xx = 16 in 10 to 20 step 3
                    xx = 'b' in "abcdef"
                    xx = 8 in [2,4,6,8]
                    xx = xx in name
                    xx = xx in values
                    xx = xx in 10 to 20 step 3
                    xx = xx in "abcdef"
                    xx = xx in [2,4,6,8]
                    xx = ww in [9000,8000,7000]
                    xx = ww in wvalues
                }
            }""", writeAssembly = true).assertSuccess()
    }
})
