package dev.gmitch215.drift.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class WriteStdoutTest {
	private fun captured(text: String): ByteArray {
		val saved = System.out
		val sink = ByteArrayOutputStream()
		System.setOut(PrintStream(sink, false))
		try {
			writeStdout(text)
		} finally {
			System.setOut(saved)
		}
		return sink.toByteArray()
	}

	@Test
	fun writesUtf8BytesWithoutTranslatingAnySeparator() {
		val text = "a\u0085b c d\r\n\te\u0000f${Char(0xE9)}😀"
		assertEquals(text.encodeToByteArray().toList(), captured(text).toList())
	}

	@Test
	fun writesAMegabyteLineAndNothingForEmptyText() {
		val line = "x".repeat(1 shl 20) + " "
		assertEquals(line.encodeToByteArray().size, captured(line).size)
		assertEquals(0, captured("").size)
	}
}
