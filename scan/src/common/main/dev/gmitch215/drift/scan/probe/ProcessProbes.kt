package dev.gmitch215.drift.scan.probe

import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.Host

private val PROCESS = setOf(Capability.PROCESS)

private fun ProbeContext.shell(label: String, script: String) {
	val windows = host.os.startsWith("windows")
	val argv = if (windows) listOf("cmd", "/c", script) else listOf("sh", "-c", script)
	report(label, host.run(argv))
}

private fun ProbeContext.report(label: String, result: CommandResult?) {
	if (result == null) {
		line(label, "no-process")
	} else {
		line(
			label,
			"exit=${result.exitCode} out=${result.output.replace(
				"\r",
				"\\r",
			).replace("\n", "\\n")}",
		)
	}
}

private fun Host.posix() = !os.startsWith("windows")

internal val processProbes = listOf(
	Probe("process.echo", Family.PROCESS, PROCESS) {
		report("echo", host.run(listOf("echo", "drift")))
	},
	Probe("process.exit-code", Family.PROCESS, PROCESS) {
		shell("exit-3", "exit 3")
		shell("exit-0", "exit 0")
	},
	Probe("process.stderr-merged", Family.PROCESS, PROCESS) {
		shell(
			"both-streams",
			if (host.posix()) "echo out; echo err 1>&2" else "echo out& echo err 1>&2",
		)
	},
	Probe("process.missing-command", Family.PROCESS, PROCESS) {
		val result = host.run(listOf("drift-no-such-binary-4f1c"))
		line("nonzero-or-null", result == null || result.exitCode != 0)
	},
	Probe("process.quoting", Family.PROCESS, PROCESS) {
		if (host.posix()) {
			report("spaces-and-quotes", host.run(listOf("printf", "%s", "a b'c\"d \$HOME *")))
		} else {
			line("spaces-and-quotes", "skipped-on-windows")
		}
	},
	Probe("process.large-output", Family.PROCESS, PROCESS) {
		if (host.posix()) {
			val result = host.run(
				listOf(
					"sh",
					"-c",
					"i=0; while [ \$i -lt 30000 ]; do echo line-\$i; i=\$((i+1)); done",
				),
			)
			line("lines", result?.output?.lines()?.count { it.isNotEmpty() })
			line("exit", result?.exitCode)
		} else {
			line("lines", "skipped-on-windows")
		}
	},
	Probe("process.env-visible", Family.PROCESS, PROCESS) {
		if (host.posix()) {
			val home = host.env()["HOME"]
			val seen = host.run(listOf("sh", "-c", "printf %s \"\$HOME\""))?.output
			line("home-matches", home == seen)
		} else {
			line("home-matches", "skipped-on-windows")
		}
	},
	Probe("process.timezone", Family.PROCESS, PROCESS) {
		val result = host.run(
			if (host.posix()) listOf("date", "+%Z") else listOf("cmd", "/c", "echo %TZ%"),
		)
		line("zone", result?.output?.trim())
	},
	Probe("process.resolve-localhost", Family.PROCESS, PROCESS) {
		val result = host.run(listOf("getent", "hosts", "localhost"))
		line("resolves", result != null && result.exitCode == 0 && result.output.isNotBlank())
	},
)
