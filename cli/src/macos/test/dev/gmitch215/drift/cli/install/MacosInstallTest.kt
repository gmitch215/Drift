@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.test
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.X_OK
import platform.posix.access
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosInstallTest {
	private val system = systemInstall()

	private fun scratch(): String {
		val tmp = (systemHost().env()["TMPDIR"] ?: "/tmp").trimEnd('/')
		return "$tmp/drift-install-" + Random.nextLong().toULong().toString(16)
	}

	@Test
	fun executableFilesGetTheExecuteBit() {
		val base = scratch()
		assertTrue(system.makeDirectory(base))
		assertTrue(system.write("$base/plain", byteArrayOf(1)))
		assertTrue(system.write("$base/tool", byteArrayOf(1), executable = true))
		assertTrue(access("$base/plain", X_OK) != 0)
		assertEquals(0, access("$base/tool", X_OK))
		assertTrue(system.remove("$base/plain"))
		assertTrue(system.remove("$base/tool"))
		assertTrue(system.removeDirectory(base))
	}

	@Test
	fun theWholeFlowRunsAgainstAScratchHomeAndLeavesNothingBehind() {
		val home = scratch()
		assertTrue(system.makeDirectory(home))
		assertTrue(system.write("$home/.zshenv", "export A=1".encodeToByteArray()))
		val host = FakeHost(
			os = "macosx",
			env = mapOf("HOME" to home, "PATH" to "/usr/bin:/bin", "SHELL" to "/bin/zsh"),
		)
		val drift = DriftCommand(host, install = system)
		val dry = drift.test("install --dry-run")
		assertEquals(0, dry.statusCode, dry.stderr)
		assertFalse(system.exists("$home/.local"))
		val done = drift.test("install")
		assertEquals(0, done.statusCode, done.stderr)
		val installed = assertNotNull(system.read("$home/.local/bin/drift"))
		assertContentEquals(system.read(assertNotNull(system.selfPath())), installed)
		assertEquals(0, access("$home/.local/bin/drift", X_OK))
		val profile = assertNotNull(system.read("$home/.zshenv")).decodeToString()
		assertEquals(1, profile.split(ProfileBlock.START).size - 1)
		val again = drift.test("install")
		assertEquals(0, again.statusCode, again.stderr)
		assertEquals(profile, system.read("$home/.zshenv")?.decodeToString())
		val gone = drift.test("uninstall")
		assertEquals(0, gone.statusCode, gone.stderr)
		assertEquals("export A=1", system.read("$home/.zshenv")?.decodeToString())
		assertFalse(system.exists("$home/.local"))
		assertFalse(system.exists("$home/.config"))
		assertTrue(system.remove("$home/.zshenv"))
		assertTrue(system.removeDirectory(home))
	}
}
