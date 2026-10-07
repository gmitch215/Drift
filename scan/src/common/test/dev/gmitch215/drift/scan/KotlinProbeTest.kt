package dev.gmitch215.drift.scan

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.fixtures.AtlasVariants
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.probe.Family
import dev.gmitch215.drift.scan.probe.Probe
import dev.gmitch215.drift.scan.probe.ProbeContext
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.probe.kotlin.AtlasTranscript
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import dev.gmitch215.drift.scan.probe.kotlin.outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KotlinProbeTest {
	private val host = systemHost()
	private val platform = host.platform
	private val probes = KotlinCatalog.all.map { it.probe }
	private val pending = setOf("mingw")
	private val arm = setOf("aarch64", "arm64")
	private val target = if (platform == "linux" && host.arch in arm) "linux-arm64" else platform

	// debug test binaries do not fold libm constants, so linux reproduces a linux-built transcript
	private val file = if (target.startsWith("linux")) "$target@linux.txt" else "$target.txt"

	private fun results() = ProbeRunner.run(host, probes)

	// the jvm fixture is arm64 and x86-64 sets the sign bit of a generated NaN; linux is exact
	private fun portable(text: String) = when {
		target.startsWith("linux") -> text

		platform == "jvm" && host.arch !in arm ->
			text.lines().joinToString("\n") { it.substringBefore(" = ") }

		else -> text.replace("fff8000000000000", "7ff8000000000000")
	}

	private fun recorded(name: String): String = when (name) {
		in AtlasVariants.names -> AtlasVariants.text(name)

		in AtlasFixtures.names -> AtlasFixtures.text(name)

		else -> {
			val label = name.substringBefore("@").removeSuffix(".txt")
			val dir = if ("@" in name) "fixtures/atlas-variants" else "fixtures/atlas"
			fail(
				"no recorded transcript $name; run `drift atlas record --target $label` " +
					"on a matching host and save it as $dir/$name",
			)
		}
	}

	private fun labels(transcript: String): Map<String, String> =
		transcript.lines().filter { it.isNotEmpty() }
			.associate { it.substringBefore(" = ") to it.substringAfter(" = ") }

	private fun lines(id: String): Map<String, String> =
		labels(results().single { it.id == id }.transcript)

	@Test
	fun catalogIsConsistent() {
		val all = KotlinCatalog.all
		assertTrue(all.size >= 30, "only ${all.size} probes")
		assertEquals(all.size, all.map { it.id }.toSet().size)
		val shape = Regex("^kotlin\\.[a-z0-9]+\\.[a-z0-9-]+$")
		for (p in all) {
			assertTrue(shape.matches(p.id), p.id)
			assertEquals(Family.KOTLIN, p.probe.family, p.id)
			assertTrue(p.probe.needs.isEmpty(), p.id)
			assertTrue(p.question.endsWith("?"), p.id)
		}
	}

	@Test
	fun displayedSourceIsNotEmptyAndNamesEveryLabel() {
		val results = results().associateBy { it.id }
		for (p in KotlinCatalog.all) {
			assertTrue(p.source.isNotBlank(), "${p.id} has no displayed source")
			for (line in results.getValue(p.id).transcript.lines().filter { it.isNotEmpty() }) {
				val label = line.substringBefore(" = ").trim('\'')
				assertTrue(label in p.source, "${p.id} source lacks label '$label'")
			}
		}
	}

	@Test
	fun everyProbeReturnsOkWithAWellFormedTranscript() {
		for (r in results()) {
			assertEquals(ProbeStatus.OK, r.status, "${r.id}: ${r.transcript}")
			assertTrue(r.transcript.endsWith("\n"), r.id)
			val lines = r.transcript.lines()
			val reserved = lines.filter { it.startsWith("@@ ") || it.startsWith("# ") }
			assertTrue(reserved.isEmpty(), r.id)
		}
	}

	@Test
	fun runningTwiceGivesIdenticalOutput() {
		assertEquals(results(), results())
	}

	@Test
	fun theWholeSuiteRunsWithinTheTestBudget() {
		val start = TimeSource.Monotonic.markNow()
		results()
		assertTrue(start.elapsedNow() < 2.seconds, "took ${start.elapsedNow()}")
	}

	@Test
	fun aThrowingProbeCannotBreakTheScan() {
		val broken = listOf(
			Probe("test.exception", Family.KOTLIN) { throw IllegalStateException("x") },
			Probe("test.error", Family.KOTLIN) { throw AssertionError("y") },
			Probe("test.todo", Family.KOTLIN) { TODO() },
		)
		val mixed = ProbeRunner.run(host, broken + probes.first())
		assertEquals(
			listOf(ProbeStatus.UNAVAILABLE, ProbeStatus.UNAVAILABLE, ProbeStatus.UNAVAILABLE),
			mixed.take(3).map { it.status },
		)
		assertEquals("failed IllegalStateException: x\n", mixed[0].transcript)
		assertTrue(mixed[1].transcript.startsWith("failed AssertionError"), mixed[1].transcript)
		assertEquals(ProbeStatus.OK, mixed[3].status)
		assertEquals(results().first(), mixed[3])
	}

	@Test
	fun outcomeTurnsAnyThrowableIntoAStableMarker() {
		val context = ProbeContext(FakeHost())
		context.outcome("exception") { error("e") }
		context.outcome("error") { throw AssertionError("a") }
		context.outcome("value") { 1 + 1 }
		assertEquals(
			"exception = err:IllegalStateException\nerror = err:AssertionError\nvalue = ok:2\n",
			context.transcript(),
		)
	}

	@Test
	fun capsuleRecordsTheKotlinVersionAndTarget() {
		val attrs = Capture.run(host, "k", probes.take(1)).attributes.associateBy { it.path }
		assertEquals(KotlinBuild.VERSION, attrs.getValue("kotlin.version").value)
		assertEquals(platform, attrs.getValue("kotlin.target").value)
	}

	@Test
	fun theBuildVersionExtendsTheRuntimeVersion() {
		val runtime = KotlinVersion.CURRENT.toString()
		assertTrue(KotlinBuild.VERSION.startsWith(runtime), KotlinBuild.VERSION)
	}

	@Test
	fun theBetaFixturesCarryTheHeaderTheBuildWouldWrite() {
		for (target in listOf("jvm", "macos", "wasm")) {
			val text = AtlasFixtures.text("2.5.0-beta1/$target.txt")
			val parsed = AtlasTranscript.parse(text)
			assertEquals("2.5.0-Beta1", parsed.meta["kotlin.version"])
			val header = AtlasTranscript.render(target, emptyList())
				.replace("kotlin.version = ${KotlinBuild.VERSION}", "kotlin.version = 2.5.0-Beta1")
			assertTrue(text.startsWith(header), "$target\n$header")
			assertEquals(KotlinCatalog.all.map { it.id }, parsed.probes.keys.toList())
		}
	}

	@Test
	fun transcriptTextRoundTrips() {
		val sample = listOf(
			ProbeResult("b.two", ProbeStatus.OK, "x = 1\ny = 2\n"),
			ProbeResult("a.one", ProbeStatus.UNAVAILABLE, "failed X: boom\n"),
		)
		val text = AtlasTranscript.render("fake", sample)
		assertEquals(
			"# kotlin.version = ${KotlinBuild.VERSION}\n# kotlin.target = fake\n" +
				"@@ a.one [UNAVAILABLE]\nfailed X: boom\n@@ b.two\nx = 1\ny = 2\n",
			text,
		)
		val parsed = AtlasTranscript.parse(text)
		assertEquals("fake", parsed.meta["kotlin.target"])
		assertEquals(listOf("a.one", "b.two"), parsed.probes.keys.toList())
		assertEquals("x = 1\ny = 2\n", parsed.probes["b.two"])
	}

	@Test
	fun currentPlatformMatchesTheRecordedTranscript() {
		val actual = AtlasTranscript.render(target, results())
		if (target in pending && file !in AtlasFixtures.names + AtlasVariants.names) {
			println("no recorded transcript for $target yet, so nothing was compared")
			return
		}
		val expected = AtlasTranscript.parse(recorded(file))
		assertEquals(KotlinBuild.VERSION, expected.meta["kotlin.version"])
		assertEquals(target, expected.meta["kotlin.target"])
		assertEquals(KotlinCatalog.all.map { it.id }, expected.probes.keys.toList())
		val got = AtlasTranscript.parse(actual).probes
		for ((id, transcript) in expected.probes) {
			assertEquals(portable(transcript), portable(got.getValue(id)), "$target $id")
		}
		assertEquals(portable(recorded(file)), portable(actual))
	}

	@Test
	fun everyRecordedTranscriptCoversExactlyTheCatalog() {
		val ids = KotlinCatalog.all.map { it.id }
		for (name in AtlasFixtures.names.filter { it.endsWith(".txt") }) {
			val recorded = AtlasTranscript.parse(AtlasFixtures.text(name)).probes.keys.toList()
			assertEquals(ids, recorded, name)
		}
		for (name in AtlasVariants.names.filter { it.endsWith(".txt") }) {
			val parsed = AtlasTranscript.parse(AtlasVariants.text(name))
			assertEquals(ids, parsed.probes.keys.toList(), name)
			assertEquals(KotlinBuild.VERSION, parsed.meta["kotlin.version"], name)
			assertEquals(name.substringBefore("@"), parsed.meta["kotlin.target"], name)
			val buildHost = name.substringAfter("@").removeSuffix(".txt")
			assertTrue(buildHost in setOf("linux", "macos", "windows"), name)
		}
	}

	@Test
	fun linuxVariantsDifferFromTheCommittedColumnsOnlyInThreeLibmLines() {
		for (target in listOf("linux", "linux-arm64")) {
			val committed = AtlasTranscript.parse(AtlasFixtures.text("$target.txt")).probes
			val variant = AtlasTranscript.parse(AtlasVariants.text("$target@linux.txt")).probes
			val differing = committed.keys.filter { committed[it] != variant[it] }
			assertEquals(listOf("kotlin.math.transcendental-bits"), differing, target)
			val a = labels(committed.getValue("kotlin.math.transcendental-bits"))
			val b = labels(variant.getValue("kotlin.math.transcendental-bits"))
			val labelsThatDiffer = a.keys.filter { a[it] != b[it] }.toSet()
			assertEquals(setOf("tan1", "asin-half", "acos-half"), labelsThatDiffer, target)
		}
	}

	@Test
	fun variantsStayOutOfTheDataset() {
		assertTrue(AtlasFixtures.names.none { "@" in it }, AtlasFixtures.names.toString())
		assertTrue(AtlasVariants.names.filter { it.endsWith(".txt") }.all { "@" in it })
	}

	@Test
	fun aMissingTranscriptSaysHowToRecordIt() {
		val variant = assertFailsWith<AssertionError> { recorded("linux-riscv64@linux.txt") }
		val message = variant.message.orEmpty()
		assertTrue("drift atlas record --target linux-riscv64" in message, message)
		assertTrue("fixtures/atlas-variants/linux-riscv64@linux.txt" in message, message)
		val column = assertFailsWith<AssertionError> { recorded("mingw.txt") }
		assertTrue("fixtures/atlas/mingw.txt" in column.message.orEmpty(), column.message.orEmpty())
	}

	@Test
	fun portableProbesMatchIndependentlyComputedValues() {
		assertEquals(
			mapOf(
				"-7/2" to "-3", "-7%2" to "-1", "-7.mod(2)" to "1", "-7.floorDiv(2)" to "-4",
				"7/-2" to "-3", "7%-2" to "1", "7.mod(-2)" to "-1", "7.floorDiv(-2)" to "-4",
				"long-7%3" to "-1", "7/2" to "3", "7/2.0" to "3.5",
			),
			lines("kotlin.int.division-modulo").filterKeys {
				it in setOf(
					"-7/2", "-7%2", "-7.mod(2)", "-7.floorDiv(2)", "7/-2", "7%-2", "7.mod(-2)",
					"7.floorDiv(-2)", "long-7%3", "7/2", "7/2.0",
				)
			},
		)
		val radix = lines("kotlin.int.radix")
		assertEquals("11111111", radix["255-bin"])
		assertEquals("-ff", radix["neg255-hex"])
		assertEquals("-80000000", radix["int-min-hex"])
		assertEquals("1" + "0".repeat(63), radix["long-min-bin"].orEmpty().removePrefix("-"))
		assertEquals("255", radix["'ff'"])
		assertEquals("255", radix["'FF'"])
		assertEquals("-2147483648", radix["'-80000000'"])
		assertEquals("null", radix["'80000000'"])
		assertEquals("1295", radix["zz-36"])
		val bits = lines("kotlin.long.bit-ops")
		assertEquals("-9223372036854775808", bits["1shl63"])
		assertEquals("15", bits["-1ushr60"])
		assertEquals("-1", bits["-1shr60"])
		assertEquals("32", bits["count-ones"])
		assertEquals("56", bits["leading-zeros"])
		assertEquals("8", bits["trailing-zeros"])
		assertEquals("512", bits["highest-one"])
		assertEquals("8", bits["lowest-one"])
		val hash = lines("kotlin.string.hashcode")
		assertEquals("99162322", hash["hello"])
		assertEquals("0", hash["empty"])
		assertEquals("30817", hash["list"])
		assertEquals("6", hash["set"])
		assertEquals("151", hash["pair"])
		assertEquals("1231", hash["boolean"])
		assertEquals("97", hash["char"])
	}
}
