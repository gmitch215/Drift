package dev.gmitch215.drift.cli.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTest {
	private val unix = Layout(mapOf("HOME" to "/home/u"), windows = false)
	private val windows = Layout(
		mapOf("USERPROFILE" to "C:\\Users\\u", "programfiles" to "D:\\PF"),
		windows = true,
	)

	@Test
	fun directoriesFollowTheConvention() {
		assertEquals("/home/u/.local/bin", unix.userDir())
		assertEquals("/home/u/.config/drift", unix.configDir())
		assertEquals("/usr/local/bin", unix.globalDir())
		assertEquals("C:\\Users\\u\\AppData\\Local\\Programs\\Drift", windows.userDir())
		assertEquals("C:\\Users\\u\\AppData\\Local\\Drift", windows.configDir())
		assertEquals("D:\\PF\\Drift", windows.globalDir())
		assertEquals("C:\\Program Files\\Drift", Layout(emptyMap(), true).globalDir())
		assertNull(Layout(emptyMap(), false).userDir())
		assertNull(Layout(emptyMap(), true).configDir())
	}

	@Test
	fun xdgVariablesApplyOnlyWhenAbsolute() {
		val set = mapOf("HOME" to "/h", "XDG_BIN_HOME" to "/b", "XDG_CONFIG_HOME" to "/c")
		val xdg = Layout(set, false)
		assertEquals("/b", xdg.userDir())
		assertEquals("/c/drift", xdg.configDir())
		val bad = mapOf("HOME" to "/h", "XDG_BIN_HOME" to "b", "XDG_CONFIG_HOME" to "")
		val relative = Layout(bad, false)
		assertEquals("/h/.local/bin", relative.userDir())
		assertEquals("/h/.config/drift", relative.configDir())
	}

	@Test
	fun parentAndJoinHandleRootsAndDriveLetters() {
		assertEquals("/a", unix.parent("/a/b"))
		assertEquals("/", unix.parent("/a"))
		assertNull(unix.parent("a"))
		assertEquals("/a/b", unix.join("/a/", "b"))
		assertEquals("/b", unix.join("/", "b"))
		assertEquals("C:\\Users", windows.parent("C:\\Users\\u"))
		assertEquals("C:\\", windows.parent("C:\\Users"))
		assertEquals("C:\\x", windows.join("C:\\", "x"))
	}

	@Test
	fun absoluteAndCleanFollowTheFamily() {
		assertTrue(unix.isAbsolute("/x"))
		assertFalse(unix.isAbsolute("x"))
		assertTrue(windows.isAbsolute("C:\\x"))
		assertTrue(windows.isAbsolute("C:/x"))
		assertTrue(windows.isAbsolute("\\\\server\\share"))
		assertFalse(windows.isAbsolute("\\x"))
		assertEquals("/a/b", unix.clean("/a/b/"))
		assertEquals("/", unix.clean("/"))
		assertEquals("C:\\a\\b", windows.clean("C:/a/b/"))
		assertEquals("C:\\", windows.clean("C:\\"))
	}

	@Test
	fun onPathIgnoresTrailingSeparatorsAndCaseOnWindowsOnly() {
		assertTrue(unix.onPath("/a:/b/:/c", "/b"))
		assertFalse(unix.onPath("/a:/B", "/b"))
		assertFalse(unix.onPath("::", ""))
		assertTrue(windows.onPath("C:\\A;\"c:\\b\\\"", "C:/B"))
		assertTrue(unix.sameDirectory("/a/", "/a"))
		assertEquals("C:\\x\\drift.old", windows.oldName("C:\\x\\drift.exe"))
	}
}
