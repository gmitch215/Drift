package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.cli.DockerExecutor
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.lab.Budget
import dev.gmitch215.drift.lab.Executor
import dev.gmitch215.drift.lab.FailWhen
import dev.gmitch215.drift.lab.SolveOptions
import dev.gmitch215.drift.lab.SolveRender
import dev.gmitch215.drift.lab.Solver

class SolveCommand(
	private val host: Host,
	private val files: CaseFiles,
	private val executor: Executor? = null,
) : CliktCommand(name = "solve") {
	private val green by argument(help = "capsule of the passing run")
	private val red by argument(help = "capsule of the failing run")
	private val command by option("--run", help = "the failing command, run in each container")
		.required()
	private val failWhen by option(
		"--fail-when",
		help = "exit (a nonzero exit fails) or a pattern the output of a failing trial matches",
	).required()
	private val budget by option(
		"--budget",
		metavar = "trials=N,minutes=M",
		help = "most trials over all arms and most minutes of container time",
	).default("trials=300,minutes=45")
	private val case by option("--case", help = "case directory to write").default("drift-case")
	private val detail by option("--detail", help = "how much to print")
		.choice("summary", "detail", "full").default("summary")
	private val workspace by option(
		"--workspace",
		help = "directory mounted at /work in each container",
	).default(".")
	private val driftBinary by option(
		"--drift-binary",
		help = "absolute path of a linux drift binary; each arm is captured inside its container",
	)
	private val pilot by option("--pilot", help = "trials per arm that check the failure shows")
		.int().default(8)
	private val trialMinutes by option("--trial-minutes", help = "planner's minutes per trial")
		.long().default(1)
	private val timeout by option("--timeout", help = "seconds before one trial is cut off")
		.long().default(120)
	private val maxMemory by option("--max-memory", help = "docker --memory for arms with no limit")
	private val maxCpus by option("--max-cpus", help = "docker --cpus for arms with no limit")
	private val exitStatus by option(
		"--exit-status",
		help = "exit 0 CONFIRMED, 10 CONFIRMED EFFECT (bundle), 11 NARROWED, 12 STUCK",
	).flag()
	private val name by option("--name", help = "case name (default: both capsule labels)")

	override fun help(context: Context) =
		"Find the cause of a failure by running counterfactual arms in containers"

	override fun run() {
		val dir = case.trimEnd('/')
		if (files.read("$dir/case.json") != null) {
			throw CliktError("case directory already holds a case: $case")
		}
		val limits = parseBudget(budget)
		val fail = try {
			FailWhen.parse(failWhen)
		} catch (e: IllegalArgumentException) {
			throw CliktError(e.message ?: "bad --fail-when")
		}
		val options = try {
			SolveOptions(
				command,
				fail,
				limits,
				name,
				trialMinutes,
				pilot,
			)
		} catch (e: IllegalArgumentException) {
			throw CliktError(e.message ?: "bad option")
		}
		val passing = loadCapsule(host, green)
		val failing = loadCapsule(host, red)
		val runner = executor ?: DockerExecutor(
			host,
			workspace,
			driftBinary,
			timeout,
			maxMemory,
			maxCpus,
		)
		val result = Solver(runner, files, dir, options).solve(passing, failing)
		when (val written = CaseStore.write(files, dir, result.case)) {
			is CaseWrite.Written -> echo("wrote ${written.files} files to $case", err = true)
			is CaseWrite.Failed -> throw CliktError("cannot write case file: ${written.path}")
		}
		echo(SolveRender.render(result, Detail.parse(detail)!!), trailingNewline = false)
		if (exitStatus) {
			val code = verdictExitCode(result.verdict.kind)
			if (code != 0) throw ProgramResult(code)
		}
	}

	internal fun parseBudget(text: String): Budget {
		var trials = 300
		var minutes = 45L
		for (part in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
			val key = part.substringBefore('=', "")
			val value = part.substringAfter('=', "").toLongOrNull()
			if (value == null || value < 0) throw CliktError("bad --budget item: $part")
			when (key) {
				"trials" -> trials = value.toInt()
				"minutes" -> minutes = value
				else -> throw CliktError("--budget takes trials=N and minutes=M, not $part")
			}
		}
		return Budget(trials, minutes)
	}
}
