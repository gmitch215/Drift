package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.testing.test
import dev.gmitch215.drift.case.CaseRead
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteTextFileTest {
	private val files = HostCaseFiles(systemHost())

	private fun temp(): File = File.createTempFile("drift-case", "").also {
		it.delete()
		it.deleteOnExit()
	}

	@Test
	fun writesNestedFilesAsUtf8WithoutTranslation() {
		val dir = temp()
		val text = "a\nb\r\n${Char(0xE9)}${Char(0x20AC)}"
		assertTrue(files.write("$dir/x/y/z.json", text))
		val written = File("$dir/x/y/z.json").readBytes().toList()
		assertEquals(text.encodeToByteArray().toList(), written)
		assertEquals(text, files.read("$dir/x/y/z.json"))
		dir.deleteRecursively()
	}

	@Test
	fun failsWhenThePathIsNotWritable() {
		val blocker = temp().also { it.writeText("file") }
		assertFalse(files.write("$blocker/child.json", "x"))
		blocker.delete()
	}

	@Test
	fun aCaseSurvivesTheRealFilesystem() {
		val dir = temp()
		val host = FakeHost(
			canRun = false,
			files = mapOf(
				"green.json" to Fixtures.text("36702683742.json"),
				"red.json" to Fixtures.text("36995781138.json"),
			),
		)
		val out = "${dir.invariantSeparatorsPath}/out"
		val result = DriftCommand(host, files).test("diagnose green.json red.json --case $out")
		assertEquals(0, result.statusCode, result.stderr)
		val read = CaseStore.read(files, "$dir/out")
		assertTrue(read is CaseRead.Loaded, read.toString())
		val back = read.case
		assertEquals(emptyList(), back.check())
		assertEquals(11, back.files.size)
		assertEquals(back.files.getValue("case.json"), File("$dir/out/case.json").readText())
		dir.deleteRecursively()
	}
}
