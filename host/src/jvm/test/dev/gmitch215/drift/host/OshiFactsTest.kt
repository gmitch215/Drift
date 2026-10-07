package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Stability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OshiFactsTest {
	private val facts = systemHost().facts().associateBy { it.path }

	@Test
	fun reportsCpuMemoryAndOsFacts() {
		assertTrue(facts.getValue("hw.cpu.model").value.isNotBlank())
		assertTrue(facts.getValue("hw.cpu.logical").value.toInt() > 0)
		assertTrue(facts.getValue("hw.memory.total").value.toLong() > 0)
		assertTrue(facts.getValue("hw.memory.available").value.toLong() > 0)
		assertTrue(facts.getValue("hw.processes.count").value.toInt() > 0)
		assertTrue(facts.getValue("os.build").value.isNotBlank())
		assertTrue(facts.values.all { it.source == "oshi" })
	}

	@Test
	fun readingsAreVolatileAndIdentityIsStatic() {
		assertEquals(Stability.STATIC, facts.getValue("hw.memory.total").stability)
		assertEquals(Stability.STATIC, facts.getValue("hw.cpu.model").stability)
		assertEquals(Stability.VOLATILE, facts.getValue("hw.memory.available").stability)
		assertEquals(Stability.VOLATILE, facts.getValue("hw.uptime.seconds").stability)
	}

	@Test
	fun cachesAreNamedByLevelAndType() {
		val caches = facts.keys.filter { it.startsWith("hw.cpu.cache.") }
		val shape = Regex("hw\\.cpu\\.cache\\.l\\d[di]?(\\.\\d+)?")
		assertTrue(caches.all { shape.matches(it) }, "$caches")
	}
}
