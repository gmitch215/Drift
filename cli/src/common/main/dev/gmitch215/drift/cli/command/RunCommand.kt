package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.Execution
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.symptom.SymptomExtractor
import kotlin.time.TimeSource

class RunCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "run") {
    private val out by option("--out", help = "capsule directory to write").default("drift-run")
    private val label by option("--label", help = "name stored in the capsule").default("run")
    private val tailLines by option("--tail-lines", help = "log lines kept in tail.log")
        .int().default(DEFAULT_TAIL)
    private val tools by option("--tools", help = "also run the toolchain scanners").flag()
    private val project by option(
        "--project",
        help = "also read dependency versions from the manifests in the working directory",
    ).flag()
    private val force by option(
        "--force",
        help = "replace the files of an earlier run in --out",
    ).flag()
    private val command by argument(help = "the command to run, after --").multiple(required = true)

    init {
        context { allowInterspersedArgs = false }
    }

    override fun help(context: Context) =
        "Run a command, capture the environment around it and keep a capsule directory; " +
            "the exit code passes through"

    override fun run() {
        if (tailLines < 1) throw CliktError("--tail-lines must be at least 1")
        val dir = out.trimEnd('/')
        if (!force && files.read("$dir/${CapsuleDirectories.CAPSULE}") != null) {
            throw CliktError("$out already holds a capsule directory; pass --force to replace it")
        }
        val capsule = Capture.run(host, label, tools = tools, project = project)
        val started = TimeSource.Monotonic.markNow()
        val recent = StringBuilder()
        var windowCut = false
        val result = host.run(command) { chunk ->
            rawEcho(chunk, trailingNewline = false)
            recent.append(chunk)
            if (recent.length > 2 * Execution.WINDOW_CHARS) {
                recent.deleteRange(0, recent.length - Execution.WINDOW_CHARS)
                windowCut = true
            }
        } ?: throw CliktError("cannot start command: ${command.first()}")
        val durationMs = started.elapsedNow().inWholeMilliseconds
        val window = "$recent".let { if (windowCut) it.substringAfter('\n', "") else it }
        val (tail, short) = Execution.tail(window, tailLines)
        val truncated = short || windowCut
        val shown = command.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
        val line = Redactor.redactText(shown)
        val passed = result.exitCode == 0
        val run = Run(
            label,
            if (passed) RunOutcome.PASS else RunOutcome.FAIL,
            listOf(
                Step(
                    null,
                    line,
                    if (passed) StepConclusion.SUCCESS else StepConclusion.FAILURE,
                    durationMs,
                ),
            ),
            SymptomExtractor.extract(tail),
        )
        val execution = Execution(line, result.exitCode, durationMs, truncated)
        val failed = CapsuleDirectories.write(
            files,
            dir,
            CapsuleDirectory(capsule, run, execution, tail),
        )
        if (failed != null) throw CliktError("cannot write capsule file: $failed")
        echo("drift: exit ${result.exitCode} after $durationMs ms; capsule in $out", err = true)
        if (!passed) throw ProgramResult(result.exitCode)
    }

    private companion object {
        const val DEFAULT_TAIL = 200
    }
}
