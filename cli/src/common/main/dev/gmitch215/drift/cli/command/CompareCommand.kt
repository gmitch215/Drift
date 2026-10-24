package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.DirectoryRead
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.stats.RunComparison
import dev.gmitch215.drift.symptom.Performance

class CompareCommand(private val host: Host) : CliktCommand(name = "compare") {
    private val first by argument("runA", help = "capsule directory written by drift run --repeat")
    private val second by argument("runB", help = "capsule directory to compare against runA")
    private val json by option("--json", help = "print canonical JSON instead of text").flag()

    override fun help(context: Context) =
        "Compare the stored samples of two runs: ranges, an exact Mann-Whitney p-value, " +
            "Cliff's delta and a seeded bootstrap interval, computed offline from run.json"

    override fun helpEpilog(context: Context) = examples("compare")

    private fun load(path: String): Pair<CapsuleDirectory, Run> {
        val read = CapsuleDirectories.read(host::readText, path)
            ?: throw CliktError(
                "$path is not a capsule directory; record one with drift run --repeat",
            )
        val directory = when (read) {
            is DirectoryRead.Loaded -> read.directory
            is DirectoryRead.Failed -> throw CliktError(read.reason)
        }
        val run = directory.run?.takeIf { it.samples.size >= Performance.MIN_SAMPLES }
            ?: throw CliktError(
                "$path has ${directory.run?.samples?.size ?: 0} samples; compare needs at least " +
                    "${Performance.MIN_SAMPLES}, so record it with drift run --repeat",
            )
        return directory to run
    }

    override fun run() {
        val (a, runA) = load(first)
        val (b, runB) = load(second)
        val result = RunComparison.of(runA, runB, a.capsule, b.capsule)!!
        if (json) {
            val fields = mapOf("runA" to JsonString(first), "runB" to JsonString(second))
            rawEcho(CanonicalJson.encode(JsonObject(fields + result.toJson().fields)))
            return
        }
        echo("A: $first (${a.capsule.label})")
        echo("B: $second (${b.capsule.label})")
        result.lines().forEach { echo(it) }
    }
}
