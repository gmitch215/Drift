package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.case.Render
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.studio.StudioState

class DiagnoseCommand(private val host: Host, private val files: CaseFiles) :
	CliktCommand(name = "diagnose") {
	private val green by argument(help = "capsule of the passing run")
	private val red by argument(help = "capsule of the failing run")
	private val case by option("--case", help = "write a case directory here")
	private val detail by option("--detail", help = "print text instead of JSON")
		.choice("summary", "detail", "full")
	private val json by option("--json", help = "print canonical JSON (the default)").flag()
	private val frame by option("--frame", help = "two commits, or a local and a CI capture")
		.choice("sha", "environment").default("sha")
	private val name by option("--name", help = "case name (default: both capsule labels)")
	private val trialMinutes by option("--trial-minutes", help = "minutes one trial takes")
		.long().default(5)
	private val failures by option("--failures", help = "failing runs seen on the red side")
		.int().default(1)
	private val runs by option("--runs", help = "runs seen on the red side").int().default(1)

	override fun help(context: Context) =
		"Diagnose a green and a red capsule: canonical JSON by default, or text and a case"

	override fun run() {
		if (json && detail != null) throw UsageError("--json and --detail cannot be combined")
		val passing = loadCapsule(host, green)
		val failing = loadCapsule(host, red)
		if (detail == null && case == null) {
			rawEcho(CanonicalJson.encode(StudioState.of(passing, failing).toJson()))
			return
		}
		if (trialMinutes < 1) throw CliktError("--trial-minutes must be at least 1")
		if (runs < 0 || failures !in 0..runs) {
			throw CliktError("--failures must be within 0..--runs")
		}
		val ranking = Ranker.rank(passing, failing)
		val shape = if (frame == "sha") {
			Frame.shas(passing.label, failing.label)
		} else {
			Frame.environments(passing.label, failing.label)
		}
		val plan = Planner.from(ranking, shape, Options(trialMinutes, failures, runs))
		case?.let { dir ->
			if (files.read("${dir.trimEnd('/')}/case.json") != null) {
				throw CliktError("case directory already holds a case: $dir")
			}
			val label = name ?: "${passing.label} to ${failing.label}"
			val built = CaseBuilder.build(label, passing, failing, ranking, plan)
			when (val result = CaseStore.write(files, dir, built)) {
				is CaseWrite.Written -> echo("wrote ${result.files} files to $dir", err = true)
				is CaseWrite.Failed -> throw CliktError("cannot write case file: ${result.path}")
			}
		}
		val level = detail?.let { Detail.parse(it) }
		if (level == null) {
			rawEcho(CanonicalJson.encode(StudioState.of(passing, failing).toJson()))
		} else {
			echo(Render.render(ranking, plan, level), trailingNewline = false)
		}
	}
}
