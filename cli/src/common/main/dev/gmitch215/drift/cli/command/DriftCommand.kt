package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.versionOption
import dev.gmitch215.drift.DRIFT_VERSION
import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.cli.HostArchiveFiles
import dev.gmitch215.drift.cli.HostCaseFiles
import dev.gmitch215.drift.cli.install.InstallSystem
import dev.gmitch215.drift.cli.install.systemInstall
import dev.gmitch215.drift.cli.serve.ServeSystem
import dev.gmitch215.drift.cli.serve.systemServe
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.systemHost

class DriftCommand(
	host: Host = systemHost(),
	files: CaseFiles = HostCaseFiles(host),
	archives: ArchiveFiles = HostArchiveFiles(),
	install: InstallSystem = systemInstall(),
	serve: ServeSystem = systemServe(),
) : CliktCommand(name = "drift") {
	init {
		context {
			helpFormatter = {
				MordantHelpFormatter(it, showRequiredTag = true, showDefaultValues = true)
			}
		}
		versionOption("$DRIFT_VERSION (${host.platform})")
		subcommands(
			CaptureCommand(host),
			DiagnoseCommand(host, files),
			NextCommand(files),
			CaseCommand(files),
			ReproduceCommand(host, files),
			AtlasCommand(host, files),
			SolveCommand(host, files),
			IngestCommand(host, files),
			PackCommand(files, archives),
			VerifyCommand(archives, files),
			ReportCommand(files, archives),
			InstallCommand(host, install),
			UninstallCommand(host, install),
			ServeCommand(host, install, serve),
		)
	}

	override fun run() = Unit
}
