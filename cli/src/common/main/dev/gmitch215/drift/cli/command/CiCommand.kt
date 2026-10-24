package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.Detail
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.scan.ci.CiCheck
import dev.gmitch215.drift.scan.ci.CiOutcome
import dev.gmitch215.drift.scan.ci.CiProblemKind
import dev.gmitch215.drift.scan.ci.CiProviderKind
import dev.gmitch215.drift.scan.ci.CiRequest

class CiCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "ci") {
    private val commit by option("--commit", help = "take the failed run of this commit")
    private val good by option(
        "--good",
        help = "compare against the passing run of this commit, or this run id",
    )
    private val runId by option("--run", help = "take this run id as the failing run")
    private val job by option("--job", help = "compare this job (default: the first failed job)")
    private val remote by option("--remote", help = "git remote that names the repository")
        .default("origin")
    private val provider by option("--provider", help = "CI provider (default: from the remote)")
        .choice("github", "gitlab")
    private val out by option("--out", help = "directory for the case (default: .drift/ci/SHA)")
    private val detail by option("--detail", help = "also print the full ranking and plan")
        .choice("summary", "detail", "full")
    private val json by option("--json", help = "print canonical JSON instead of text").flag()

    override fun help(context: Context) =
        "Say what is wrong with this repository's CI: compare the latest failed run with an " +
            "earlier passing run through gh or glab, then diagnose the pair"

    override fun helpEpilog(context: Context) = examples("ci")

    override fun run() {
        if (json && detail != null) throw UsageError("--json and --detail cannot be combined")
        val request = CiRequest(
            remote,
            provider?.let { id -> CiProviderKind.entries.first { it.id == id } },
            commit,
            good,
            runId,
            job,
            out,
        )
        when (val outcome = CiCheck.run(host, files, request)) {
            is CiOutcome.Stopped -> {
                if (outcome.kind == CiProblemKind.USAGE) throw UsageError(outcome.message)
                throw CliktError(outcome.message)
            }

            is CiOutcome.Compared -> {
                val report = outcome.report
                if (json) {
                    rawEcho(CanonicalJson.encode(report.toJson()))
                } else {
                    echo(report.text(detail?.let { Detail.parse(it) }), trailingNewline = false)
                }
            }
        }
    }
}
