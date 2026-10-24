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
import dev.gmitch215.drift.history.Repeat
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.ResourceUsage
import dev.gmitch215.drift.host.cgroupReading
import dev.gmitch215.drift.model.CgroupDelta
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Sample
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.symptom.Spread
import dev.gmitch215.drift.symptom.SymptomExtractor
import kotlin.time.TimeSource

class RunCommand(
    private val host: Host,
    private val files: CaseFiles,
    private val clock: TimeSource = TimeSource.Monotonic,
) : CliktCommand(name = "run") {
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
    private val runs by option(
        "--repeat",
        metavar = "N",
        help = "run the command N times and record each run's exit code, wall time and, where " +
            "the host measures them, CPU time and peak memory",
    ).int().default(1)
    private val warmups by option(
        "--warmup",
        metavar = "W",
        help = "run the command W times first without recording them",
    ).int().default(0)
    private val command by argument(help = "the command to run, after --").multiple(required = true)

    init {
        context { allowInterspersedArgs = false }
    }

    override fun help(context: Context) =
        "Run a command, capture the environment around it and keep a capsule directory; " +
            "the exit code passes through; with --repeat, the exit code of the first failing run"

    private class Attempt(
        val exitCode: Int,
        val wallMs: Long,
        val usage: ResourceUsage?,
        val window: String,
        val cut: Boolean,
        val cgroup: CgroupDelta?,
    )

    private fun attempt(live: Boolean): Attempt {
        val before = host.cgroupReading()
        val started = clock.markNow()
        val recent = StringBuilder()
        var windowCut = false
        val result = host.run(command) { chunk ->
            if (live) rawEcho(chunk, trailingNewline = false)
            recent.append(chunk)
            if (recent.length > 2 * Execution.WINDOW_CHARS) {
                recent.deleteRange(0, recent.length - Execution.WINDOW_CHARS)
                windowCut = true
            }
        } ?: throw CliktError("cannot start command: ${command.first()}")
        val wallMs = started.elapsedNow().inWholeMilliseconds
        val window = "$recent".let { if (windowCut) it.substringAfter('\n', "") else it }
        val cgroup = host.cgroupReading()?.since(before)
        return Attempt(result.exitCode, wallMs, result.usage, window, windowCut, cgroup)
    }

    override fun helpEpilog(context: Context) = examples("run")

    override fun run() {
        if (tailLines < 1) throw CliktError("--tail-lines must be at least 1")
        if (runs < 1) throw CliktError("--repeat must be at least 1")
        if (warmups < 0) throw CliktError("--warmup must not be negative")
        val dir = out.trimEnd('/')
        if (!force && files.read("$dir/${CapsuleDirectories.CAPSULE}") != null) {
            throw CliktError("$out already holds a capsule directory; pass --force to replace it")
        }
        val capsule = Capture.run(host, label, tools = tools, project = project)
        repeat(warmups) { attempt(live = false) }
        val attempts = List(runs) { attempt(live = runs == 1) }
        val chosen = attempts.firstOrNull { it.exitCode != 0 } ?: attempts.last()
        if (runs > 1) rawEcho(chosen.window, trailingNewline = false)
        val samples = attempts.map {
            Sample(
                it.exitCode,
                it.wallMs,
                it.usage?.userMs,
                it.usage?.systemMs,
                it.usage?.peakKb,
                it.cgroup,
            )
        }
        val durationMs = Spread.of(samples.map { it.wallMs })!!.median
        val (tail, short) = Execution.tail(chosen.window, tailLines)
        val truncated = short || chosen.cut
        val shown = command.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
        val line = Redactor.redactText(shown)
        val exitCode = chosen.exitCode
        val passed = attempts.all { it.exitCode == 0 }
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
            samples = if (runs > 1) samples else emptyList(),
        )
        val execution = Execution(line, exitCode, durationMs, truncated)
        val failed = CapsuleDirectories.write(
            files,
            dir,
            CapsuleDirectory(capsule, run, execution, tail),
        )
        if (failed != null) throw CliktError("cannot write capsule file: $failed")
        if (runs > 1) {
            Repeat.summary(label, samples).forEach { echo("drift: $it", err = true) }
            echo("drift: exit $exitCode; capsule in $out", err = true)
        } else {
            echo("drift: exit $exitCode after $durationMs ms; capsule in $out", err = true)
        }
        if (!passed) throw ProgramResult(exitCode)
    }

    private companion object {
        const val DEFAULT_TAIL = 200
    }
}
