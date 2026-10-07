package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.DRIFT_VERSION
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.test
import dev.gmitch215.drift.host.FakeHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallerTest {
	private val receipt = "/home/u/.config/drift/install-receipt.json"
	private val dollar = "$"

	private fun unix(vararg env: Pair<String, String>) = FakeHost(
		os = "linux",
		env = mapOf("HOME" to "/home/u", "PATH" to "/usr/bin:/bin", "SHELL" to "/bin/bash") + env,
	)

	private fun run(host: FakeHost, system: FakeInstallSystem, args: String) =
		DriftCommand(host, install = system).test(args)

	private fun profile(dir: String) = ProfileBlock.sh(dir, "/home/u")

	@Test
	fun userInstallCopiesTheBinaryAndAppendsOneProfileBlock() {
		val system = FakeInstallSystem()
		val result = run(unix(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("DRIFT-BINARY", system.text("/home/u/.local/bin/drift"))
		assertTrue("/home/u/.local/bin/drift" in system.executable)
		assertEquals(profile("/home/u/.local/bin"), system.text("/home/u/.profile"))
		val done = "installed drift $DRIFT_VERSION to /home/u/.local/bin/drift"
		assertTrue(result.stdout.contains(done))
		assertTrue(result.stdout.contains("restart your shell or run: . \"/home/u/.profile\""))
		val saved = Receipt.parse(assertNotNull(system.text(receipt)))
		assertEquals(Scope.USER, saved.scope)
		assertEquals("/home/u/.local/bin/drift", saved.binary)
		assertEquals(
			listOf(ProfileEdit("/home/u/.profile", profile("/home/u/.local/bin"), true, false)),
			saved.edits,
		)
	}

	@Test
	fun aSecondRunChangesNothing() {
		val system = FakeInstallSystem()
		run(unix(), system, "install")
		val before = system.snapshot()
		system.mutations.clear()
		val again = run(unix(), system, "install")
		assertEquals(0, again.statusCode, again.stderr)
		assertEquals(before, system.snapshot())
		assertEquals(emptyList(), system.mutations)
		assertTrue(again.stdout.contains("/home/u/.local/bin/drift is up to date"))
		assertTrue(again.stdout.contains("/home/u/.profile already has the drift block"))
	}

	@Test
	fun theDryRunPrintsEveryChangeAndMakesNone() {
		val system = FakeInstallSystem()
		val before = system.snapshot()
		val result = run(unix(), system, "install --dry-run")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals(before, system.snapshot())
		assertEquals(emptyList(), system.mutations)
		val expected = listOf(
			"dry run: nothing is changed",
			"scope: user",
			"create directory /home/u/.local",
			"create directory /home/u/.local/bin",
			"write /home/u/.local/bin/drift (mode 0755, 12 bytes)",
			"create directory /home/u/.config",
			"create directory /home/u/.config/drift",
			"append to /home/u/.profile (new file):",
			"  # >>> drift >>>",
			"  case \":$dollar{PATH}:\" in",
			"  \t*\":${dollar}HOME/.local/bin:\"*) ;;",
			"  \t*) export PATH=\"${dollar}HOME/.local/bin:${dollar}PATH\" ;;",
			"  esac",
			"  # <<< drift <<<",
			"write receipt /home/u/.config/drift/install-receipt.json",
			"restart your shell or run: . \"/home/u/.profile\"",
		).joinToString("\n", postfix = "\n")
		assertEquals(expected, result.stdout)
	}

	@Test
	fun uninstallRestoresTheMachineExactly() {
		val system = FakeInstallSystem()
		val before = system.snapshot()
		run(unix(), system, "install")
		assertTrue(system.snapshot() != before)
		val result = run(unix(), system, "uninstall")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals(before, system.snapshot())
		assertTrue(result.stdout.endsWith("uninstalled drift\n"), result.stdout)
	}

	@Test
	fun uninstallKeepsDirectoriesItDidNotCreate() {
		val system = FakeInstallSystem()
		system.directories += listOf("/home/u/.local", "/home/u/.local/bin", "/home/u/.config")
		system.put("/home/u/.local/bin/other", "x")
		run(unix(), system, "install")
		run(unix(), system, "uninstall")
		assertTrue("/home/u/.local/bin" in system.directories)
		assertTrue("/home/u/.config" in system.directories)
		assertFalse("/home/u/.config/drift" in system.directories)
		assertEquals("x", system.text("/home/u/.local/bin/other"))
	}

	@Test
	fun uninstallDryRunPrintsTheActionsAndRemovesNothing() {
		val system = FakeInstallSystem()
		run(unix(), system, "install")
		val before = system.snapshot()
		val result = run(unix(), system, "uninstall --dry-run")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals(before, system.snapshot())
		val expected = listOf(
			"dry run: nothing is changed",
			"remove /home/u/.profile (drift created it)",
			"remove /home/u/.local/bin/drift",
			"remove $receipt",
			"remove directory /home/u/.config/drift if it is empty",
			"remove directory /home/u/.config if it is empty",
			"remove directory /home/u/.local/bin if it is empty",
			"remove directory /home/u/.local if it is empty",
		).joinToString("\n", postfix = "\n")
		assertEquals(expected, result.stdout)
	}

	@Test
	fun uninstallWithoutAReceiptSaysSoAndRemovesNothing() {
		val system = FakeInstallSystem()
		system.put("/home/u/.local/bin/drift", "someone else's")
		val before = system.snapshot()
		val result = run(unix(), system, "uninstall")
		assertEquals(0, result.statusCode)
		assertEquals("no install receipt at $receipt; nothing removed\n", result.stdout)
		assertEquals(before, system.snapshot())
	}

	@Test
	fun anUnreadableReceiptStopsBothCommands() {
		val system = FakeInstallSystem()
		system.directories += listOf("/home/u/.config", "/home/u/.config/drift")
		system.put(receipt, "{\"schema\":7}")
		val before = system.snapshot()
		for (command in listOf("uninstall", "install")) {
			val result = run(unix(), system, command)
			assertEquals(1, result.statusCode)
			val message = "cannot read the install receipt at $receipt"
			assertTrue(result.stderr.contains(message), result.stderr)
		}
		assertEquals(before, system.snapshot())
	}

	@Test
	fun aProfileBlockEditedByHandIsLeftAndTheReceiptKept() {
		val system = FakeInstallSystem()
		run(unix(), system, "install")
		system.put("/home/u/.profile", profile("/home/u/.local/bin").replace("export", "set"))
		val result = run(unix(), system, "uninstall")
		assertEquals(1, result.statusCode)
		assertTrue(result.stderr.contains("differs from the receipt; left in place"), result.stderr)
		assertTrue(result.stderr.contains("1 item(s) were not removed"), result.stderr)
		assertNull(system.files["/home/u/.local/bin/drift"])
		assertNotNull(system.files[receipt])
		assertNotNull(system.files["/home/u/.profile"])
	}

	@Test
	fun scopeFollowsPrivilegeAndTheFlags() {
		val table = listOf(
			Triple(false, "install", "/home/u/.local/bin/drift"),
			Triple(false, "install --user", "/home/u/.local/bin/drift"),
			Triple(true, "install", "/usr/local/bin/drift"),
			Triple(true, "install --user", "/home/u/.local/bin/drift"),
			Triple(true, "install --global", "/usr/local/bin/drift"),
			Triple(false, "install --dir /opt/tools/bin", "/opt/tools/bin/drift"),
		)
		for ((privileged, args, binary) in table) {
			val system = FakeInstallSystem(privileged = privileged)
			system.directories += listOf("/opt", "/opt/tools")
			val result = run(unix(), system, args)
			assertEquals(0, result.statusCode, "$privileged $args: ${result.stderr}")
			assertNotNull(system.files[binary], "$privileged $args")
		}
	}

	@Test
	fun globalWithoutWriteAccessNamesTheCommandAndWritesNothing() {
		val system = FakeInstallSystem()
		system.readOnly += "/usr/local/bin"
		val before = system.snapshot()
		val result = run(unix(), system, "install --global --no-modify-path")
		assertEquals(1, result.statusCode)
		assertEquals(
			"error: cannot write to /usr/local/bin; " +
				"run: sudo \"/src/drift\" install --global --no-modify-path\n",
			result.stderr,
		)
		assertEquals(before, system.snapshot())
	}

	@Test
	fun anUnwritableUserDirectoryIsAFailure() {
		val system = FakeInstallSystem()
		system.readOnly += "/home/u"
		val result = run(unix(), system, "install")
		assertEquals(1, result.statusCode)
		assertEquals("error: cannot write to /home/u\n", result.stderr)
	}

	@Test
	fun usageErrorsExitTwo() {
		val system = FakeInstallSystem()
		val both = run(unix(), system, "install --user --global")
		assertEquals(2, both.statusCode)
		assertTrue(both.stderr.contains("--user and --global cannot be combined"))
		val relative = run(unix(), system, "install --dir bin")
		assertEquals(2, relative.statusCode)
		assertTrue(relative.stderr.contains("--dir must be an absolute path: bin"))
		val jvm = FakeInstallSystem(self = null)
		val host = FakeHost(platform = "jvm", env = mapOf("HOME" to "/home/u"))
		val result = run(host, jvm, "install")
		assertEquals(2, result.statusCode)
		assertEquals(
			"error: the JVM distribution cannot install itself; install the native executable\n",
			result.stderr,
		)
		val unknown = run(FakeHost(platform = "wasm"), FakeInstallSystem(self = null), "install")
		assertEquals("error: cannot locate the running executable\n", unknown.stderr)
	}

	@Test
	fun withoutAHomeDirectoryTheInstallFails() {
		val result = run(FakeHost(os = "linux"), FakeInstallSystem(), "install")
		assertEquals(1, result.statusCode)
		assertEquals("error: cannot find the home directory: HOME is not set\n", result.stderr)
	}

	@Test
	fun xdgBinHomeIsHonoredWhenAbsolute() {
		val system = FakeInstallSystem()
		system.directories += "/xdg"
		run(unix("XDG_BIN_HOME" to "/xdg/bin"), system, "install --no-modify-path")
		assertNotNull(system.files["/xdg/bin/drift"])
		val relative = FakeInstallSystem()
		run(unix("XDG_BIN_HOME" to "bin"), relative, "install --no-modify-path")
		assertNotNull(relative.files["/home/u/.local/bin/drift"])
	}

	@Test
	fun theConfigDirectoryFollowsXdgConfigHome() {
		val system = FakeInstallSystem()
		system.directories += "/cfg"
		run(unix("XDG_CONFIG_HOME" to "/cfg"), system, "install --no-modify-path")
		assertNotNull(system.files["/cfg/drift/install-receipt.json"])
	}

	@Test
	fun pathIsLeftAloneWhenTheDirectoryIsOnItOrOptedOut() {
		val onPath = unix("PATH" to "/usr/bin:/home/u/.local/bin/:/bin")
		val cases = listOf(
			Triple(onPath, "install", "is already on PATH"),
			Triple(unix(), "install --no-modify-path", "PATH is not modified"),
			Triple(unix("DRIFT_NO_MODIFY_PATH" to "1"), "install", "PATH is not modified"),
		)
		for ((host, args, note) in cases) {
			val system = FakeInstallSystem()
			val result = run(host, system, args)
			assertEquals(0, result.statusCode, result.stderr)
			assertTrue(result.stdout.contains(note), "$args: ${result.stdout}")
			assertNull(system.files["/home/u/.profile"], args)
			val saved = Receipt.parse(assertNotNull(system.text(receipt)))
			assertEquals(emptyList(), saved.edits)
		}
		val unset = run(unix("DRIFT_NO_MODIFY_PATH" to "0"), FakeInstallSystem(), "install")
		assertTrue(unset.stdout.contains("append to /home/u/.profile"))
	}

	@Test
	fun aGlobalUnixInstallDoesNotEditAnyProfile() {
		val system = FakeInstallSystem(privileged = true)
		system.put("/home/u/.profile", "export A=1\n")
		val result = run(unix("PATH" to "/bin"), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("export A=1\n", system.text("/home/u/.profile"))
		assertTrue(result.stdout.contains("/usr/local/bin is not on PATH; add it"))
	}

	@Test
	fun profileTargetsFollowTheFilesAndTheShell() {
		data class Case(
			val files: List<String>,
			val dirs: List<String>,
			val env: List<Pair<String, String>>,
			val edited: List<String>,
		)
		val cases = listOf(
			Case(emptyList(), emptyList(), emptyList(), listOf("/home/u/.profile")),
			Case(
				listOf(".profile", ".bash_profile", ".bashrc"),
				emptyList(),
				emptyList(),
				listOf("/home/u/.profile", "/home/u/.bash_profile", "/home/u/.bashrc"),
			),
			Case(listOf(".bashrc"), emptyList(), emptyList(), listOf("/home/u/.bashrc")),
			Case(
				emptyList(),
				emptyList(),
				listOf("SHELL" to "/bin/zsh"),
				listOf("/home/u/.zshenv"),
			),
			Case(listOf(".zprofile"), emptyList(), emptyList(), listOf("/home/u/.zprofile")),
			Case(
				listOf(".zshenv", ".zprofile"),
				emptyList(),
				emptyList(),
				listOf("/home/u/.zshenv"),
			),
			Case(
				emptyList(),
				listOf("/z"),
				listOf("SHELL" to "/bin/zsh", "ZDOTDIR" to "/z"),
				listOf("/z/.zshenv"),
			),
			Case(
				listOf(".profile"),
				listOf("/home/u/.config", "/home/u/.config/fish"),
				emptyList(),
				listOf("/home/u/.profile", "/home/u/.config/fish/conf.d/drift.fish"),
			),
			Case(
				emptyList(),
				emptyList(),
				listOf("SHELL" to "/usr/bin/fish"),
				listOf("/home/u/.config/fish/conf.d/drift.fish"),
			),
		)
		for (case in cases) {
			val system = FakeInstallSystem()
			system.directories += case.dirs
			for (name in case.files) system.put("/home/u/$name", "# mine\n")
			val result = run(unix(*case.env.toTypedArray()), system, "install")
			assertEquals(0, result.statusCode, "${case.files}: ${result.stderr}")
			val saved = Receipt.parse(assertNotNull(system.text(receipt)))
			assertEquals(case.edited, saved.edits.map { (it as ProfileEdit).path }, case.toString())
		}
	}

	@Test
	fun theFishBlockUsesFishSyntaxAndTheConfigDirectoryIsRemovedAgain() {
		val system = FakeInstallSystem()
		system.directories += listOf("/home/u/.config", "/home/u/.config/fish")
		val before = system.snapshot()
		run(unix(), system, "install")
		val block = assertNotNull(system.text("/home/u/.config/fish/conf.d/drift.fish"))
		assertEquals(ProfileBlock.fish("/home/u/.local/bin", "/home/u"), block)
		assertTrue("/home/u/.config/fish/conf.d" in system.directories)
		run(unix(), system, "uninstall")
		assertEquals(before, system.snapshot())
	}

	@Test
	fun profilesRoundTripByteForByte() {
		val originals = listOf(
			"",
			"\n",
			"export A=1",
			"export A=1\n",
			"a\r\nb\r\n",
			"# >>> drift later <<<\nx",
			"no newline at the end é中",
			"line\n\n\n",
		)
		for (original in originals) {
			val system = FakeInstallSystem()
			system.put("/home/u/.profile", original)
			val before = system.snapshot()
			run(unix(), system, "install")
			assertTrue(system.text("/home/u/.profile")!!.contains(ProfileBlock.START))
			val result = run(unix(), system, "uninstall")
			assertEquals(0, result.statusCode, result.stderr)
			assertEquals(before, system.snapshot(), "original: $original")
		}
	}

	@Test
	fun aProfileThatIsNotUtf8IsLeftAlone() {
		val system = FakeInstallSystem()
		system.files["/home/u/.profile"] = byteArrayOf(0x66, 0xff.toByte(), 0x0a)
		val result = run(unix(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		val message = "/home/u/.profile cannot be read as UTF-8"
		assertTrue(result.stderr.contains(message), result.stderr)
		assertEquals(3, system.files["/home/u/.profile"]!!.size)
		assertEquals(emptyList(), Receipt.parse(system.text(receipt)!!).edits)
	}

	@Test
	fun aBlockThatWasNotWrittenByTheReceiptIsReportedAndNotRecorded() {
		val system = FakeInstallSystem()
		system.put("/home/u/.profile", "${ProfileBlock.START}\nmine\n${ProfileBlock.END}\n")
		val result = run(unix(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		val message = "already has a drift block that this install did not write"
		assertTrue(result.stderr.contains(message))
		assertEquals(emptyList(), Receipt.parse(system.text(receipt)!!).edits)
		val gone = run(unix(), system, "uninstall")
		assertEquals(0, gone.statusCode)
		val mine = "${ProfileBlock.START}\nmine\n${ProfileBlock.END}\n"
		assertEquals(mine, system.text("/home/u/.profile"))
	}

	@Test
	fun aBlockDeletedByHandIsWrittenAgainOnReinstall() {
		val system = FakeInstallSystem()
		system.put("/home/u/.profile", "export A=1\n")
		run(unix(), system, "install")
		system.put("/home/u/.profile", "export A=1\n")
		val result = run(unix(), system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		val expected = "export A=1\n" + profile("/home/u/.local/bin")
		assertEquals(expected, system.text("/home/u/.profile"))
		run(unix(), system, "uninstall")
		assertEquals("export A=1\n", system.text("/home/u/.profile"))
	}

	@Test
	fun upgradingInPlaceKeepsTheRecordedEditsEvenWhenTheDirectoryIsNowOnPath() {
		val system = FakeInstallSystem()
		run(unix(), system, "install")
		system.files["/src/drift"] = "NEWER".encodeToByteArray()
		val onPath = unix("PATH" to "/usr/bin:/home/u/.local/bin")
		val result = run(onPath, system, "install")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("NEWER", system.text("/home/u/.local/bin/drift"))
		assertEquals(1, Receipt.parse(system.text(receipt)!!).edits.size)
		run(onPath, system, "uninstall")
		assertNull(system.files["/home/u/.profile"])
	}

	@Test
	fun aDifferentDirectoryWhileAnInstallIsRecordedIsRefused() {
		val system = FakeInstallSystem()
		system.directories += listOf("/opt", "/opt/bin")
		run(unix(), system, "install")
		val before = system.snapshot()
		val result = run(unix(), system, "install --dir /opt/bin")
		assertEquals(1, result.statusCode)
		assertTrue(result.stderr.contains("already installed in /home/u/.local/bin"), result.stderr)
		assertEquals(before, system.snapshot())
	}

	@Test
	fun aFailedBinaryWriteLeavesNoStagedFile() {
		val system = FakeInstallSystem()
		system.directories += listOf("/home/u/.local", "/home/u/.local/bin")
		system.locked += "/home/u/.local/bin/drift.new"
		val result = run(unix(), system, "install")
		assertEquals(1, result.statusCode)
		assertTrue(result.stderr.contains("failed: write /home/u/.local/bin/drift"), result.stderr)
		assertNull(system.files["/home/u/.local/bin/drift.new"])
		assertNull(system.files["/home/u/.local/bin/drift"])
	}

	@Test
	fun theReceiptIsStillWrittenWhenAPathEditFailsAfterTheBinaryLanded() {
		val system = FakeInstallSystem()
		system.directories += listOf("/home/u/.local", "/home/u/.local/bin")
		system.locked += "/home/u/.profile"
		val result = run(unix(), system, "install")
		assertEquals(1, result.statusCode)
		assertNotNull(system.files["/home/u/.local/bin/drift"])
		val saved = Receipt.parse(assertNotNull(system.text(receipt)))
		assertEquals("/home/u/.local/bin/drift", saved.binary)
		assertEquals(emptyList(), saved.edits)
	}
}
