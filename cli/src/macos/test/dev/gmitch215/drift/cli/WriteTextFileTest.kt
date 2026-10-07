package dev.gmitch215.drift.cli

import dev.gmitch215.drift.host.systemHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteTextFileTest {
	private val host = systemHost()
	private val base = (host.env()["TMPDIR"] ?: "/tmp").trimEnd('/') + "/drift-write-test"

	@Test
	fun writesNestedFilesAsUtf8WithoutTranslation() {
		val text = "a\nb\r\n${Char(0xE9)}${Char(0x20AC)}"
		assertTrue(writeTextFile("$base/x/y/z.json", text))
		assertEquals(text, host.readText("$base/x/y/z.json"))
		assertTrue(writeTextFile("$base/x/y/empty.json", ""))
		assertEquals("", host.readText("$base/x/y/empty.json"))
		host.run(listOf("rm", "-r", base))
	}

	@Test
	fun failsWhenThePathIsNotWritable() {
		assertFalse(writeTextFile("/dev/null/child.json", "x"))
	}
}
