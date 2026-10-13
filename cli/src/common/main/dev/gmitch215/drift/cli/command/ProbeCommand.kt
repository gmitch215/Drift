package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.cli.cloudflare.CloudflareOptions
import dev.gmitch215.drift.cli.cloudflare.CloudflareProbe
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.atlas.AtlasException
import dev.gmitch215.drift.scan.atlas.Transcripts

class ProbeCommand(host: Host, files: CaseFiles) : CliktCommand(name = "probe") {
    init {
        subcommands(ProbeCloudflareCommand(host, files))
    }

    override fun help(context: Context) = "Record Kotlin probe transcripts on remote runtimes"

    override fun run() = Unit
}

private val targetLabel = Regex("[A-Za-z0-9._-]+")

private class ProbeCloudflareCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "cloudflare") {
    private val worker by option(
        "--worker",
        help = "directory with the built Worker and its wrangler config",
    ).required()
    private val target by option("--target", help = "label in the header").default("cloudflare")
    private val out by option("--out", help = "directory that receives <target>.txt")
    private val wrangler by option(
        "--wrangler",
        help = "wrangler command (default: wrangler on PATH, else npx wrangler)",
    )
    private val port by option("--port", help = "port for wrangler dev; 0 picks a free one")
        .int().default(0)
    private val timeout by option("--timeout", help = "seconds to wait for wrangler and the Worker")
        .int().default(DEFAULT_TIMEOUT)
    private val deploy by option(
        "--deploy",
        help = "deploy to your Cloudflare account instead of running locally; prints a price first",
    ).flag()
    private val dryRun by option(
        "--dry-run",
        help = "with --deploy, run wrangler deploy --dry-run: bundle only, nothing is uploaded",
    ).flag()

    override fun help(context: Context) =
        "Run the Kotlin probes in a Worker on workerd and write the transcript; the default run " +
            "is local through wrangler dev and needs no network"

    override fun run() {
        if (!targetLabel.matches(target)) {
            throw UsageError("--target may only use letters, digits, '.', '_' and '-': $target")
        }
        if (dryRun && !deploy) throw UsageError("--dry-run applies to --deploy")
        if (timeout < 1) throw UsageError("--timeout must be at least 1")
        if (deploy) echo(CloudflareProbe.estimate(), err = true)
        val argv = CloudflareProbe.argv(
            CloudflareOptions(worker, wrangler, port, timeout, deploy, dryRun),
        )
        val result = host.run(argv)
            ?: throw CliktError("cannot start node; the Cloudflare probe needs Node and wrangler")
        if (result.exitCode != 0) {
            throw CliktError(
                "the Cloudflare run failed (exit ${result.exitCode}):\n${result.output.trim()}",
            )
        }
        val log = CloudflareProbe.log(result.output)
        if (log.isNotEmpty()) echo(log, err = true)
        if (deploy && dryRun) return
        val text = CloudflareProbe.transcript(result.output)
            ?: throw CliktError("the Worker returned no transcript:\n${result.output.trim()}")
        val labelled = CloudflareProbe.retarget(text, target)
        val column = try {
            Transcripts.read(target, labelled)
        } catch (e: AtlasException) {
            throw CliktError("the Worker returned a malformed transcript: ${e.message}")
        }
        val refused = column.cells.count { it.value.status == ProbeStatus.UNAVAILABLE }
        val summary = "${column.cells.size} probes for $target on kotlin ${column.version}, " +
            "$refused unavailable"
        val dir = out
        if (dir == null) {
            rawEcho(labelled, trailingNewline = false)
            echo("recorded $summary", err = true)
            return
        }
        val path = "${dir.trimEnd('/')}/$target.txt"
        if (!files.write(path, labelled)) throw CliktError("cannot write transcript: $path")
        echo("wrote $summary to $path", err = true)
    }

    private companion object {
        const val DEFAULT_TIMEOUT = 120
    }
}
