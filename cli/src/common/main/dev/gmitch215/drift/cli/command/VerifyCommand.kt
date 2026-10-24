package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.ci.JUnitCheck
import dev.gmitch215.drift.ci.JUnitStatus
import dev.gmitch215.drift.ci.JUnitXml
import dev.gmitch215.drift.lab.Check
import dev.gmitch215.drift.lab.CheckStatus
import dev.gmitch215.drift.lab.Verify

private const val SUITE = "drift verify"

class VerifyCommand(private val archives: ArchiveFiles, private val files: CaseFiles) :
    CliktCommand(name = "verify") {
    private val file by argument("file", help = "a .driftcase archive")
    private val json by option("--json", help = "print the report as canonical JSON").flag()
    private val junit by option("--junit", metavar = "FILE", help = "also write a JUnit XML report")

    override fun help(context: Context) =
        "Re-derive everything a .driftcase records from its own data; experiments are not re-run"

    override fun helpEpilog(context: Context) = examples("verify")

    override fun run() {
        files.refuseCaseDirectory(file)
        val bytes = archives.read(file) ?: throw CliktError("cannot read archive: $file")
        val report = Verify.open(bytes)
        echo(if (json) report.json() else report.text())
        val path = junit
        if (path != null) {
            val xml = JUnitXml.render(SUITE, report.checks.map { it.toJUnit() })
            if (!files.write(path, xml)) throw CliktError("cannot write JUnit report: $path")
        }
        val first = report.first ?: return
        throw CliktError("verification failed: ${first.line()}")
    }

    private fun Check.toJUnit() = JUnitCheck(
        id,
        when (status) {
            CheckStatus.PASS -> JUnitStatus.PASS
            CheckStatus.FAIL -> JUnitStatus.FAIL
            CheckStatus.SKIP -> JUnitStatus.SKIP
        },
        line(),
    )
}
