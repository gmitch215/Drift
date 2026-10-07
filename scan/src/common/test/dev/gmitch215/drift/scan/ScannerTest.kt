package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.scan.scanner.Kit
import dev.gmitch215.drift.scan.scanner.KitEntry
import dev.gmitch215.drift.scan.scanner.Scanner
import dev.gmitch215.drift.scan.scanner.Scanners
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerTest {
	private fun scanner(tool: String) = Scanners.all.single { it.tool == tool }

	private fun parsed(tool: String, label: String): Map<String, String> {
		val text = Fixtures.text("$tool/$label.txt")
		return Scanners.attributes(scanner(tool), text).associate {
			it.path.removePrefix("tool.$tool.") to it.value
		}
	}

	private fun labels(tool: String) = Fixtures.names
		.filter { it.startsWith("$tool/") }
		.map { it.removePrefix("$tool/").removeSuffix(".txt") }

	@Test
	fun everyToolHasCapturedTranscripts() {
		for (s in Scanners.all) {
			assertTrue(labels(s.tool).isNotEmpty(), "no transcripts for ${s.tool}")
		}
	}

	@Test
	fun versionsMatchTheImageTheyWereCapturedFrom() {
		val prefixes = mapOf("node" to "", "python" to "", "php" to "")
		for ((tool, _) in prefixes) {
			for (label in labels(tool)) {
				val wanted = label.substringAfter('-')
				val version = parsed(tool, label).getValue("version")
				assertTrue(version.startsWith("$wanted."), "$tool $label parsed as $version")
			}
		}
		for (label in labels("go")) {
			val version = parsed("go", label).getValue("version")
			assertTrue(version.startsWith(label.substringAfter('-')), label)
		}
		assertEquals("linux", parsed("go", labels("go").first()).getValue("os"))
		for (label in labels("java")) {
			val major = label.substringAfter('-')
			val expected = if (major == "8") "1.8." else "$major."
			val result = parsed("java", label)
			assertTrue(result.getValue("version").startsWith(expected), "java $label $result")
			assertEquals("openjdk", result.getValue("implementation"), label)
		}
		for (label in labels("rust")) {
			assertTrue(parsed("rust", label).getValue("version").startsWith("1.80."), label)
		}
	}

	@Test
	fun compilerAndGitTranscriptsParseAcrossDistros() {
		for (label in labels("cc")) {
			val result = parsed("cc", label)
			assertEquals("gcc", result["family"], label)
			val version = result.getValue("version")
			assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(version), "$label $result")
		}
		for (label in labels("git")) {
			assertTrue(
                Regex("\\d+\\.\\d+(\\.\\d+)?").matches(parsed("git", label).getValue("version")),
                label,
            )
		}
	}

	@Test
	fun phpReportsThreadSafetyAndSapi() {
		for (label in labels("php")) {
			val result = parsed("php", label)
			assertEquals("cli", result["sapi"], label)
			assertTrue(result["thread-safety"] in setOf("NTS", "ZTS"), "$label $result")
		}
	}

	@Test
	fun coreutilsFlavorsAreDistinguished() {
		assertEquals("gnu", parsed("coreutils", "debian-bookworm")["flavor"])
		assertEquals("busybox", parsed("coreutils", "alpine-3.20")["flavor"])
	}

	@Test
	fun unknownFormatsDegradeToUnparsedWithRawLine() {
		val attrs = Scanners.attributes(scanner("node"), "node: something new 99\nsecond line\n")
		assertEquals(
			listOf(
				Attribute("tool.node.version", "unparsed", "scanner:node --version"),
				Attribute("tool.node.raw", "node: something new 99", "scanner:node --version"),
			),
			attrs,
		)
	}

	@Test
	fun fuzzedAndTruncatedInputNeverThrows() {
		val random = Random(1234)
		val alphabet = "vV0123456789. \n\"()/gjP-:=ox"
		repeat(300) {
			val chars = CharArray(random.nextInt(0, 80)) { alphabet.random(random) }
			val text = chars.concatToString()
			for (s in Scanners.all) Scanners.attributes(s, text)
		}
		for (name in Fixtures.names) {
			val tool = name.substringBefore('/')
			val full = Fixtures.text(name)
			for (n in 0..full.length step 3) Scanners.attributes(scanner(tool), full.take(n))
		}
	}

	@Test
	fun absentToolsProduceNoAttributes() {
		val missing =
		    FakeHost(
                commands = mapOf(
                    listOf("node", "--version") to CommandResult(127, "sh: node: not found\n"),
                ),
            )
		assertEquals(emptyList(), Scanners.scan(missing))
		assertEquals(emptyList(), Scanners.scan(FakeHost(canRun = false)))
	}

	@Test
	fun scanReadsCommandOutputFromTheHost() {
		val host =
		    FakeHost(commands = mapOf(listOf("node", "--version") to CommandResult(0, "v22.4.1\n")))
		assertEquals(
			listOf(Attribute("tool.node.version", "22.4.1", "scanner:node --version")),
			Scanners.scan(host),
		)
	}

	@Test
	fun kitScriptsListEveryScannerCommand() {
		val sh = Kit.sh()
		val ps1 = Kit.ps1()
		for (s in Scanners.all) {
			assertTrue("run ${s.tool} " in sh, s.tool)
			assertTrue("Run-Tool '${s.tool}' '${s.command.first()}'" in ps1, s.tool)
		}
	}

	@Test
	fun kitOutputParsesBackIntoEntries() {
		val text = "@@ node exit=0\nv22.4.1\n@@ end\n" +
			"@@ java exit=127\nsh: java: not found\n@@ end\n"
		val entries = Kit.parse(text)
		assertEquals(0, entries.getValue("node").exitCode)
		assertEquals("v22.4.1", entries.getValue("node").output)
		assertEquals(127, entries.getValue("java").exitCode)
		assertEquals(
			listOf(Attribute("tool.node.version", "22.4.1", "scanner:node --version")),
			Scanners.fromTranscripts(entries),
		)
	}

	@Test
	fun kitRunOnTheRealHostParsesIdenticallyToNativeScan() {
		val host = systemHost()
		if (host.os.startsWith("windows") || host.run(listOf("sh", "-c", "exit 0")) == null) return
		val script = host.run(listOf("sh", "-c", Kit.sh()))
		assertTrue(script != null)
		val viaKit = Scanners.fromTranscripts(Kit.parse(script.output))
		val native = Scanners.scan(host)
		assertEquals(native.sortedBy { it.path }, viaKit.sortedBy { it.path })
		assertTrue(native.any { it.path == "tool.coreutils.flavor" })
	}

	@Test
	fun capsuleIncludesToolAttributesOnRequest() {
		val host =
		    FakeHost(
                commands = mapOf(
                    listOf("git", "--version") to CommandResult(0, "git version 2.45.2\n"),
                ),
            )
		val capsule = Capture.run(host, "t", tools = true)
		assertEquals("2.45.2", capsule.attributes.single { it.path == "tool.git.version" }.value)
		assertTrue(Capture.run(host, "t").attributes.none { it.path.startsWith("tool.") })
		assertEquals(KitEntry(0, "x").output, "x")
		assertEquals(Scanner::class, scanner("git")::class)
	}

	@Test
	fun libcTranscriptsNameTheFamilyAndVersion() {
		val expected = mapOf(
			"musl-alpine-3.18" to ("musl" to "1.2.4"),
			"musl-alpine-3.20" to ("musl" to "1.2.5"),
			"musl-alpine-3.22" to ("musl" to "1.2.5"),
			"glibc-debian-bookworm" to ("glibc" to "2.36"),
			"glibc-ubuntu-24.04" to ("glibc" to "2.39"),
		)
		assertEquals(expected.keys, labels("libc").toSet())
		for ((label, want) in expected) {
			val result = parsed("libc", label)
			assertEquals(want, result.getValue("family") to result.getValue("version"), label)
		}
	}

	@Test
	fun libcWithoutAVersionLineKeepsTheFamily() {
		val text = "musl libc (x86_64)\nDynamic Program Loader\n"
		val attrs = Scanners.attributes(scanner("libc"), text)
		assertEquals(listOf("tool.libc.family"), attrs.map { it.path })
		assertEquals("musl", attrs.single().value)
	}

	@Test
	fun unknownLibcOutputDegradesToUnparsed() {
		val attrs = Scanners.attributes(scanner("libc"), "ldd: unrecognized\n")
		assertEquals(
			listOf("tool.libc.version" to "unparsed", "tool.libc.raw" to "ldd: unrecognized"),
			attrs.map { it.path to it.value },
		)
	}

	@Test
	fun pythonMarkerTranscriptsDistinguishManagedInterpreters() {
		val managed = mapOf(
			"alpine-3.18" to "false",
			"alpine-3.20" to "true",
			"alpine-3.22" to "true",
			"python-3.12-alpine" to "false",
		)
		assertEquals(managed.keys, labels("python-managed").toSet())
		for ((label, want) in managed) {
			val attrs = Scanners.attributes(
				scanner("python-managed"),
				Fixtures.text("python-managed/$label.txt"),
			)
			assertEquals(listOf("tool.python.externally-managed"), attrs.map { it.path }, label)
			assertEquals(want, attrs.single().value, label)
		}
	}

	@Test
	fun pythonMarkerNeverClobbersThePythonVersion() {
		val entries = mapOf(
			"python" to KitEntry(0, "Python 3.12.3\n"),
			"python-managed" to KitEntry(0, "True\n"),
		)
		val byPath = Scanners.fromTranscripts(entries).associate { it.path to it.value }
		assertEquals(
			mapOf(
			"tool.python.version" to "3.12.3",
			"tool.python.externally-managed" to "true",
		),
			byPath,
		)
		val broken = Scanners.fromTranscripts(mapOf("python-managed" to KitEntry(1, "Traceback\n")))
		assertEquals(emptyList(), broken)
	}

	@Test
	fun visualStudioVersionComesFromVswhere() {
		val vs2022 = parsed("visualstudio", "vs-2022-synthetic")
		val vs2026 = parsed("visualstudio", "vs-2026-synthetic")
		assertEquals("17.14.36301.6", vs2022.getValue("version"))
		assertEquals("18.0.11222.15", vs2026.getValue("version"))
		val noise = Scanners.attributes(scanner("visualstudio"), "no installation found\n")
		assertEquals("unparsed", noise.first().value)
	}

	@Test
	fun newScannersRunThroughTheHostAndTheKit() {
		val host = FakeHost(
			commands = mapOf(
				listOf("ldd", "--version") to
					CommandResult(1, Fixtures.text("libc/musl-alpine-3.20.txt")),
				scanner("python-managed").command to CommandResult(0, "False\n"),
			),
		)
		val byPath = Scanners.scan(host).associate { it.path to it.value }
		assertEquals("musl", byPath["tool.libc.family"])
		assertEquals("1.2.5", byPath["tool.libc.version"])
		assertEquals("false", byPath["tool.python.externally-managed"])
		assertTrue("run python-managed 'python3' '-c' " in Kit.sh())
		assertTrue("Run-Tool 'visualstudio' 'vswhere'" in Kit.ps1())
	}
}
