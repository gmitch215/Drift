package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertTrue

class MacosFactsTest {
	@Test
	fun systemHostReadsSysctlFacts() {
		val facts = systemHost().facts().associateBy { it.path }
		assertTrue(facts.getValue("hw.cpu.model").value.isNotBlank())
		assertTrue(facts.getValue("hw.memory.total").value.toLong() > 0)
		assertTrue(facts.getValue("hw.cpu.logical").value.toInt() > 0)
		assertTrue(facts.values.all { it.source == "sysctl" })
	}
}
