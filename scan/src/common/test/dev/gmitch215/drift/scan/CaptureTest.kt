package dev.gmitch215.drift.scan

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.redact.Redactor
import dev.gmitch215.drift.scan.probe.Catalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTest {
	private val host = FakeHost(
		platform = "linux",
		arch = "amd64",
		env = mapOf(
			"TZ" to "UTC",
			"PWD" to "/work",
			"GITHUB_TOKEN" to "abc",
			"NOTE" to "ghp_0123456789abcdefghijABCDEFGHIJ",
		),
		files = mapOf(
			"/etc/os-release" to "ID=ubuntu\nVERSION_ID=\"24.04\"\nHOME_URL=x\n",
			"/sys/fs/cgroup/cpu.max" to "200000 100000\n",
		),
		commands = mapOf(
			listOf("uname", "-r") to CommandResult(0, "6.8.0-1\n"),
			listOf("getconf", "_NPROCESSORS_ONLN") to CommandResult(0, "4\n"),
		),
	)

	@Test
	fun collectsFactsFromHost() {
		val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
		assertEquals("x86_64", byPath.getValue("os.arch").value)
		assertEquals("ubuntu", byPath.getValue("os.release.ID").value)
		assertEquals("24.04", byPath.getValue("os.release.VERSION_ID").value)
		assertEquals("6.8.0-1", byPath.getValue("kernel.release").value)
		assertEquals("4", byPath.getValue("cpu.count").value)
		assertEquals("200000 100000", byPath.getValue("cgroup.cpu.max").value)
		assertNull(byPath["os.release.HOME_URL"])
		assertEquals(Stability.VOLATILE, byPath.getValue("env.PWD").stability)
		assertEquals(Stability.STATIC, byPath.getValue("env.TZ").stability)
	}

	@Test
	fun redactsSecretsBeforeTheyEnterTheCapsule() {
		val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
		assertEquals(Redactor.MASK, byPath.getValue("env.GITHUB_TOKEN").value)
		assertEquals(Redactor.MASK, byPath.getValue("env.NOTE").value)
	}

	@Test
	fun hostWithoutProcessesReportsOnlyWhatItKnows() {
		val paths = Capture.run(
			FakeHost(platform = "wasm", os = "unknown", arch = "wasm32", canRun = false),
			"w",
		)
			.attributes.map { it.path }
		assertEquals(
			listOf(
				"drift.platform",
				"kotlin.target",
				"kotlin.version",
				"os.arch",
				"os.name",
				"runtime.platform",
			),
			paths,
		)
	}

	@Test
	fun windowsUsesVerAndEnvCount() {
		val win = FakeHost(
			platform = "mingw",
			os = "windows",
			env = mapOf("NUMBER_OF_PROCESSORS" to "8"),
			commands = mapOf(
				listOf("cmd", "/c", "ver") to
					CommandResult(0, "\r\nMicrosoft Windows [Version 10.0.26100.1]\r\n"),
			),
		)
		val byPath = Capture.run(win, "w").attributes.associateBy { it.path }
		assertEquals(
			"Microsoft Windows [Version 10.0.26100.1]",
			byPath.getValue("os.version").value,
		)
		assertEquals("8", byPath.getValue("cpu.count").value)
		assertTrue(win.ran.none { it.first() == "uname" })
	}

	@Test
	fun selfDiffOnTheRealHostIsEmpty() {
		val real = systemHost()
		val a = Capture.run(real, "a")
		val b = Capture.run(real, "b")
		assertEquals(emptyList(), CapsuleDiff.attributes(a, b))
		if (real.platform != "wasm") {
			assertTrue(a.attributes.any { it.path == "os.name" }, "os.name missing")
			assertTrue(
				a.attributes.any {
					it.path.startsWith("env.")
				},
				"no env captured on ${real.platform}",
			)
		}
	}

	@Test
	fun runtimePlatformDefaultsToTheHostAndCanBeDeclared() {
		val plain = Capture.run(host, "t").attributes.single { it.path == "runtime.platform" }
		assertEquals("linux", plain.value)
		assertEquals("drift", plain.source)
		val declared =
			FakeHost(platform = "jvm", env = mapOf("DRIFT_RUNTIME_PLATFORM" to "workerd-local"))
		val attr = Capture.run(declared, "t").attributes.single { it.path == "runtime.platform" }
		assertEquals("workerd-local", attr.value)
		assertEquals("env", attr.source)
		assertEquals(Stability.STATIC, attr.stability)
		val blank = FakeHost(platform = "jvm", env = mapOf("DRIFT_RUNTIME_PLATFORM" to " "))
		assertEquals(
			"jvm",
			Capture.run(blank, "t").attributes.single {
			it.path == "runtime.platform"
		}.value,
		)
	}

	@Test
	fun runnerLabelComesOnlyFromTheDeclaredVariable() {
		assertTrue(Capture.run(host, "t").attributes.none { it.path == "ci.runner.label" })
		val env = mapOf("DRIFT_RUNNER_LABEL" to "ubuntu-latest", "RUNNER_OS" to "Linux")
		val ci = FakeHost(env = env)
		val label = Capture.run(ci, "t").attributes.single { it.path == "ci.runner.label" }
		assertEquals("ubuntu-latest", label.value)
		assertEquals(Stability.STATIC, label.stability)
	}

	@Test
	fun hardwareFactsAreOptIn() {
		val fact = Attribute("hw.memory.total", "1", "test")
		val withFacts = FakeHost(facts = listOf(fact))
		assertTrue(Capture.run(withFacts, "t").attributes.none { it.path.startsWith("hw.") })
		val on = Capture.run(withFacts, "t", hardware = true).attributes
		assertEquals(fact, on.single { it.path == "hw.memory.total" })
	}

	private fun leaky(account: String, home: String, name: String) = FakeHost(
		env = mapOf(
			"HOME" to home,
			"USER" to account,
			"LOGNAME" to account,
			"HOSTNAME" to name,
			"PATH" to "$home/bin:/usr/bin:$home/.nvm/node/bin",
			"NVM_DIR" to "$home/.nvm",
			"MAIL_NOTE" to "sent by $account from $name",
			"OTHER" to "/home/someoneelse/tool",
			"TZ" to "UTC",
		),
		files = mapOf("/sys/fs/cgroup/cpu.max" to "max 100000 $home\n"),
		commands = mapOf(
			listOf("node", "--version") to CommandResult(0, "odd $home/bin/node build\n"),
			listOf("uname", "-r") to CommandResult(0, "6.8.0\n"),
		),
		facts = listOf(Attribute("hw.model", "box of $account", "test")),
	)

	@Test
	fun anonymizesTheAccountHostAndHomePathEverywhere() {
		val host = leaky("zorbulax", "/home/zorbulax", "quuxhost")
		val capsule =
			Capture.run(host, "t", probes = Catalog.all, tools = true, hardware = true)
		val text = capsule.canonical()
		for (secret in listOf("zorbulax", "quuxhost")) {
			assertFalse(secret in text, "$secret leaked: $text")
		}
		val byPath = capsule.attributes.associateBy { it.path }
		assertEquals("~", byPath.getValue("env.HOME").value)
		assertEquals("user", byPath.getValue("env.USER").value)
		assertEquals("host", byPath.getValue("env.HOSTNAME").value)
		assertEquals("~/bin:/usr/bin:~/.nvm/node/bin", byPath.getValue("env.PATH").value)
		assertEquals("~/.nvm", byPath.getValue("env.NVM_DIR").value)
		assertEquals("sent by user from host", byPath.getValue("env.MAIL_NOTE").value)
		assertEquals("/home/user/tool", byPath.getValue("env.OTHER").value)
		assertEquals("max 100000 ~", byPath.getValue("cgroup.cpu.max").value)
		assertEquals("odd ~/bin/node build", byPath.getValue("tool.node.raw").value)
		assertEquals("box of user", byPath.getValue("hw.model").value)
		assertEquals("env (anonymized)", byPath.getValue("env.PATH").source)
		assertEquals("env", byPath.getValue("env.TZ").source)
		assertEquals("uname", byPath.getValue("kernel.release").source)
	}

	@Test
	fun keepIdentifiersKeepsThemAndNothingElseChanges() {
		val host = leaky("zorbulax", "/home/zorbulax", "quuxhost")
		val kept = Capture.run(host, "t", keepIdentifiers = true)
		val byPath = kept.attributes.associateBy { it.path }
		assertEquals("/home/zorbulax", byPath.getValue("env.HOME").value)
		assertEquals("zorbulax", byPath.getValue("env.USER").value)
		assertEquals("env", byPath.getValue("env.HOME").source)
		val plain = Capture.run(host, "t")
		assertEquals(
			kept.attributes.map { it.path },
			plain.attributes.map { it.path },
		)
	}

	@Test
	fun machinesWithDifferentAccountsAnonymizeToTheSameCapsule() {
		val mac = leaky("alicexyz", "/Users/alicexyz", "alices-mac")
		val box = leaky("bobabc", "/home/bobabc", "bob-ci-7")
		val a = Capture.run(mac, "a", tools = true)
		val b = Capture.run(box, "a", tools = true)
		assertEquals(emptyList(), CapsuleDiff.attributes(a, b))
		assertEquals(a.canonical(), Capture.run(mac, "a", tools = true).canonical())
	}

	@Test
	fun shortNamesAndUnrelatedWordsAreNotRewritten() {
		val host = FakeHost(
			env = mapOf(
				"USER" to "al",
				"HOME" to "/home/al",
				"NOTE" to "al went to alabama; usertools stay",
				"PATH" to "/home/albert/bin:/home/al/bin",
			),
		)
		val byPath = Capture.run(host, "t").attributes.associateBy { it.path }
		assertEquals("al", byPath.getValue("env.USER").value)
		assertEquals("al went to alabama; usertools stay", byPath.getValue("env.NOTE").value)
		assertEquals("/home/user/bin:~/bin", byPath.getValue("env.PATH").value)
	}
}
