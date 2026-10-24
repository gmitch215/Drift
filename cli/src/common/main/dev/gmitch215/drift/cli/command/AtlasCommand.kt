package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.scan.KotlinBuild
import dev.gmitch215.drift.scan.atlas.Atlas
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.Comparison
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Here
import dev.gmitch215.drift.scan.atlas.Show
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.atlas.VersionDiff
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog

class AtlasCommand(host: Host, files: CaseFiles) : CliktCommand(name = "atlas") {
    init {
        subcommands(
            AtlasRecordCommand(host, files),
            AtlasHereCommand(host, files),
            AtlasBuildCommand(files),
            AtlasCompareCommand(files),
            AtlasShowCommand(files),
            AtlasDiffCommand(files),
        )
    }

    override fun help(context: Context) =
        "Record Kotlin probe transcripts, build the dataset and compare a device against it"

    override fun helpEpilog(context: Context) = examples("atlas")

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

    override fun helpEpilog(context: Context) = examples("atlas record")

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

private class AtlasHereCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "here") {
    private val version by option(
        "--version",
        help = "compare with this kotlin version of the dataset (default: this build)",
    )
    private val out by option("--out", help = "also write this device as a transcript to this file")
    private val exitStatus by option(
        "--exit-status",
        help = "exit 10 when a probe matches no recorded column",
    ).flag()

    override fun help(context: Context) =
        "Run the Kotlin probes on this device and compare them with the recorded columns"

    override fun helpEpilog(context: Context) = examples("atlas here")

    override fun run() {
        val kotlin = version ?: KotlinBuild.VERSION
        val label = Atlas.deviceLabel(host)
        val text = Atlas.record(host, label)
        val columns = atlas {
            AtlasFixtures.names.filter { it.endsWith(".txt") }.sorted()
                .map { Transcripts.read(it, AtlasFixtures.text(it)) }
        }.filter { it.version == kotlin }
        if (columns.isEmpty()) throw CliktError("no recorded column for kotlin $kotlin")
        val dataset = atlas { Dataset.build(columns) }
        val here = Here(Comparison(dataset, atlas { Transcripts.read(label, text) }), kotlin)
        out?.let { path ->
            files.put(path, text, "transcript")
            echo("wrote $label to $path", err = true)
        }
        rawEcho(here.render(), trailingNewline = false)
        if (exitStatus && here.differsEverywhere) throw ProgramResult(10)
    }
}

private class AtlasBuildCommand(private val files: CaseFiles) : CliktCommand(name = "build") {
    private val transcripts by argument("transcript", help = "files written by atlas record")
        .multiple(required = true)
    private val out by option("--out", help = "write the dataset to this file").required()

    override fun help(context: Context) =
        "Aggregate transcripts into one canonical JSON dataset, one cell per target and version"

    override fun helpEpilog(context: Context) = examples("atlas build")

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

    override fun helpEpilog(context: Context) = examples("atlas compare")

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
    private val oracle by option(
        "--oracle",
        help = "print the oracle kind of every divergent line and the reportable lines",
    ).flag()

    override fun help(context: Context) =
        "Print the matrix, the oracle, or a generated repro draft that a person must review"

    override fun helpEpilog(context: Context) = examples("atlas show")

    override fun run() {
        val data = atlas { Dataset.parse(files.need(dataset, "dataset")) }
        val id = probe
        if (oracle && id != null) throw CliktError("--oracle and --probe cannot be combined")
        rawEcho(
            when {
                oracle -> Show.oracle(data)
                id == null -> Show.matrix(data)
                else -> atlas { Show.repro(data, id) }
            },
            trailingNewline = false,
        )
    }
}

private class AtlasDiffCommand(private val files: CaseFiles) : CliktCommand(name = "diff") {
    private val dataset by argument("dataset", help = "file written by atlas build")
    private val from by option(
        "--from",
        help = "older Kotlin version, as in the dataset",
    ).required()
    private val to by option("--to", help = "newer Kotlin version, as in the dataset").required()
    private val target by option("--target", help = "compare one target (default: every target)")
    private val detail by option("--detail", help = "how much to print")
        .choice("summary", "detail", "full").default("detail")

    override fun help(context: Context) =
        "Show what changed between two Kotlin versions on the targets measured on both"

    override fun helpEpilog(context: Context) = examples("atlas diff")

    override fun run() {
        val diff = atlas {
            VersionDiff(Dataset.parse(files.need(dataset, "dataset")), from, to, target)
        }
        rawEcho(diff.render(Detail.parse(detail)!!), trailingNewline = false)
    }
}
