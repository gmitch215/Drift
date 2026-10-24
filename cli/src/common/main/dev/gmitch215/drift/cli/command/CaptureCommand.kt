package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.ci.CapsuleDirectories
import dev.gmitch215.drift.ci.CapsuleDirectory
import dev.gmitch215.drift.cli.HostCaseFiles
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.scan.probe.Catalog

class CaptureCommand(private val host: Host, private val files: CaseFiles = HostCaseFiles(host)) :
    CliktCommand(name = "capture") {
    private val label by option("--label", help = "name stored in the capsule").default("capture")
    private val tools by option("--tools", help = "also run the toolchain scanners").flag()
    private val project by option(
        "--project",
        help = "also read dependency versions from the manifests in the working directory",
    ).flag()
    private val probes by option("--probes", help = "also run every probe").flag()
    private val keepIdentifiers by option(
        "--keep-identifiers",
        help = "keep the account name, host name and home path (local use only)",
    ).flag()
    private val ssh by option(
        "--ssh",
        metavar = "HOST",
        help = "capture the machine HOST over ssh with read-only commands; its host key must " +
            "already be in your known_hosts",
    )
    private val out by option(
        "--out",
        metavar = "DIR",
        help = "write capsule.json into DIR instead of printing it",
    )

    override fun help(context: Context) = "Print this environment's capsule as canonical JSON"

    override fun helpEpilog(context: Context) = examples("capture")

    override fun run() {
        if (keepIdentifiers) {
            echo("warning: the capsule keeps your account, host name and home path", err = true)
        }
        val remote = ssh
        val capsule = if (remote == null) {
            Capture.run(
                host,
                label,
                probes = if (probes) Catalog.all else emptyList(),
                tools = tools,
                project = project,
                keepIdentifiers = keepIdentifiers,
            )
        } else {
            if (tools || probes || project) {
                throw UsageError(
                    "--tools, --probes and --project run many more commands and are not " +
                        "available with --ssh in this version",
                )
            }
            captureOverSsh(host, remote, label, keepIdentifiers)
        }
        val dir = out
        if (dir == null) {
            rawEcho(capsule.canonical())
            return
        }
        CapsuleDirectories.write(files, dir, CapsuleDirectory(capsule))
            ?.let { throw CliktError("cannot write $it; check the directory") }
        echo("wrote capsule to $dir", err = true)
    }
}
