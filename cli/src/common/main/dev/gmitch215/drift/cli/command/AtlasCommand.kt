package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.scan.KotlinBuild
import dev.gmitch215.drift.scan.atlas.Atlas
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.Comparison
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Show
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog

class AtlasCommand(host: Host, files: CaseFiles) : CliktCommand(name = "atlas") {
	init {
		subcommands(
			AtlasRecordCommand(host, files),
			AtlasBuildCommand(files),
			AtlasCompareCommand(files),
			AtlasShowCommand(files),
		)
	}

	override fun help(context: Context) =
		"Record Kotlin probe transcripts, build the dataset and compare a device against it"

	override fun run() = Unit
}

private val targetLabel = Regex("[A-Za-z0-9._-]+")

private fun <T> atlas(block: () -> T): T = try {
	block()
} catch (e: AtlasException) {
	throw CliktError(e.message)
} catch (e: JsonException) {
	throw CliktError("cannot encode the dataset: ${e.message}")
}

private fun CaseFiles.need(path: String, what: String): String =
	read(path) ?: throw CliktError("cannot read $what: $path")

private fun CaseFiles.put(path: String, text: String, what: String) {
	if (!write(path, text)) throw CliktError("cannot write $what: $path")
}

private class AtlasRecordCommand(private val host: Host, private val files: CaseFiles) :
	CliktCommand(name = "record") {
	private val out by option("--out", help = "directory that receives <target>.txt")
	private val target by option("--target", help = "label in the header (default: the platform)")

	override fun help(context: Context) =
		"Run the Kotlin probes on this target and write the transcript (stdout without --out)"

	override fun run() {
		val label = target ?: host.platform
		if (!targetLabel.matches(label)) {
			throw CliktError("--target may only use letters, digits, '.', '_' and '-': $label")
		}
		val text = Atlas.record(host, label)
		val probes = KotlinCatalog.all.size
		val version = KotlinBuild.VERSION
		val dir = out
		if (dir == null) {
			rawEcho(text, trailingNewline = false)
			echo("recorded $probes probes for $label on kotlin $version", err = true)
			return
		}
		val path = "${dir.trimEnd('/')}/$label.txt"
		files.put(path, text, "transcript")
		echo("wrote $probes probes for $label on kotlin $version to $path", err = true)
	}
}

private class AtlasBuildCommand(private val files: CaseFiles) : CliktCommand(name = "build") {
	private val transcripts by argument("transcript", help = "files written by atlas record")
		.multiple(required = true)
	private val out by option("--out", help = "write the dataset to this file").required()

	override fun help(context: Context) =
		"Aggregate transcripts into one canonical JSON dataset, one cell per target and version"

	override fun run() {
		val dataset = atlas {
			Dataset.build(transcripts.map { Transcripts.read(it, files.need(it, "transcript")) })
		}
		val text = atlas { dataset.encode() }
		files.put(out, text + "\n", "dataset")
		echo(
			"dataset: ${dataset.probes.size} probes, ${dataset.columns.size} columns, " +
				"${dataset.divergentCount} divergent, sha256 ${Sha256.hex(text)}",
			err = true,
		)
	}
}

private class AtlasCompareCommand(private val files: CaseFiles) : CliktCommand(name = "compare") {
	private val dataset by argument("dataset", help = "file written by atlas build")
	private val transcript by argument("transcript", help = "this device, from atlas record")
	private val detail by option("--detail", help = "how much to print")
		.choice("summary", "detail", "full").default("summary")

	override fun help(context: Context) =
		"Show where this device matches each recorded column and where it differs"

	override fun run() {
		val comparison = atlas {
			Comparison(
				Dataset.parse(files.need(dataset, "dataset")),
				Transcripts.read(transcript, files.need(transcript, "transcript")),
			)
		}
		rawEcho(comparison.render(Detail.parse(detail)!!), trailingNewline = false)
	}
}

private class AtlasShowCommand(private val files: CaseFiles) : CliktCommand(name = "show") {
	private val dataset by argument("dataset", help = "file written by atlas build")
	private val probe by option("--probe", help = "print the repro draft for this probe id")

	override fun help(context: Context) =
		"Print the matrix, or a generated repro draft for one probe that a person must review"

	override fun run() {
		val data = atlas { Dataset.parse(files.need(dataset, "dataset")) }
		val id = probe
		rawEcho(
			if (id == null) Show.matrix(data) else atlas { Show.repro(data, id) },
			trailingNewline = false,
		)
	}
}
