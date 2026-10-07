package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosHostTest {
	private val host = systemHost()

	@Test
	fun declaresOnlyWhatTheSandboxAllows() {
		assertEquals("ios", host.platform)
		assertEquals(setOf(Capability.FILES, Capability.ENV), host.capabilities)
		assertNull(host.run(listOf("echo", "x")))
	}

	@Test
	fun readsFactsFromFoundationAndSysctl() {
		val facts = host.facts().associateBy { it.path }
		assertTrue(facts.getValue("hw.memory.total").value.toLong() > 0)
		assertTrue(facts.getValue("hw.cpu.logical").value.toInt() > 0)
		assertTrue(facts.getValue("os.version").value.isNotBlank())
		assertTrue(facts.getValue("os.build").value.isNotBlank())
		val thermal = facts.getValue("hw.thermal-state").value
		assertTrue(thermal in setOf("nominal", "fair", "serious", "critical"))
	}

	@Test
	fun readsTextFromAnExistingFileOnly() {
		assertNull(host.readText("/nonexistent/drift"))
		assertTrue(host.readText("/etc/hosts")?.isNotEmpty() == true)
	}
}
