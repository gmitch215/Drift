package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.upgrade.TargetException
import dev.gmitch215.drift.upgrade.Targets
import dev.gmitch215.drift.upgrade.UpgradeCheck

class UpgradeCheckCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "upgrade-check") {
    private val capsule by argument(
        "capsule",
        help = "capsule file or directory of today's environment (default: capture now)",
    ).optional()
    private val to by option(
        "--to",
        metavar = "NAME=VERSION,...",
        help = "versions to move to, as php=8.3,drupal=11; a name is an alias or an attribute path",
    ).multiple()
    private val toFile by option(
        "--to-file",
        metavar = "FILE",
        help = "YAML file of flat name: version lines; --to replaces a name it also sets",
    )
    private val json by option("--json", help = "print canonical JSON instead of text").flag()
    private val learned by option(
        "--learned",
        metavar = "DIR",
        help = "learned-rule store whose rules are checked with the shipped ones",
    )

    override fun help(context: Context) =
        "List what a move to other tool and dependency versions breaks, from one capsule"

    override fun run() {
        val targets = try {
            val file = toFile?.let { path ->
                val text = host.readText(path) ?: throw CliktError("cannot read target file: $path")
                Targets.parseFile(text)
            }.orEmpty()
            Targets.resolve(file + to.flatMap(Targets::parseList))
        } catch (e: TargetException) {
            throw UsageError(e.message.orEmpty())
        }
        if (targets.isEmpty()) {
            throw UsageError(
                "give the versions to move to with --to or --to-file",
            )
        }
        val today = capsule?.let { loadCapsuleSource(host, it) }
            ?: Capture.run(host, "current", tools = true, project = true)
        val report = UpgradeCheck.run(today, targets, rulesWithLearned(files, learned))
        if (json) rawEcho(report.json()) else rawEcho(report.text(), trailingNewline = false)
    }
}
