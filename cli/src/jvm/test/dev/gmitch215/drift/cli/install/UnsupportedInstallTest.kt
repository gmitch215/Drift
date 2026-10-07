package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.test
import dev.gmitch215.drift.host.FakeHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class UnsupportedInstallTest {
	@Test
	fun everyOperationDeclines() {
		val system = UnsupportedInstall
		assertNull(system.selfPath())
		assertFalse(system.isPrivileged())
		assertFalse(system.exists("/"))
		assertFalse(system.isDirectory("/"))
		assertFalse(system.canWrite("/"))
		assertNull(system.read("/etc/hosts"))
		assertFalse(system.write("/x", ByteArray(0)))
		assertFalse(system.rename("/x", "/y"))
		assertFalse(system.remove("/x"))
		assertFalse(system.makeDirectory("/x"))
		assertFalse(system.removeDirectory("/x"))
		assertNull(system.readPath(false))
		assertFalse(system.writePath(true, null))
		system.broadcastEnvironment()
	}

	@Test
	fun theJvmBuildIsNotSelfInstallable() {
		assertSame(UnsupportedInstall, systemInstall())
		val host = FakeHost(platform = "jvm", env = mapOf("HOME" to "/home/u"))
		val result = DriftCommand(host).test("install")
		assertEquals(2, result.statusCode)
		assertEquals(
			"error: the JVM distribution cannot install itself; install the native executable\n",
			result.stderr,
		)
	}
}
