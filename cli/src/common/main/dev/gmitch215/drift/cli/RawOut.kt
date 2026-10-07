package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject

/** Receives machine output byte for byte; tests install one through the root context. */
fun interface RawOut {
	fun write(text: String)
}

/** Writes [text] to standard output as UTF-8 with no line-break translation. */
expect fun writeStdout(text: String)

/** Clikt's echo turns some Unicode separators into line breaks, so JSON and transcripts skip it. */
internal fun CliktCommand.rawEcho(text: String, trailingNewline: Boolean = true) {
	val all = if (trailingNewline) text + "\n" else text
	val out = currentContext.findObject<RawOut>()
	if (out != null) out.write(all) else writeStdout(all)
}
