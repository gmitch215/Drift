package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.lab.Verify

class VerifyCommand(private val archives: ArchiveFiles, private val files: CaseFiles) :
	CliktCommand(name = "verify") {
	private val file by argument("file", help = "a .driftcase archive")
	private val json by option("--json", help = "print the report as canonical JSON").flag()

	override fun help(context: Context) =
		"Re-derive everything a .driftcase records from its own data; experiments are not re-run"

	override fun run() {
		files.refuseCaseDirectory(file)
		val bytes = archives.read(file) ?: throw CliktError("cannot read archive: $file")
		val report = Verify.open(bytes)
		echo(if (json) report.json() else report.text())
		val first = report.first ?: return
		throw CliktError("verification failed: ${first.line()}")
	}
}
