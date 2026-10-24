package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.ci.GithubImport
import dev.gmitch215.drift.ci.GitlabImport
import dev.gmitch215.drift.ci.Import
import dev.gmitch215.drift.host.Host

class ImportCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "import") {
    private val log by argument("log", help = "saved job log or trace")
    private val format by option("--format", help = "where the log came from").choice(
        "github",
        "gitlab",
    ).required()
    private val out by option("--out", help = "capsule directory to write").required()
    private val summary by option(
        "--summary",
        help = "gh run view --json output (github) or pipeline JSON (gitlab) for the outcome",
    )
    private val job by option("--job", help = "job to read from a multi-job GitHub log")
    private val label by option("--label", help = "name stored in the capsule")
    private val force by option("--force", help = "replace the files of an earlier import").flag()

    override fun help(context: Context) =
        "Turn a saved GitHub Actions or GitLab CI log into a capsule directory, offline"

    override fun helpEpilog(context: Context) = examples("import")

    override fun run() {
        if (job != null && format != "github") throw UsageError("--job applies to --format github")
        val dir = out.trimEnd('/')
        if (!force && files.read("$dir/${CapsuleDirectories.CAPSULE}") != null) {
            throw CliktError("$out already holds a capsule directory; pass --force to replace it")
        }
        val text = host.readText(log) ?: throw CliktError("cannot read log file: $log")
        val extra = summary?.let { host.readText(it) ?: throw CliktError("cannot read file: $it") }
        val id = label ?: format
        val result = if (format == "github") {
            GithubImport.run(text, extra, id, job)
        } else {
            GitlabImport.run(text, extra, id)
        }
        val run = when (result) {
            is Import.Done -> result.run
            is Import.Refused -> throw CliktError("cannot import $log: ${result.reason}")
        }
        val capsule = run.capsule ?: throw CliktError("cannot import $log: no environment found")
        val failed = CapsuleDirectories.write(
            files,
            dir,
            CapsuleDirectory(capsule, run.copy(capsule = null)),
        )
        if (failed != null) throw CliktError("cannot write capsule file: $failed")
        echo(
            "wrote ${capsule.attributes.size} attributes and ${run.symptoms.size} symptoms " +
                "to $out",
            err = true,
        )
    }
}
