package dev.gmitch215.drift.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.Detail

class NextCommand(private val files: CaseFiles) : CliktCommand(name = "next") {
	private val dir by argument("case-dir", help = "a case directory written by diagnose --case")
	private val detail by option("--detail", help = "how much to print")
		.choice("summary", "detail", "full").default("detail")

	override fun help(context: Context) =
		"Print the next proposed experiment of a case, and the manual ones with instructions"

	override fun run() {
		val case = loadCase(files, dir)
		case.requireIntact(dir)
		echo(case.render(dir, Detail.parse(detail)!!, next = true), trailingNewline = false)
	}
}
