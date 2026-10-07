package dev.gmitch215.drift.host

import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidHostTest {
	private val host = systemHost()

	@Test
	fun readsBuildFacts() {
		val facts = host.facts().associateBy { it.path }
		assertEquals("android", host.platform)
		assertEquals(Build.VERSION.SDK_INT.toString(), facts.getValue("os.api-level").value)
		assertEquals(Build.MODEL, facts.getValue("hw.model").value)
		assertEquals(Build.VERSION.RELEASE, facts.getValue("os.version").value)
	}

	@Test
	fun readsProcFactsWithoutPrivileges() {
		val facts = host.facts().associateBy { it.path }
		assertTrue(facts.getValue("hw.memory.total").value.toLong() > 0)
		assertTrue(facts.getValue("hw.cpu.logical").value.toInt() > 0)
	}

	@Test
	fun runsCommandsAndReadsFiles() {
		assertEquals("ok", host.run(listOf("echo", "ok"))?.output?.trim())
		assertNotNull(host.readText("/proc/meminfo"))
		assertEquals(null, host.readText("/nonexistent/drift"))
	}
}
