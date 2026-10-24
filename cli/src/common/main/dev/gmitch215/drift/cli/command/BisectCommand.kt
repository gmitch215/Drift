package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.delta.BisectApply
import dev.gmitch215.drift.delta.BisectPlan
import dev.gmitch215.drift.delta.BisectState
import dev.gmitch215.drift.delta.TrialOutcome
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException

class BisectCommand(host: Host, files: CaseFiles) : CliktCommand(name = "bisect") {
    init {
        subcommands(BisectPlanCommand(host, files), BisectApplyCommand(files))
    }

    override fun help(context: Context) =
        "Narrow a green and red pair to the attributes that matter, one test at a time"

    override fun helpEpilog(context: Context) = examples("bisect")

    override fun run() = Unit
}

private class BisectPlanCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "plan") {
    private val green by argument(help = "capsule file or directory of the passing run")
    private val red by argument(help = "capsule file or directory of the failing run")
    private val out by option("--out", help = "plan file to write").required()
    private val force by option("--force", help = "replace an existing plan file").flag()

    override fun help(context: Context) =
        "Write a bisection plan over the static attributes that differ, and print the first step"

    override fun helpEpilog(context: Context) = examples("bisect plan")

    override fun run() {
        if (!force && files.read(out) != null) {
            throw CliktError("plan file already exists: $out; pass --force to replace it")
        }
        val plan = BisectPlan.of(loadCapsuleSource(host, green), loadCapsuleSource(host, red))
            ?: throw CliktError("the capsules have no static attribute differences to bisect")
        if (!files.write(out, plan.canonical() + "\n")) throw CliktError("cannot write file: $out")
        echo("wrote a plan over ${plan.flips.size} attributes to $out", err = true)
        echo(BisectText.render(plan, out), trailingNewline = false)
    }
}

private class BisectApplyCommand(private val files: CaseFiles) : CliktCommand(name = "apply") {
    private val planFile by argument("plan", help = "plan file written by bisect plan")
    private val step by option("--step", help = "the step the test was run for").int().required()
    private val green by option("--green", help = "the test passed with this step's changes").flag()
    private val red by option("--red", help = "the test failed with this step's changes").flag()

    override fun help(context: Context) =
        "Record the outcome of a step, then print the next step or the minimal set"

    override fun helpEpilog(context: Context) = examples("bisect apply")

    override fun run() {
        if (green == red) throw UsageError("pass exactly one of --green and --red")
        val text = files.read(planFile) ?: throw CliktError("cannot read plan file: $planFile")
        val plan = try {
            BisectPlan.parse(text)
        } catch (e: JsonException) {
            throw CliktError("invalid plan file $planFile: ${e.message}")
        }
        val outcome = if (red) TrialOutcome.RED else TrialOutcome.GREEN
        val next = when (val result = plan.apply(step, outcome)) {
            is BisectApply.Refused -> throw CliktError(result.reason)
            is BisectApply.Updated -> result.plan
        }
        if (next != plan && !files.write(planFile, next.canonical() + "\n")) {
            throw CliktError("cannot write file: $planFile")
        }
        echo(BisectText.render(next, planFile), trailingNewline = false)
    }
}

internal object BisectText {
    fun render(plan: BisectPlan, file: String): String {
        val out = StringBuilder()
        when (val state = plan.state()) {
            is BisectState.Next -> {
                out.append("step ${state.step}: in the green environment, set these ")
                out.append("attributes to their red values and run the test\n")
                for (i in state.set) {
                    val f = plan.flips[i]
                    out.append("  ${f.path}: ${f.green ?: "(unset)"} -> ${f.red ?: "(unset)"}\n")
                }
                out.append("then: drift bisect apply $file --step ${state.step} ")
                out.append("--green (test passed) or --red (test failed)\n")
            }

            is BisectState.Done -> {
                val size = state.minimal.size
                val tests = plan.trials.size
                out.append("minimal set: $size of ${plan.flips.size} attributes after ")
                out.append("$tests ${if (tests == 1) "test" else "tests"}; the test fails with ")
                out.append("all of them and passes when any one is left out\n")
                for (i in state.minimal) {
                    val f = plan.flips[i]
                    out.append("  ${f.path}: ${f.green ?: "(unset)"} -> ${f.red ?: "(unset)"}\n")
                }
            }
        }
        return out.toString()
    }
}
