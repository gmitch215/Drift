package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvBlockTest {
	private fun block(vararg entries: String) =
		(entries.joinToString("\u0000") + "\u0000\u0000").toCharArray()

	@Test
	fun parsesSortedPairsAndSkipsHiddenVariables() {
		val env = parseEnvBlock(block("=C:=C:\\", "PATH=C:\\bin;D:\\x", "ALPHA=1=2", "=::=::\\"))
		assertEquals(listOf("ALPHA", "PATH"), env.keys.toList())
		assertEquals("1=2", env.getValue("ALPHA"))
		assertEquals("C:\\bin;D:\\x", env.getValue("PATH"))
	}

	@Test
	fun keepsEmptyValuesAndNamesWithoutEquals() {
		val env = parseEnvBlock(block("EMPTY=", "BARE"))
		assertEquals(mapOf("BARE" to "", "EMPTY" to ""), env)
	}

	@Test
	fun keepsNonBmpCharacters() {
		val env = parseEnvBlock(block("EMOJI=\uD83D\uDE00x"))
		assertEquals("\uD83D\uDE00x", env.getValue("EMOJI"))
	}

	@Test
	fun parsesUnterminatedBlocks() {
		assertEquals(mapOf("A" to "1", "B" to "2"), parseEnvBlock("A=1\u0000B=2".toCharArray()))
	}

	@Test
	fun emptyBlocksYieldNothing() {
		assertEquals(emptyMap(), parseEnvBlock(CharArray(0)))
		assertEquals(emptyMap(), parseEnvBlock("\u0000\u0000".toCharArray()))
	}

	@Test
	fun stopsAtTheTerminator() {
		val env = parseEnvBlock("A=1\u0000\u0000B=2\u0000\u0000".toCharArray())
		assertEquals(mapOf("A" to "1"), env)
	}
}
