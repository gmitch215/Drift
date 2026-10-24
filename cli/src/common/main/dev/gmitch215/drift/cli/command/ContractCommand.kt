package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.varargValues
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.ci.JUnitCheck
import dev.gmitch215.drift.ci.JUnitStatus
import dev.gmitch215.drift.ci.JUnitXml
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.contract.AssertionKind
import dev.gmitch215.drift.contract.AssertionResult
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.contract.ContractResult
import dev.gmitch215.drift.contract.Holds
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.scan.probe.Catalog

internal const val NO_SEPARATION = 3

private const val VERIFY_SUITE = "drift contract verify"

class ContractCommand(host: Host, files: CaseFiles) : CliktCommand(name = "contract") {
    init {
        subcommands(ContractCreateCommand(host, files), ContractVerifyCommand(host, files))
    }

    override fun help(context: Context) =
        "Write what a program needs from an environment and check other environments against it"

    override fun helpEpilog(context: Context) = examples("contract")

    override fun run() = Unit
}

private class ContractCreateCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "create") {
    override val treatUnknownOptionsAsArgs = true

    private val from by option("--from", metavar = "CAPSULE", help = "capsules of passing runs")
        .varargValues(min = 1).required()
    private val red by option("--red", metavar = "CAPSULE", help = "capsules of failing runs")
        .varargValues(min = 1)
    private val out by option("-o", "--out", help = "write the contract here, not to stdout")

    override fun help(context: Context) =
        "Find the fewest assertions that separate the passing capsules from the failing ones"

    override fun helpEpilog(context: Context) = examples("contract create")

    override fun run() {
        val greens = from.map { loadCapsule(host, it) }
        val reds = red.orEmpty().map { loadCapsule(host, it) }
        when (val result = Contract.create(greens, reds)) {
            is ContractResult.Inseparable -> throw CliktError(
                "no separating set: red capsule(s) ${result.reds.joinToString(", ")} " +
                    "contradict none of the ${result.candidates} values every green shares",
                statusCode = NO_SEPARATION,
            )

            is ContractResult.Created -> {
                val contract = result.contract
                val text = contract.render()
                val path = out
                if (path == null) {
                    rawEcho(text)
                } else if (!files.write(path, text + "\n")) {
                    throw CliktError("cannot write contract file: $path")
                }
                echo(summary(result, greens.size, reds.size), err = true)
                if (path != null) echo("wrote $path", err = true)
            }
        }
    }

    private fun summary(result: ContractResult.Created, greens: Int, reds: Int): String {
        val n = result.contract.assertions.size
        return if (reds == 0) {
            "contract: $n values are constant across $greens green capsule(s); all are " +
                "coincidental until a red capsule discriminates them"
        } else {
            "contract: $n assertion(s) separate $greens green capsule(s) from $reds red " +
                "capsule(s); no assertion can be removed (${result.candidates} shared values, " +
                "${result.calls} ddmin tests)"
        }
    }
}

private class ContractVerifyCommand(private val host: Host, private val files: CaseFiles) :
    CliktCommand(name = "verify") {
    private val contractFile by argument("contract", help = "a contract written by contract create")
    private val capsuleFile by argument("capsule", help = "capsule of the environment to check")
        .optional()
    private val capture by option("--capture", help = "capture this environment and check it")
        .flag()
    private val json by option("--json", help = "print the report as canonical JSON").flag()
    private val junit by option("--junit", metavar = "FILE", help = "also write a JUnit XML report")

    override fun help(context: Context) =
        "Report which assertions of a contract hold, break or cannot be checked in an environment"

    override fun helpEpilog(context: Context) = examples("contract verify")

    override fun run() {
        if (capture == (capsuleFile != null)) {
            throw UsageError("give either a capsule file or --capture")
        }
        val text = host.readText(contractFile)
            ?: throw CliktError("cannot read contract file: $contractFile")
        val contract = try {
            Contract.parse(text)
        } catch (e: JsonException) {
            throw CliktError("invalid contract file $contractFile: ${e.message}")
        }
        val environment = capsuleFile?.let { loadCapsule(host, it) } ?: captureHere(contract)
        val report = contract.verify(environment)
        if (json) rawEcho(report.json()) else echo(report.text())
        val path = junit
        if (path != null) {
            val xml = JUnitXml.render(VERIFY_SUITE, report.results.map { it.toJUnit() })
            if (!files.write(path, xml)) throw CliktError("cannot write JUnit report: $path")
        }
        val broken = report.first ?: return
        throw CliktError("contract broken: ${broken.assertion.kind.id} ${broken.assertion.id}")
    }

    private fun AssertionResult.toJUnit(): JUnitCheck {
        val a = assertion
        val status = when (finding.status) {
            Holds.HOLDS -> JUnitStatus.PASS
            Holds.BREAKS -> JUnitStatus.FAIL
            Holds.UNVERIFIABLE -> JUnitStatus.SKIP
        }
        val message = when (finding.status) {
            Holds.HOLDS -> "expected ${a.expect}"
            Holds.BREAKS -> "expected ${a.expect}, found ${finding.found.orEmpty()}"
            Holds.UNVERIFIABLE -> "cannot be checked in this environment"
        }
        return JUnitCheck("${a.kind.id} ${a.id}", status, message)
    }

    private fun captureHere(contract: Contract): Capsule {
        val probeIds = contract.assertions.filter { it.kind == AssertionKind.PROBE }.map { it.id }
        return Capture.run(
            host,
            "capture",
            probes = Catalog.all.filter { it.id in probeIds },
            tools = contract.assertions.any { it.id.startsWith("tool.") },
            project = contract.assertions.any { it.id.startsWith("project.") },
        )
    }
}
