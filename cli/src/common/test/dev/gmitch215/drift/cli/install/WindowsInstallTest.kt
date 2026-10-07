package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.test
import dev.gmitch215.drift.host.FakeHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsInstallTest {
	private val bin = "C:\\Users\\u\\AppData\\Local\\Programs\\Drift"
	private val exe = "$bin\\drift.exe"
	private val receipt = "C:\\Users\\u\\AppData\\Local\\Drift\\install-receipt.json"
	private val user = "C:\\Users\\u"
	private val expandPath = "%SystemRoot%\\system32;%USERPROFILE%\\bin"

	private fun host(vararg env: Pair<String, String>) = FakeHost(
		os = "windows",
		env = mapOf(
			"LOCALAPPDATA" to "$user\\AppData\\Local",
			"USERPROFILE" to user,
			"ProgramFiles" to "C:\\Program Files",
			"Path" to "C:\\Windows",
		) + env,
	)

	private fun machine(elevated: Boolean = false) = FakeInstallSystem(
		self = "C:\\src\\drift.exe",
		privileged = elevated,
		windows = true,
		roots = listOf(
			"C:\\",
			"C:\\src",
			"C:\\Users",
			user,
			"$user\\AppData",
			"$user\\AppData\\Local",
			"C:\\Program Files",
		),
	)

	private fun run(host: FakeHost, system: FakeInstallSystem, args: String) =
		DriftCommand(host, install = system).test(args)

	@Test
	fun theUserPathValueGainsOneEntryAndEveryOtherEntryIsKeptRaw() {
		val system = machine()
		system.registry[false] = RegistryValue(REG_EXPAND_SZ, expandPath)
		val result = run(host(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("DRIFT-BINARY", system.text(exe))
		assertEquals(RegistryValue(REG_EXPAND_SZ, "$expandPath;$bin"), system.registry[false])
		assertEquals(1, system.broadcasts)
		assertTrue(result.stdout.contains("set Path in HKCU\\Environment (REG_EXPAND_SZ)"))
		assertTrue(result.stdout.contains("  before: $expandPath"))
		assertTrue(result.stdout.contains("open a new terminal"))
		val saved = Receipt.parse(assertNotNull(system.text(receipt)))
		val edit = RegistryEdit(false, bin, ";", false, REG_EXPAND_SZ)
		assertEquals(listOf<PathEdit>(edit), saved.edits)
	}

	@Test
	fun aSecondRunAndTheUninstallLeaveTheMachineAsItWas() {
		val system = machine()
		system.registry[false] = RegistryValue(REG_EXPAND_SZ, "$expandPath;")
		val before = system.snapshot()
		run(host(), system, "install")
		assertEquals("$expandPath;$bin", system.registry[false]?.data)
		system.mutations.clear()
		val again = run(host(), system, "install")
		assertEquals(0, again.statusCode, again.stderr)
		assertEquals(emptyList(), system.mutations)
		assertEquals(1, system.broadcasts)
		assertTrue(again.stdout.contains("$bin is already in the Path value of HKCU\\Environment"))
		val gone = run(host(), system, "uninstall")
		assertEquals(0, gone.statusCode, gone.stderr)
		assertEquals(RegistryValue(REG_EXPAND_SZ, "$expandPath;"), system.registry[false])
		assertEquals(2, system.broadcasts)
		assertEquals(before, system.snapshot())
	}

	@Test
	fun aPathValueThatDidNotExistIsDeletedAgain() {
		val system = machine()
		run(host(), system, "install")
		assertEquals(RegistryValue(REG_EXPAND_SZ, bin), system.registry[false])
		run(host(), system, "uninstall")
		assertNull(system.registry[false])
	}

	@Test
	fun aPlainStringValueKeepsItsType() {
		val system = machine()
		system.registry[false] = RegistryValue(REG_SZ, "C:\\tools")
		run(host(), system, "install")
		assertEquals(RegistryValue(REG_SZ, "C:\\tools;$bin"), system.registry[false])
		run(host(), system, "uninstall")
		assertEquals(RegistryValue(REG_SZ, "C:\\tools"), system.registry[false])
	}

	@Test
	fun aValueOfAnotherTypeIsNotTouched() {
		val system = machine()
		system.registry[false] = RegistryValue(3, "binary")
		val result = run(host(), system, "install")
		assertEquals(1, result.statusCode)
		assertTrue(result.stderr.contains("has registry type 3; not touching it"), result.stderr)
		assertEquals(RegistryValue(3, "binary"), system.registry[false])
	}

	@Test
	fun anEntryWrittenWithVariablesCountsAsPresent() {
		val system = machine()
		val raw = "C:\\a;%LOCALAPPDATA%\\Programs\\Drift\\"
		system.registry[false] = RegistryValue(REG_EXPAND_SZ, raw)
		val result = run(host(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals(raw, system.registry[false]?.data)
		assertEquals(0, system.broadcasts)
	}

	@Test
	fun noModifyPathLeavesTheRegistryAlone() {
		val system = machine()
		val result = run(host("DRIFT_NO_MODIFY_PATH" to "1"), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertNull(system.registry[false])
		assertEquals(0, system.broadcasts)
	}

	@Test
	fun theDryRunShowsTheRegistryChangeAndMakesNone() {
		val system = machine()
		system.registry[false] = RegistryValue(REG_EXPAND_SZ, "C:\\a")
		val before = system.snapshot()
		val result = run(host(), system, "install --dry-run")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals(before, system.snapshot())
		assertEquals(0, system.broadcasts)
		assertEquals(RegistryValue(REG_EXPAND_SZ, "C:\\a"), system.registry[false])
		val expected = listOf(
			"dry run: nothing is changed",
			"scope: user",
			"create directory $user\\AppData\\Local\\Programs",
			"create directory $bin",
			"write $exe (12 bytes)",
			"create directory $user\\AppData\\Local\\Drift",
			"set Path in HKCU\\Environment (REG_EXPAND_SZ)",
			"  before: C:\\a",
			"  after: C:\\a;$bin",
			"broadcast WM_SETTINGCHANGE",
			"write receipt $receipt",
			"open a new terminal for the PATH change to apply",
		).joinToString("\n", postfix = "\n")
		assertEquals(expected, result.stdout)
	}

	@Test
	fun globalNeedsAnElevatedTokenAndEditsTheMachineKey() {
		val plain = machine()
		val refused = run(host(), plain, "install --global")
		assertEquals(1, refused.statusCode)
		assertEquals(
			"error: cannot write to C:\\Program Files; run this from an elevated prompt: " +
				"\"C:\\src\\drift.exe\" install --global\n",
			refused.stderr,
		)
		val system = machine(elevated = true)
		val result = run(host(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("DRIFT-BINARY", system.text("C:\\Program Files\\Drift\\drift.exe"))
		assertEquals("C:\\Program Files\\Drift", system.registry[true]?.data)
		assertNull(system.registry[false])
		assertTrue(result.stdout.contains(RegistryEdit.MACHINE_KEY))
		run(host(), system, "uninstall")
		assertNull(system.registry[true])
	}

	@Test
	fun aBinaryInUseIsMovedAsideAndRemovedAtUninstall() {
		val system = machine()
		run(host("Path" to "C:\\Windows;$bin"), system, "install")
		system.locked += exe
		system.files["C:\\src\\drift.exe"] = "NEWER".encodeToByteArray()
		val result = run(host("Path" to "C:\\Windows;$bin"), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("NEWER", system.text(exe))
		assertEquals("DRIFT-BINARY", system.text("$bin\\drift.old"))
		assertEquals(listOf("$bin\\drift.old"), Receipt.parse(system.text(receipt)!!).leftovers)
		system.locked.clear()
		val gone = run(host(), system, "uninstall")
		assertEquals(0, gone.statusCode, gone.stderr)
		assertNull(system.files["$bin\\drift.old"])
		assertNull(system.files[exe])
		assertNull(system.registry[false])
	}

	@Test
	fun uninstallingTheRunningBinaryRenamesItAndSchedulesTheRest() {
		val system = machine()
		run(host(), system, "install")
		system.locked += exe
		val result = run(host(), system, "uninstall")
		assertEquals(0, result.statusCode, result.stderr)
		assertNull(system.files[exe])
		assertNotNull(system.files["$bin\\drift.old"])
		assertNull(system.files[receipt])
		assertEquals("", result.stderr)
		assertTrue(result.stdout.contains("remove $bin\\drift.old when this process exits"))
		val (paths, directories) = assertNotNull(system.scheduled)
		assertEquals(listOf("$bin\\drift.old"), paths)
		assertEquals(listOf(bin, "$user\\AppData\\Local\\Programs"), directories)
	}

	@Test
	fun withoutAWayToScheduleTheLeftoverIsNamedInAWarning() {
		val system = machine()
		system.canSchedule = false
		run(host(), system, "install")
		system.locked += exe
		val result = run(host(), system, "uninstall")
		assertEquals(0, result.statusCode, result.stderr)
		val message = "$bin\\drift.old is in use; delete it after this process exits"
		assertTrue(result.stderr.contains(message))
	}

	@Test
	fun aStaleOldCopyBesideTheBinaryIsRemovedByInstallAndUninstall() {
		val system = machine()
		system.directories += listOf("$user\\AppData\\Local\\Programs", bin)
		system.put("$bin\\drift.old", "stale")
		system.files["C:\\src\\drift.exe"] = "NEW".encodeToByteArray()
		run(host(), system, "install --no-modify-path")
		assertNull(system.files["$bin\\drift.old"])
		system.put("$bin\\drift.old", "stale again")
		run(host(), system, "uninstall")
		assertNull(system.files["$bin\\drift.old"])
		assertNull(system.files[exe])
	}

	@Test
	fun aDirectoryWithAPercentSignIsNotWrittenToThePathValue() {
		val system = machine()
		val result = run(host(), system, "install --dir C:/100%/bin")
		assertEquals(0, result.statusCode, result.stderr)
		assertTrue(result.stderr.contains("contains a percent sign"), result.stderr)
		assertNull(system.registry[false])
	}

	@Test
	fun slashesInTheDirectoryBecomeBackslashes() {
		val system = machine()
		val result = run(host(), system, "install --no-modify-path --dir C:/tools/drift")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("DRIFT-BINARY", system.text("C:\\tools\\drift\\drift.exe"))
	}
}
