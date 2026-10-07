package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.scan.Capture
import dev.gmitch215.drift.scan.probe.Catalog

class CaptureCommand(private val host: Host) : CliktCommand(name = "capture") {
	private val label by option("--label", help = "name stored in the capsule").default("capture")
	private val tools by option("--tools", help = "also run the toolchain scanners").flag()
	private val probes by option("--probes", help = "also run every probe").flag()
	private val keepIdentifiers by option(
		"--keep-identifiers",
		help = "keep the account name, host name and home path (local use only)",
	).flag()

	override fun help(context: Context) = "Print this environment's capsule as canonical JSON"

	override fun run() {
		if (keepIdentifiers) {
			echo("warning: the capsule keeps your account, host name and home path", err = true)
		}
		rawEcho(
			Capture.run(
				host,
				label,
				probes = if (probes) Catalog.all else emptyList(),
				tools = tools,
				keepIdentifiers = keepIdentifiers,
			).canonical(),
		)
	}
}
