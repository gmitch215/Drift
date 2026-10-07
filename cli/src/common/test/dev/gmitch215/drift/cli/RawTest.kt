package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test as cliktTest

internal class RawBuffer : RawOut {
	val text = StringBuilder()

	override fun write(text: String) {
		this.text.append(text)
	}
}

/** Like Clikt's test, with the raw machine output appended to stdout. */
internal fun CliktCommand.test(argv: String): CliktCommandTestResult {
	val raw = RawBuffer()
	val result = context { obj = raw }.cliktTest(argv)
	return CliktCommandTestResult(
		output = result.output + raw.text,
		stdout = result.stdout + raw.text,
		stderr = result.stderr,
		statusCode = result.statusCode,
	)
}
