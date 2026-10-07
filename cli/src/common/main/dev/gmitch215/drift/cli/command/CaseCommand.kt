package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.Ingest

class CaseCommand(files: CaseFiles) : CliktCommand(name = "case") {
	init {
		subcommands(ShowCommand(files))
	}

	override fun help(context: Context) = "Work with case directories"

	override fun run() = Unit
}

private class ShowCommand(private val files: CaseFiles) : CliktCommand(name = "show") {
	private val dir by argument("case-dir", help = "a case directory written by diagnose --case")
	private val detail by option("--detail", help = "how much to print")
		.choice("summary", "detail", "full").default("summary")

	override fun help(context: Context) = "Show what a case holds and whether it still checks out"

	override fun run() {
		val case = loadCase(files, dir)
		echo(header(case, dir))
		val problems = case.check()
		if (problems.isEmpty()) {
			val files = case.files.size - 1
			echo("integrity: ok, $files files match the manifest and the chain holds")
		} else {
			echo("integrity: ${problems.size} problem(s)")
			for (p in problems) echo("  ${p.message()}", err = true)
		}
		if (problems.isEmpty()) {
			ruleLine(case)?.let { echo(it) }
		}
		echo("")
		if (problems.isEmpty()) {
			echo(case.render(dir, Detail.parse(detail)!!, next = false), trailingNewline = false)
		} else {
			throw ProgramResult(1)
		}
	}

	private fun ruleLine(case: CaseFile): String? = try {
		Ingest.rule(case.json("experiments/plan.json"))?.let {
			"rule sha256: ${it.sha256()} (ingest names it as ruleSha256)"
		}
	} catch (e: JsonException) {
		null
	}

	private fun header(case: CaseFile, dir: String): String = try {
		val head = case.json(CaseFile.CASE)
		val frame = head["frame"] as JsonObject
		fun text(o: JsonObject, key: String) = (o[key] as JsonString).value
		"case: ${text(head, "name")}\n" +
			"frame: ${text(frame, "kind")}, passing ${text(frame, "passing")}, " +
			"failing ${text(frame, "failing")}\n" +
			"observations: ${(head["observations"] as JsonInt).value}, chain head " +
			text(head, "head")
	} catch (e: JsonException) {
		throw CliktError("case $dir has no readable case.json: ${e.message}")
	} catch (e: RuntimeException) {
		throw CliktError("case $dir has no readable case.json")
	}
}
