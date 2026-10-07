package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.CaseWrite
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.Ingest
import dev.gmitch215.drift.lab.IngestOutcome
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.lab.VerdictKind
import dev.gmitch215.drift.math.FixedPoint

class IngestCommand(private val host: Host, private val files: CaseFiles) :
	CliktCommand(name = "ingest") {
	private val case by argument(help = "case directory to add the results to")
	private val results by argument(
		help = "directory of result files (*.json) from another run; --template writes here",
	)
	private val template by option(
		"--template",
		help = "write a skeleton result file per open experiment into the results directory " +
			"instead of ingesting; fill in the failures counts",
	).flag()
	private val exitStatus by option(
		"--exit-status",
		help = "exit 0 CONFIRMED, 10 CONFIRMED EFFECT (bundle), 11 NARROWED, 12 STUCK",
	).flag()

	override fun help(context: Context) =
		"Add results someone else produced to a case, under the preregistered rule"

	private fun writeTemplate(loaded: CaseFile) {
		val skeleton = try {
			Ingest.skeleton(loaded)
		} catch (e: JsonException) {
			throw CliktError("case $case does not hold a plan: ${e.message}")
		}
		if (skeleton.isEmpty()) throw CliktError("case $case has no open experiment to template")
		val base = results.trimEnd('/')
		val existing = skeleton.keys.filter { files.read("$base/$it") != null }
		if (existing.isNotEmpty()) {
			throw CliktError("results directory already holds ${existing.joinToString(", ")}")
		}
		for ((name, text) in skeleton) {
			if (!files.write("$base/$name", text)) {
				throw CliktError("cannot write file: $base/$name")
			}
		}
		echo(
			"wrote ${skeleton.size} result files to $results; set failures for each arm",
			err = true,
		)
		echo(skeleton.keys.joinToString("\n"))
	}

	override fun run() {
		val dir = case.trimEnd('/')
		val loaded = loadCase(files, dir)
		loaded.requireIntact(dir)
		if (template) return writeTemplate(loaded)
		val listing = host.run(listOf("ls", "-1", results))
		if (listing == null || listing.exitCode != 0) {
			throw CliktError("cannot list results directory: $results")
		}
		val names = listing.output.lines().map { it.trim() }.filter { it.endsWith(".json") }
		if (names.isEmpty()) throw CliktError("no *.json result files in $results")
		val texts = names.associateWith {
			host.readText("${results.trimEnd('/')}/$it")
				?: throw CliktError("cannot read result file: $it")
		}
		when (val outcome = Ingest.apply(loaded, texts)) {
			is IngestOutcome.Refused -> {
				val lines = outcome.problems.joinToString("\n") { it.message() }
				throw CliktError("nothing was ingested:\n$lines")
			}

			is IngestOutcome.Done -> {
				when (val written = CaseStore.write(files, dir, outcome.case)) {
					is CaseWrite.Written ->
						echo("wrote ${written.files} files to $case", err = true)

					is CaseWrite.Failed ->
						throw CliktError("cannot write case file: ${written.path}")
				}
				for (a in outcome.accepted) {
					echo(
						"${a.id}: ${a.outcome.id}; control ${a.control.failures} of " +
							"${a.control.trials}, treatment ${a.treatment.failures} of " +
							"${a.treatment.trials}, p ${FixedPoint.format(a.p.micro())}",
					)
				}
				val certificate = SolveCertificate.certificate(outcome.case)
				val verdict = certificate?.get("verdict") as? JsonObject
				if (verdict != null) {
					val label = (verdict["label"] as JsonString).value
					echo("$label: ${(verdict["statement"] as JsonString).value}")
					val id = (verdict["kind"] as JsonString).value
					val kind = VerdictKind.entries.firstOrNull { it.id == id }
					if (exitStatus && kind != null && verdictExitCode(kind) != 0) {
						throw ProgramResult(verdictExitCode(kind))
					}
				}
			}
		}
	}
}
