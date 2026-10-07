package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.host.systemHost
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal expect fun underWine(): Boolean

class NativeInstallTest {
	private val system = systemInstall()
	private val windows = systemHost().os == "windows"
	private val sep = if (windows) "\\" else "/"

	private fun scratch(): String {
		val env = systemHost().env()
		val tmp = (env["TMPDIR"] ?: env["TEMP"] ?: env["TMP"] ?: "/tmp").trimEnd('/', '\\')
		return tmp + sep + "drift-install-" + Random.nextLong().toULong().toString(16)
	}

	@Test
	fun filesAndDirectoriesBehaveLikeTheFakeDoes() {
		val base = scratch()
		assertFalse(system.exists(base))
		assertTrue(system.makeDirectory(base))
		assertFalse(system.makeDirectory(base))
		assertTrue(system.isDirectory(base))
		assertTrue(system.exists(base))
		assertTrue(system.canWrite(base))
		val file = "$base${sep}plain-name"
		val bytes = ByteArray(3 * 65536 + 17) { (it * 31).toByte() }
		assertTrue(system.write(file, bytes))
		assertContentEquals(bytes, system.read(file))
		assertFalse(system.isDirectory(file))
		assertTrue(system.write(file, byteArrayOf(1, 2, 3)))
		assertContentEquals(byteArrayOf(1, 2, 3), system.read(file))
		assertTrue(system.write("$base${sep}empty", ByteArray(0)))
		assertContentEquals(ByteArray(0), system.read("$base${sep}empty"))
		val moved = "$base${sep}moved"
		assertTrue(system.write(moved, byteArrayOf(9)))
		assertTrue(system.rename(file, moved))
		assertContentEquals(byteArrayOf(1, 2, 3), system.read(moved))
		assertFalse(system.exists(file))
		assertFalse(system.removeDirectory(base))
		assertNull(system.read(base))
		assertNull(system.read("$base${sep}missing"))
		assertFalse(system.write("$base${sep}no${sep}parent", byteArrayOf(1)))
		assertTrue(system.remove(moved))
		assertFalse(system.remove(moved))
		assertTrue(system.remove("$base${sep}empty"))
		assertTrue(system.removeDirectory(base))
		assertFalse(system.exists(base))
	}

	@Test
	fun unicodeFileNamesRoundTrip() {
		val env = systemHost().env()
		val locale = listOf("LC_ALL", "LC_CTYPE", "LANG").firstNotNullOfOrNull {
			env[it]?.takeIf(String::isNotEmpty)
		} ?: ""
		if (underWine() && !locale.lowercase().replace("-", "").contains("utf8")) {
			println("skipped: wine maps file names through the locale charset; set LC_ALL=C.UTF-8")
			return
		}
		val base = scratch()
		assertTrue(system.makeDirectory(base))
		val file = "$base${sep}déjà-中"
		val moved = "$base${sep}moved-中"
		assertTrue(system.write(file, byteArrayOf(1, 2, 3)))
		assertContentEquals(byteArrayOf(1, 2, 3), system.read(file))
		assertTrue(system.rename(file, moved))
		assertFalse(system.exists(file))
		assertTrue(system.remove(moved))
		assertTrue(system.removeDirectory(base))
	}

	@Test
	fun theRunningExecutableCanBeFoundAndRead() {
		val self = assertNotNull(system.selfPath())
		assertTrue(system.exists(self))
		assertTrue(assertNotNull(system.read(self)).size > 1000)
		system.isPrivileged()
	}

	@Test
	fun thePathValueIsOnlyReadOnWindows() {
		val value = system.readPath(false)
		if (!windows) assertNull(value)
		if (!windows) assertFalse(system.writePath(false, RegistryValue(REG_SZ, "x")))
		if (windows) assertTrue(value == null || value.type > 0)
	}
}
