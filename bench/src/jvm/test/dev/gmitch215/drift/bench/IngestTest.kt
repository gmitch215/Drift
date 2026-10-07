package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.string
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestTest {
	private fun scenario(vararg changes: Pair<String, String>): Scenario {
		val fields = linkedMapOf(
			"id" to "\"t\"",
			"dimension" to "\"env\"",
			"runtime" to "\"sh\"",
			"program" to "\"echo ok\"",
			"signature" to "\"boom\"",
			"trials" to "\"10\"",
			"gate" to "\"500\"",
			"base" to "{\"image\":\"debian:12-slim\"}",
			"green" to "{\"env\":{\"A\":\"1\"}}",
			"red" to "{\"env\":{\"A\":\"2\"}}",
			"cause" to "{\"dimension\":\"env\",\"path\":\"env.A\",\"direction\":\"lower\"}",
		)
		changes.forEach { fields[it.first] = it.second }
		val text = fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
		return Template.fromJson(CanonicalJson.parse(text)).build(1, emptyMap(), 12345)
	}

	private fun capsule(vararg attrs: Triple<String, String, String>): String {
		val items = attrs.joinToString(",") {
			"{\"path\":\"${it.first}\",\"source\":\"x\",\"stability\":\"${it.third}\"," +
				"\"value\":\"${it.second}\"}"
		}
		return "{\"attributes\":[$items],\"label\":\"t\",\"probes\":[],\"schema\":1}"
	}

	private fun run(
		s: Scenario,
		arm: Arm,
		capsule: String?,
		flip: Set<Int> = emptySet(),
		sig: Boolean = true,
	): ArmRun {
		val predicted = s.predicted(arm).toSet()
		val failing = (predicted - flip) + (flip - predicted)
		val trials = (0 until s.trials).map {
			val failed = it in failing
			TrialOutcome(it, if (failed) 1 else 0, false, failed && sig, "tail")
		}
		return ArmRun(capsule, trials, true)
	}

	private val greenCapsule =
		capsule(Triple("env.A", "1", "static"), Triple("env.PWD", "/a", "volatile"))
	private val redCapsule =
		capsule(Triple("env.A", "2", "static"), Triple("env.PWD", "/b", "volatile"))

	private fun verdict(s: Scenario, green: ArmRun, red: ArmRun, require: Boolean = true) =
		Ingest.scenario(s, green, red, "abc", require)

	@Test
	fun aScenarioThatBehavesIsValid() {
		val s = scenario()
		val v = verdict(s, run(s, s.green, greenCapsule), run(s, s.red, redCapsule))
		assertTrue(v.valid, v.reasons.toString())
		val capsule = v.result.fields.getValue("capsule") as JsonObject
		assertEquals(listOf("env.A"), capsule.fields.getValue("diff").array().map { it.string() })
		assertEquals(setOf("green", "red"), v.capsules.keys)
		assertEquals("abc", v.result.fields.getValue("drift_sha256").string())
	}

	@Test
	fun aGreenFailureInvalidatesTheScenario() {
		val s = scenario()
		val v = verdict(
			s,
			run(s, s.green, greenCapsule, flip = setOf(3)),
			run(s, s.red, redCapsule),
		)
		assertFalse(v.valid)
		assertTrue(v.reasons.any { it.startsWith("green arm failures differ") })
	}

	@Test
	fun aRedRateFarFromTheDeclaredOneInvalidatesIt() {
		val s = scenario("gate" to "\"200\"")
		val predicted = s.predicted(s.red).toSet()
		val extra = (0 until s.trials).filter { it !in predicted }.toSet()
		val v = verdict(s, run(s, s.green, greenCapsule), run(s, s.red, redCapsule, flip = extra))
		assertFalse(v.valid)
		assertTrue(v.reasons.any { it.startsWith("red arm failed 10/10, declared 200 permille") })
	}

	@Test
	fun aFailureWithoutTheSignatureInvalidatesIt() {
		val s = scenario()
		val v = verdict(s, run(s, s.green, greenCapsule), run(s, s.red, redCapsule, sig = false))
		assertFalse(v.valid)
		assertTrue(v.reasons.contains("red arm failed without the declared signature"))
	}

	@Test
	fun anIncompleteArmInvalidatesIt() {
		val s = scenario()
		val full = run(s, s.red, redCapsule)
		val cut = ArmRun(redCapsule, full.trials.take(4), false)
		val v = verdict(s, run(s, s.green, greenCapsule), cut)
		assertTrue(v.reasons.contains("red arm incomplete: 4/10 trials"))
	}

	@Test
	fun theCauseMustBeVisibleExactlyWhenDeclared() {
		val s = scenario("visible" to "false")
		val v = verdict(s, run(s, s.green, greenCapsule), run(s, s.red, redCapsule))
		assertTrue(v.reasons.any { it.startsWith("cause env.A declared visible=false") })
		val same = verdict(s, run(s, s.green, greenCapsule), run(s, s.red, greenCapsule))
		assertTrue(same.valid, same.reasons.toString())
	}

	@Test
	fun aMissingCapsuleOnlyMattersWhenRequired() {
		val s = scenario()
		val strict = verdict(s, run(s, s.green, null), run(s, s.red, null))
		assertTrue(strict.reasons.contains("no capsule captured"))
		val loose = verdict(s, run(s, s.green, null), run(s, s.red, null), require = false)
		assertTrue(loose.valid)
		assertTrue(loose.capsules.isEmpty())
	}

	@Test
	fun theResultListsUnseenAndIncidentalAttributes() {
		val s = scenario(
			"decoys" to "[{\"dimension\":\"env\",\"path\":\"env.B\"}]",
			"red" to "{\"env\":{\"A\":\"2\",\"B\":\"x\"}}",
		)
		val g = capsule(Triple("env.A", "1", "static"), Triple("env.C", "1", "static"))
		val r = capsule(Triple("env.A", "2", "static"), Triple("env.C", "2", "static"))
		val v = verdict(s, run(s, s.green, g), run(s, s.red, r))
		val c = v.result.fields.getValue("capsule") as JsonObject
		assertEquals(
			listOf("env.B"),
			c.fields.getValue("declared_unseen").array().map { it.string() },
		)
		assertEquals(listOf("env.C"), c.fields.getValue("incidental").array().map { it.string() })
	}

	@Test
	fun overridesAreAppliedToBothCapsulesAndSorted() {
		val g = Capsules.apply(greenCapsule, mapOf("kernel.release" to "6.8", "env.A" to "9"))
		val r = Capsules.apply(redCapsule, mapOf("kernel.release" to "5.4"))
		assertEquals(listOf("env.A", "env.PWD", "kernel.release"), attributePaths(g))
		assertTrue(g.contains("\"value\":\"9\""))
		assertTrue(g.contains("bench-synthetic"))
		assertEquals(listOf("env.A", "kernel.release"), Capsules.diff(g, r))
		assertEquals(greenCapsule, Capsules.apply(greenCapsule, emptyMap()))
	}

	@Test
	fun diffIgnoresVolatileAttributesAndSeesOneSidedOnes() {
		val g = capsule(Triple("a", "1", "static"), Triple("v", "1", "volatile"))
		val r =
			capsule(
				Triple("a", "1", "static"),
				Triple("v", "2", "volatile"),
				Triple("b", "1", "static"),
			)
		assertEquals(listOf("b"), Capsules.diff(g, r))
	}

	@Test
	fun harnessOutputParses() {
		val out = "CAPSULE-BEGIN\n{\"attributes\":[]}\nCAPSULE-END\n" +
			"TRIAL\t0\t0\t1\t0\tok\nTRIAL\t1\t137\t1\t1\tKilled\nTRIAL\t2\t1\t0\t0\t\nnoise\nDONE\n"
		val run = ArmRun.parse(out)
		assertEquals("{\"attributes\":[]}", run.capsule)
		assertTrue(run.done)
		assertEquals(listOf(0, 137, 1), run.trials.map { it.exit })
		assertEquals(listOf(false, true, false), run.trials.map { it.signature })
		assertEquals("Killed", run.trials[1].tail)
		assertEquals("", run.trials[2].tail)
		val bare = ArmRun.parse("TRIAL\t0\t0\t0\t0\tok\n")
		assertEquals(null, bare.capsule)
		assertFalse(bare.done)
	}

	@Test
	fun importingProbedCapsulesCopiesTheBaselinesAndRefusesATestId() {
		val committed = Path.of(".")
		val dev = Work(committed).devIds().sorted().first()
		val test = SplitRule.split(Work(committed).loadScenarios().map { it.id }).test.first()
		val root = Files.createTempDirectory("bench-probed")
		val data = root.resolve("data").createDirectories()
		data.resolve("split.json").writeText(committed.resolve("data/split.json").readText())
		val solved = root.resolve("solved")
		fun arm(id: String, name: String, text: String) {
			val dir = solved.resolve("$id/arms/$name").createDirectories()
			dir.resolve("capsule.json").writeText(text)
		}
		arm(dev, "p0-control", greenCapsule)
		arm(dev, "p0-treatment", redCapsule)
		arm(dev, "x1-treatment", "not a capsule")
		assertEquals(listOf(dev), Work(root).importProbed(solved))
		assertEquals(greenCapsule + "\n", data.resolve("probed/$dev.green.json").readText())
		assertEquals(redCapsule + "\n", data.resolve("probed/$dev.red.json").readText())
		assertEquals(setOf("$dev.green.json", "$dev.red.json"), data.resolve("probed").names())
		arm(test, "p0-control", greenCapsule)
		arm(test, "p0-treatment", redCapsule)
		assertFailsWith<SealedException> { Work(root).importProbed(solved) }
		assertEquals(setOf("$dev.green.json", "$dev.red.json"), data.resolve("probed").names())
	}

	private fun Path.names() = listDirectoryEntries().map { it.name }.toSet()

	private fun attributePaths(capsule: String): List<String> =
		((CanonicalJson.parse(capsule) as JsonObject).fields.getValue("attributes") as JsonArray)
			.items
			.map { ((it as JsonObject).fields.getValue("path") as JsonString).value }
}
