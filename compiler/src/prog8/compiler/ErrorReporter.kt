package prog8.compiler

import prog8.ast.base.Position
import prog8.compilerinterface.IErrorReporter

internal class ErrorReporter: IErrorReporter {
    private enum class MessageSeverity {
        WARNING,
        ERROR
    }
    private class CompilerMessage(val severity: MessageSeverity, val message: String, val position: Position)

    private val messages = mutableListOf<CompilerMessage>()
    private val alreadyReportedMessages = mutableSetOf<String>()

    override fun err(msg: String, position: Position) {
        messages.add(CompilerMessage(MessageSeverity.ERROR, msg, position))
    }
    override fun warn(msg: String, position: Position) {
        messages.add(CompilerMessage(MessageSeverity.WARNING, msg, position))
    }

    override fun report() {
        var numErrors = 0
        var numWarnings = 0
        messages.forEach {
            val printer = when(it.severity) {
                MessageSeverity.WARNING -> System.out
                MessageSeverity.ERROR -> System.err
            }
            when(it.severity) {
                MessageSeverity.ERROR -> printer.print("\u001b[91m")  // bright red
                MessageSeverity.WARNING -> printer.print("\u001b[93m")  // bright yellow
            }
            val msg = "${it.severity} ${it.position.toClickableStr()} ${it.message}".trim()
            if(msg !in alreadyReportedMessages) {
                printer.println(msg)
                alreadyReportedMessages.add(msg)
                when(it.severity) {
                    MessageSeverity.WARNING -> numWarnings++
                    MessageSeverity.ERROR -> numErrors++
                }
            }
            printer.print("\u001b[0m")      // reset color
        }
        System.out.flush()
        System.err.flush()
        messages.clear()
        finalizeNumErrors(numErrors, numWarnings)
    }

    override fun noErrors() = messages.none { it.severity==MessageSeverity.ERROR }
}
