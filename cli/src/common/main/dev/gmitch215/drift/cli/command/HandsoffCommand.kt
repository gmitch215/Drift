package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.scan.handsoff.Handsoff
import dev.gmitch215.drift.scan.handsoff.HandsoffRender
import dev.gmitch215.drift.scan.handsoff.HandsoffRequest
import dev.gmitch215.drift.scan.handsoff.HostCheckout

class HandsoffCommand(host: Host, files: CaseFiles) : CliktCommand(name = "handsoff") {
    init {
        subcommands(PlanCommand(host, files))
    }

    override fun help(context: Context) =
        "Work out what a hands-off run of a checkout would do, before anything runs"

    override fun helpEpilog(context: Context) = examples("handsoff")

    override fun run() = Unit
}

private class PlanCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "plan") {
    private val checkout by argument("checkout", help = "the checkout to read").default(".")
    private val root by option("--root", help = "plan this directory of the checkout").default("")
    private val job by option("--job", help = "the CI job to read, by id or name")
    private val workflow by option(
        "--workflow",
        help = "the CI file to read, from the checkout root",
    )
    private val fromCi by option(
        "--from-ci",
        help = "a case directory written by drift ci; its failing job is the one to read",
    )
    private val cell by option("--cell", metavar = "KEY=VALUE", help = "choose one matrix cell")
        .multiple()
    private val lockfile by option("--lockfile", help = "the lockfile an install follows")
    private val acceptNoLockfile by option(
        "--accept-no-lockfile",
        help = "go ahead although a manifest has no lockfile",
    ).flag()
    private val testCommand by option("--test-command", help = "the command that runs the tests")
    private val runtime by option(
        "--runtime",
        metavar = "NAME=VERSION",
        help = "the version of node, python, java, php or go to use",
    ).multiple()
    private val json by option("--json", help = "print canonical JSON instead of text").flag()
    private val out by option("--out", help = "also write the report to this file")

    override fun help(context: Context) =
        "Read a checkout and print which tools it uses, which commands CI ran, where they would " +
            "run and what the plan would not do. Nothing is run and nothing is fetched"

    override fun helpEpilog(context: Context) = examples("handsoff plan")

    override fun run() {
        val request = HandsoffRequest(
            root.trim('/'),
            job,
            workflow,
            fromCi,
            cell.map { pair("--cell", it) },
            lockfile,
            acceptNoLockfile,
            testCommand,
            runtime.map { pair("--runtime", it) }.onEach {
                if (it.first !in TOOLS) {
                    throw UsageError("--runtime takes node, python, java, php or go, got $it")
                }
            },
        )
        val plan = Handsoff.plan(HostCheckout(host, checkout), checkout, request, files::read)
        val text = if (json) {
            CanonicalJson.encode(HandsoffRender.json(plan)) + "\n"
        } else {
            HandsoffRender.text(plan)
        }
        out?.let { if (!files.write(it, text)) throw CliktError("cannot write file: $it") }
        if (json) rawEcho(text.trimEnd('\n')) else echo(text, trailingNewline = false)
        if (!plan.ready) throw ProgramResult(1)
    }

    private fun pair(option: String, text: String): Pair<String, String> {
        val at = text.indexOf('=')
        if (at < 1 || at == text.length - 1) throw UsageError("$option takes KEY=VALUE, got $text")
        return text.substring(0, at) to text.substring(at + 1)
    }

    private companion object {
        val TOOLS = setOf("node", "python", "java", "php", "go")
    }
}
