package dev.gmitch215.drift.tools.ladder

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LadderTest {
	private val catalog = Path.of(
		"..",
		"scan",
		"src",
		"common",
		"main",
		"dev",
		"gmitch215",
		"drift",
		"scan",
		"probe",
		"kotlin",
	)
	private val fixture = Path.of("..", "fixtures", "atlas", "jvm.txt")

	private val sample = """
		package x

		import dev.gmitch215.drift.scan.probe.bits
		import kotlin.math.sqrt

		internal val sampleProbes = listOf(
			KotlinProbe(
				id = "kotlin.a.one",
				question = "q",
				source = ${"\"\"\""}
					line("root", sqrt(4.0))
					line("end", "^${'$'}")
				${"\"\"\""},
			) { },
			KotlinProbe(
				id = "kotlin.a.two",
				question = "q",
				source = ${"\"\"\""}
					line("bits", bits(1.0))
				${"\"\"\""},
			) { },
		)
	""".trimIndent()

	private fun realProbes(): List<LadderProbe> = LadderProbes.all(
		catalog.listDirectoryEntries("*Probes.kt").sortedBy { it.name }.map { it.readText() },
	)

	@Test
	fun extractionReadsIdsAndSourceTextWithTheDollarEscapeResolved() {
		val probes = LadderProbes.extract(sample)
		assertEquals(listOf("kotlin.a.one", "kotlin.a.two"), probes.map { it.id })
		assertEquals("line(\"root\", sqrt(4.0))\nline(\"end\", \"^$\")", probes[0].source)
		assertEquals(listOf("kotlin.math.sqrt"), probes[0].imports)
	}

	@Test
	fun theRealCatalogYieldsEveryProbeOnceInIdOrder() {
		val probes = realProbes()
		val declared = catalog.listDirectoryEntries("*Probes.kt").sumOf {
			Regex("""\bid = "kotlin\.""").findAll(it.readText()).count()
		}
		assertEquals(declared, probes.size)
		assertEquals(38, probes.size)
		assertEquals(probes.map { it.id }.sorted(), probes.map { it.id })
		assertTrue(probes.all { it.source.isNotBlank() && !it.source.contains("\${'$'}") })
	}

	@Test
	fun aProbeFileCarriesOnlyTheImportsAndHelpersItUses() {
		val probes = LadderProbes.extract(sample)
		val one = LadderProbes.file(0, probes[0])
		val two = LadderProbes.file(1, probes[1])
		assertContains(one, "import kotlin.math.sqrt\n")
		assertFalse(one.contains("private fun bits"))
		assertContains(two, "private fun bits(d: Double)")
		assertFalse(two.contains("import kotlin.math.sqrt"))
		assertContains(two, "fun probe1(): String {")
		assertContains(two, "\t\tline(\"bits\", bits(1.0))\n")
	}

	@Test
	fun aLocalDeclarationInTheSourceSuppressesTheSharedHelper() {
		val own = LadderProbe("kotlin.x", "fun find(a: Int) = a\nfind(1)", emptyList())
		assertFalse(LadderProbes.file(0, own).contains("private fun Ctx.find"))
		val shared = LadderProbe("kotlin.y", "find(\"l\", \"a\", \"a\")", emptyList())
		assertContains(LadderProbes.file(0, shared), "private fun Ctx.find")
		val holder = LadderProbe("kotlin.z", "failure(\"l\") { Holder().name }", emptyList())
		assertContains(LadderProbes.file(0, holder), "private class Holder")
	}

	@Test
	fun theProgramIsDeterministicAndSkipsExcludedProbes() {
		val probes = LadderProbes.extract(sample)
		val all = LadderProbes.program(probes, emptySet())
		assertEquals(all, LadderProbes.program(probes, emptySet()))
		assertEquals(listOf("Core.kt", "P_00.kt", "P_01.kt", "Main.kt"), all.keys.toList())
		val partial = LadderProbes.program(probes, setOf("kotlin.a.one"))
		assertEquals(listOf("Core.kt", "P_01.kt", "Main.kt"), partial.keys.toList())
		assertFalse(partial.getValue("Main.kt").contains("kotlin.a.one"))
		assertContains(partial.getValue("Main.kt"), "Pair(\"kotlin.a.two\", { probe1() })")
		assertFalse(all.getValue("Main.kt").contains(",\n\t)"))
	}

	@Test
	fun compilerLogsGiveTheFirstErrorPerProbe() {
		val log = """
			/w/src.1/P_03.kt:12:5: error: unresolved reference: floorDiv
			    a.floorDiv(2)
			      ^
			/w/src.1/P_03.kt:14:5: error: unresolved reference: mod
			file:///w/src.1/P_07.kt:4:9: error: unresolved reference 'cbrt'.
			e: file:///w/src.1/P_11.kt:2:1 Unsupported feature   here
			/w/src.1/P_09.kt:1:1: warning: unused
		""".trimIndent()
		assertEquals(
			mapOf(
				3 to "unresolved reference: floorDiv",
				7 to "unresolved reference 'cbrt'.",
				11 to "Unsupported feature here",
			),
			LadderRun.errors(log),
		)
		assertFalse(LadderRun.hasUnattributed(log))
		assertTrue(LadderRun.hasUnattributed("/w/src.1/Main.kt:3:3: error: broken"))
		assertTrue(LadderRun.hasUnattributed("error: could not find 'main'"))
	}

	@Test
	fun theExcludedListRoundTrips() {
		val map = linkedMapOf("kotlin.a" to "m one", "kotlin.b" to "m two")
		assertEquals(map, LadderRun.parseExcluded(LadderRun.renderExcluded(map)))
	}

	@Test
	fun aRunOfTheRecordedFixtureAssemblesToTheSameBytes() {
		val text = fixture.readText()
		val run = text.substringAfter("kotlin.target = jvm\n")
		val ids = LadderRun.parseRun(run).keys.toList()
		assertEquals(38, ids.size)
		assertEquals(text, LadderRun.assemble("2.4.20", "jvm", ids.shuffled(), emptyMap(), run))
	}

	@Test
	fun failedAndMissingProbesBecomeUnavailableCells() {
		val run = "@@ a\nx = 1\n@@ b [UNAVAILABLE]\nfailed Boom: bad\n"
		val text = LadderRun.assemble(
			"1.3.72",
			"jvm",
			listOf("c", "b", "a", "d"),
			mapOf("c" to "unresolved reference: cbrt"),
			run,
		)
		assertEquals(
			"# kotlin.version = 1.3.72\n# kotlin.target = jvm\n" +
				"@@ a\nx = 1\n" +
				"@@ b [UNAVAILABLE]\nfailed Boom: bad\n" +
				"@@ c [UNAVAILABLE]\ndoes not compile: unresolved reference: cbrt\n" +
				"@@ d [UNAVAILABLE]\nno output: the program ended before this probe finished\n",
			text,
		)
	}

	@Test
	fun aProbeThatKilledTheProgramRecordsItsExitStatus() {
		val exits = mapOf("a" to "139")
		val text = LadderRun.assemble("1.8.22", "linux", listOf("a"), emptyMap(), "", exits)
		assertEquals(
			"# kotlin.version = 1.8.22\n# kotlin.target = linux\n@@ a [UNAVAILABLE]\n" +
				"no output: the program ended before this probe finished (exit 139)\n",
			text,
		)
	}

	@Test
	fun aFakeCompilerEliminatesOnlyTheProbesThatDoNotCompile() {
		val probes = LadderProbes.all(
			listOf(sample.replace("bits(1.0)", "cbrt(1.0)")),
		)
		val excluded = linkedMapOf<String, String>()
		var rounds = 0
		while (true) {
			rounds++
			val files = LadderProbes.program(probes, excluded.keys)
			val bad = files.filterValues { it.contains("cbrt(") }.keys
			if (bad.isEmpty()) break
			val log = bad.joinToString("\n") {
				"/w/$it:3:3: error: unresolved reference: cbrt"
			}
			val fresh = LadderRun.errors(log).filterKeys { it < probes.size }
			for ((index, message) in fresh) excluded[probes[index].id] = message
		}
		assertEquals(2, rounds)
		assertEquals(mapOf("kotlin.a.two" to "unresolved reference: cbrt"), excluded)
		val text = LadderRun.assemble(
			"1.3.72",
			"jvm",
			probes.map {
			it.id
		},
			excluded,
			"@@ kotlin.a.one\nr = 1\n",
		)
		assertContains(text, "@@ kotlin.a.one\nr = 1\n")
		assertContains(
			text,
			"@@ kotlin.a.two [UNAVAILABLE]\ndoes not compile: unresolved reference: cbrt\n",
		)
	}
}
