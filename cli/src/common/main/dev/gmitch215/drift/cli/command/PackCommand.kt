package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchivePack

class PackCommand(private val files: CaseFiles, private val archives: ArchiveFiles) :
	CliktCommand(name = "pack") {
	private val case by argument("case-dir", help = "a case directory written by solve")
	private val out by option("--out", help = "the .driftcase file to write").required()

	override fun help(context: Context) =
		"Pack a solved case directory into a deterministic .driftcase archive"

	override fun run() {
		val loaded = loadCase(files, case.trimEnd('/'))
		when (val packed = Archive.pack(loaded)) {
			is ArchivePack.Failed -> throw CliktError("cannot pack $case: ${packed.message}")

			is ArchivePack.Packed -> {
				if (!archives.write(out, packed.bytes)) throw CliktError("cannot write $out")
				echo(
					"wrote $out: ${packed.case.files.size} files, ${packed.bytes.size} bytes, " +
						"sha256 ${Sha256.hex(packed.bytes)}",
				)
			}
		}
	}
}
