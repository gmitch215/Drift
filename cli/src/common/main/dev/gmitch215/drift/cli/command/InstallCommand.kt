package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.gmitch215.drift.cli.install.InstallFailure
import dev.gmitch215.drift.cli.install.InstallOptions
import dev.gmitch215.drift.cli.install.InstallSystem
import dev.gmitch215.drift.cli.install.Installer
import dev.gmitch215.drift.cli.install.Scope
import dev.gmitch215.drift.cli.rawEcho
import dev.gmitch215.drift.host.Host

internal fun CliktCommand.runInstaller(
	host: Host,
	system: InstallSystem,
	action: (Installer) -> Unit,
) {
	val installer = Installer(host, system, { rawEcho(it) }, { echo("warning: $it", err = true) })
	try {
		action(installer)
	} catch (e: InstallFailure) {
		echo("error: ${e.message}", err = true)
		throw ProgramResult(if (e.usage) 2 else 1)
	}
}

class InstallCommand(private val host: Host, private val system: InstallSystem) :
	CliktCommand(name = "install") {
	private val user by option("--user", help = "install for the current user (the default)").flag()
	private val global by option(
		"--global",
		help = "install for every user; never uses sudo",
	).flag()
	private val dir by option("--dir", help = "absolute directory to install into")
	private val noModifyPath by option(
		"--no-modify-path",
		help = "leave shell profiles and the registry alone (same as DRIFT_NO_MODIFY_PATH=1)",
	).flag()
	private val dryRun by option("--dry-run", help = "print every change without making any").flag()

	override fun help(context: Context) =
		"Copy this executable to a directory on the user's PATH and record what changed"

	override fun run() {
		if (user && global) {
			echo("error: --user and --global cannot be combined", err = true)
			throw ProgramResult(2)
		}
		val scope = when {
			global -> Scope.GLOBAL
			user -> Scope.USER
			else -> null
		}
		val modify = !noModifyPath && host.env()["DRIFT_NO_MODIFY_PATH"] != "1"
		runInstaller(host, system) { it.install(InstallOptions(scope, dir, modify, dryRun)) }
	}
}

class UninstallCommand(private val host: Host, private val system: InstallSystem) :
	CliktCommand(name = "uninstall") {
	private val dryRun by option("--dry-run", help = "print every change without making any").flag()

	override fun help(context: Context) =
		"Remove what drift install recorded in its receipt, and nothing else"

	override fun run() = runInstaller(host, system) { it.uninstall(dryRun) }
}
