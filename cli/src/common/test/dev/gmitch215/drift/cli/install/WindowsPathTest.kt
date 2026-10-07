package dev.gmitch215.drift.cli.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsPathTest {
	private val env = mapOf("SystemRoot" to "C:\\Windows", "LocalAppData" to "C:\\Users\\u\\L")

	@Test
	fun containsComparesCaseSlashesQuotesTrailingBackslashAndVariables() {
		val raw = "C:\\Windows\\system32;\"C:\\Tools\\\";%SystemRoot%\\bin;;%LOCALAPPDATA%\\Drift"
		assertTrue(WindowsPath.contains(raw, "c:\\windows\\SYSTEM32", env))
		assertTrue(WindowsPath.contains(raw, "C:/Tools", env))
		assertTrue(WindowsPath.contains(raw, "C:\\Windows\\bin", env))
		assertTrue(WindowsPath.contains(raw, "C:\\Users\\u\\L\\Drift\\", env))
		assertFalse(WindowsPath.contains(raw, "C:\\Windows", env))
		assertFalse(WindowsPath.contains("", "C:\\x", env))
		assertFalse(WindowsPath.contains(";;", "", env))
	}

	@Test
	fun expandReplacesKnownNamesAndKeepsTheRest() {
		assertEquals("C:\\Windows\\x", WindowsPath.expand("%systemroot%\\x", env))
		assertEquals("%NOPE%\\x", WindowsPath.expand("%NOPE%\\x", env))
		assertEquals("100%", WindowsPath.expand("100%", env))
		assertEquals("%%", WindowsPath.expand("%%", env))
	}

	@Test
	fun addThenRemoveRestoresTheRawValueExactly() {
		val dir = "C:\\Drift"
		for (raw in listOf("", "A", "A;B", "A;B;", ";", ";;", "%X%\\a;B", "A;;")) {
			val added = WindowsPath.add(raw, dir)
			assertTrue(added.value.endsWith(dir), added.value)
			val removed = WindowsPath.remove(added.value, dir, added.separator, env)
			assertEquals(raw, removed, "raw $raw")
		}
	}

	@Test
	fun addUsesNoSeparatorOnlyAfterAnEmptyValueOrATrailingSemicolon() {
		assertEquals(WindowsPath.Added("D", ""), WindowsPath.add("", "D"))
		assertEquals(WindowsPath.Added("A;D", ";"), WindowsPath.add("A", "D"))
		assertEquals(WindowsPath.Added("A;D", ""), WindowsPath.add("A;", "D"))
	}

	@Test
	fun removeTakesTheLastMatchOnlyAndKeepsLaterEntries() {
		assertEquals("D;A;B", WindowsPath.remove("D;A;D;B", "D", ";", env))
		assertEquals("A;B;C", WindowsPath.remove("A;B;D;C", "d", "", env))
		assertNull(WindowsPath.remove("A;B", "D", ";", env))
		assertNull(WindowsPath.remove("", "D", ";", env))
	}
}
