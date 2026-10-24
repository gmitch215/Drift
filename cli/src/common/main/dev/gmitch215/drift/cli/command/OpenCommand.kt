package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.host.Host

class OpenCommand(
    private val host: Host,
    private val files: CaseFiles,
    private val archives: ArchiveFiles,
) : CliktCommand(name = "open") {
    private val file by argument("file", help = "a .driftcase archive")

    override fun help(context: Context) =
        "Open a .driftcase in Drift Studio, or write its report next to it without Studio"

    override fun helpEpilog(context: Context) = examples("open")

    override fun run() {
        files.refuseCaseDirectory(file)
        val bytes = archives.read(file) ?: throw CliktError("cannot read archive: $file")
        if (launchStudio()) {
            echo("opened $file in Drift Studio")
            return
        }
        val html = reportHtml(file, bytes)
        val out = file.removeSuffix(CASE_EXTENSION) + ".report.html"
        if (!files.write(out, html)) throw CliktError("cannot write $out")
        echo("Drift Studio is not installed; wrote $out")
    }

    private fun launchStudio(): Boolean {
        val os = host.os.lowercase()
        val target = literalPath(os.startsWith("windows"))
        return when {
            os == "macos" -> host.run(listOf("open", "-a", "Drift", target))?.exitCode == 0

            os.startsWith("windows") ->
                host.run(listOf("reg", "query", "HKCR\\$CASE_EXTENSION"))?.exitCode == 0 &&
                    host.run(listOf("explorer.exe", target)) != null

            else -> {
                val handler = host.run(listOf("xdg-mime", "query", "default", MIME_TYPE))
                handler?.exitCode == 0 && handler.output.isNotBlank() &&
                    host.run(listOf("xdg-open", target))?.exitCode == 0
            }
        }
    }

    private fun literalPath(windows: Boolean): String {
        val rooted = file.startsWith("/") || file.startsWith("\\") ||
            (windows && file.getOrNull(1) == ':')
        return if (rooted) file else (if (windows) ".\\" else "./") + file
    }

    private companion object {
        const val CASE_EXTENSION = ".driftcase"
        const val MIME_TYPE = "application/x-driftcase"
    }
}
