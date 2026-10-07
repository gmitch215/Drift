package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchiveOpen
import dev.gmitch215.drift.lab.ReportHtml
import dev.gmitch215.drift.lab.SolveCertificate

class ReportCommand(private val files: CaseFiles, private val archives: ArchiveFiles) :
	CliktCommand(name = "report") {
	private val file by argument("file", help = "a .driftcase archive")
	private val out by option("--out", help = "the HTML file to write").required()

	override fun help(context: Context) =
		"Write the static report.html generated from the certificate of a .driftcase"

	override fun run() {
		files.refuseCaseDirectory(file)
		val bytes = archives.read(file) ?: throw CliktError("cannot read archive: $file")
		val case = when (val opened = Archive.open(bytes)) {
			is ArchiveOpen.Opened -> opened.case

			is ArchiveOpen.Failed -> throw CliktError(
				"cannot read $file: ${opened.problem.message()}",
			)
		}
		val html = try {
			ReportHtml.of(
				SolveCertificate.certificate(case)
					?: throw CliktError("$file holds no ${SolveCertificate.CERTIFICATE}"),
			)
		} catch (e: JsonException) {
			throw CliktError(
				"$file holds a malformed ${SolveCertificate.CERTIFICATE}: ${e.message}",
			)
		}
		if (!files.write(out, html)) throw CliktError("cannot write $out")
		echo("wrote $out: ${html.length} characters")
	}
}
