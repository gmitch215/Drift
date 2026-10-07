package dev.gmitch215.drift.build

import kotlin.test.Test
import kotlin.test.assertEquals

class LineLengthTest {
	@Test
	fun tabsExpandToTheNextStop() {
		assertEquals(4, LineLength.width("\t", 4))
		assertEquals(5, LineLength.width("\tx", 4))
		assertEquals(8, LineLength.width("ab\t\t", 4))
		assertEquals(4, LineLength.width("abc\t", 4))
	}

	@Test
	fun supplementaryCharactersCountOnce() {
		assertEquals(3, LineLength.width("a😀b", 4))
	}

	@Test
	fun exactLimitPassesAndOneOverFails() {
		val ok = "a".repeat(100)
		assertEquals(emptyList(), LineLength.violations("A.kt", ok, 100, 4))
		assertEquals(
			listOf("A.kt:2 exceeds 100 columns"),
			LineLength.violations("A.kt", "x\n$ok" + "a", 100, 4),
		)
	}

	@Test
	fun tabIndentCountsAsFourColumns() {
		val line = "\t" + "a".repeat(97)
		assertEquals(1, LineLength.violations("A.kt", line, 100, 4).size)
		assertEquals(0, LineLength.violations("A.kt", "\t" + "a".repeat(96), 100, 4).size)
	}

	@Test
	fun carriageReturnsAreNotCounted() {
		assertEquals(emptyList(), LineLength.violations("A.kt", "a".repeat(100) + "\r\n", 100, 4))
	}

	@Test
	fun editorConfigReadsTheGlobalSectionOnly() {
		val text = "root = true\n\n[*]\nmax_line_length = 100\ntab_width = 4\n" +
			"\n[*.kt]\nmax_line_length = 80\n"
		assertEquals(mapOf("max_line_length" to "100", "tab_width" to "4"), EditorConfig.global(text))
	}
}
