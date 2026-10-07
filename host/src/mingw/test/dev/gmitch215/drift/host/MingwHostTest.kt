package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MingwHostTest {
	@Test
	fun envComesFromTheProcessEnvironmentBlock() {
		val env = systemHost().env()
		assertTrue(env.isNotEmpty())
		assertTrue(env.keys.none { it.startsWith("=") })
		assertEquals(env.keys.sorted(), env.keys.toList())
	}
}
