package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomExtractor
import dev.gmitch215.drift.symptom.SymptomKind.DURATION
import dev.gmitch215.drift.symptom.SymptomKind.EXIT
import dev.gmitch215.drift.symptom.SymptomKind.SIGNATURE
import dev.gmitch215.drift.symptom.SymptomKind.STEP
import dev.gmitch215.drift.symptom.SymptomKind.TRANSPORT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunFixtureTest {
	private val green = "36702683742"
	private val reds = listOf("36995781138", "37114464625", "37195797966")
	private val nightlies = listOf("35585969640", "36232485230", "36311117223", "36412389380")

	private fun steps(id: String): List<Step> =
		CanonicalJson.parse(RunFixtures.text("runs/$id.json")).obj().require("jobs").array()
			.flatMap { job ->
				val name = job.obj().require("name").string()
				job.obj().require("steps").array().map { s ->
					val o = s.obj()
					fun time(key: String) = (o[key] as? JsonString)?.value?.let {
						SymptomExtractor.epochAt(it, 0)
					}
					val start = time("startedAt")
					val end = time("completedAt")
					Step(
						name,
						o.require("name").string(),
						StepConclusion.ofConclusion(o.require("conclusion").string()),
						if (start != null && end != null) end - start else null,
					)
				}
			}

	private fun run(id: String): Run {
		val summary = CanonicalJson.parse(RunFixtures.text("runs/$id.json")).obj()
		return Run(
			id = id,
			outcome = RunOutcome.ofConclusion(summary.require("conclusion").string()),
			steps = steps(id),
			symptoms = SymptomExtractor.extract(RunFixtures.text("logs/$id.log")),
			capsule = Capsule.parse(RunFixtures.text("capsules/$id.json")),
		)
	}

	private val transport = "Docker E2E"

	private fun redSymptoms(
		stepLine: Int,
		durationMs: Long,
		lines: List<Int>,
		counts: List<Int>,
		resetLine: Int?,
		signatureLines: List<Int>,
		signatureCounts: List<Int>,
		exitLine: Int,
	): List<Symptom> {
		val job = transport
		val out = ArrayList<Symptom>()
		out += Symptom(STEP, "bun run test:e2e:coverage", stepLine, job)
		out += Symptom(DURATION, durationMs.toString(), stepLine, job)
		if (resetLine != null) out += Symptom(TRANSPORT, "connection reset", resetLine, job, 2)
		out += Symptom(TRANSPORT, "network connection lost", lines[0], job, counts[0])
		out += Symptom(TRANSPORT, "stream disconnected", lines[1], job, counts[1])
		out += Symptom(TRANSPORT, "broken pipe", lines[2], job, 2)
		out += Symptom(
			SIGNATURE,
			"AssertionError: Error: Network connection lost.",
			signatureLines[0],
			job,
			signatureCounts[0],
		)
		out += Symptom(
			SIGNATURE,
			"FindingError: /, /user/login, /node/1, /sites/default/files/preview-probe.txt " +
				"did not match the source",
			signatureLines[1],
			job,
		)
		out += Symptom(SIGNATURE, "Error: /rows answered 500", signatureLines[2], job)
		out += Symptom(EXIT, "1", exitLine, job, 2)
		return out.sortedWith(compareBy({ it.line }, { it.kind.ordinal }, { it.value }))
	}

	private val expected = mapOf(
		reds[0] to redSymptoms(
			1286,
			198_273,
			listOf(1531, 1916, 2199),
			listOf(20, 1),
			1529,
			listOf(2224, 2252, 2261),
			listOf(2),
			2266,
		),
		reds[1] to redSymptoms(
			853,
			210_033,
			listOf(1103, 1101, 1624),
			listOf(14, 2),
			null,
			listOf(1641, 1663, 1672),
			listOf(1),
			1677,
		),
		reds[2] to redSymptoms(
			565,
			201_619,
			listOf(804, 1193, 1477),
			listOf(22, 1),
			802,
			listOf(1502, 1530, 1539),
			listOf(2),
			1544,
		),
	)

	private val hashes = mapOf(
		green to
			"ec159a2cecbcb2c5d141ec77af377393f5c6cb07e1331f1762c6e72483fffceb",
		reds[0] to
			"b0d177ee089213c7bbf92f68fec6d4741d2ed7d1334951df4ae0ca282a1041f8",
		reds[1] to
			"aee3415fa10ec8915b5e193fa6ccbc3de49582b37fe5a94c7ed0fee5fd648cbd",
		reds[2] to
			"a1a1dd10f496e964759c7dcca15326827a95a58b51115c9bdd176a817c93849f",
	)

	@Test
	fun redLogsGiveTheExpectedSymptoms() {
		for (id in reds) assertEquals(expected.getValue(id), run(id).symptoms, id)
	}

	@Test
	fun everyPassingLogGivesNoSymptomAtAll() {
		for (id in listOf(green) + nightlies) {
			val symptoms = SymptomExtractor.extract(RunFixtures.text("logs/$id.log"))
			assertEquals(emptyList(), symptoms, id)
			assertEquals(emptyMap(), run(id).facts(), id)
		}
	}

	@Test
	fun runGoldensAreIdenticalOnEveryTarget() {
		for ((id, hash) in hashes) {
			val run = run(id)
			assertEquals(hash, run.hash(), id)
			assertEquals(run, Run.parse(run.canonical()), id)
			assertEquals(if (id == green) RunOutcome.PASS else RunOutcome.FAIL, run.outcome, id)
		}
	}

	@Test
	fun theThreeRedRunsShareOneSignature() {
		val facts = reds.map { run(it).facts() }
		val signature = facts[0].getValue("signature")
		assertTrue(facts.all { it["signature"] == signature })
		assertTrue(facts.all { it["exit"] == "1" })
		assertTrue(facts.all { it["step"] == "Run Integration Suite with Coverage" })
		assertTrue(facts.all { "network connection lost" in it.getValue("transport") })
		assertTrue(facts.all { "broken pipe" in it.getValue("transport") })
	}

	@Test
	fun aDifferentFailureGetsADifferentSignature() {
		val log = RunFixtures.text("logs/${reds[0]}.log")
		val changed = log.replace("/rows answered 500", "/rows answered 502")
		val other = run(reds[0]).copy(symptoms = SymptomExtractor.extract(changed))
		assertNotEquals(run(reds[0]).facts()["signature"], other.facts()["signature"])
		val fewer = log.replace("##[error]Error: /rows answered 500", "##[warning]kept")
		val subset = run(reds[0]).copy(symptoms = SymptomExtractor.extract(fewer))
		assertNotEquals(run(reds[0]).facts()["signature"], subset.facts()["signature"])
	}

	@Test
	fun logAndJsonAgreeOnTheFailingStep() {
		for (id in reds) {
			val run = run(id)
			val failed = run.steps.single { it.conclusion == StepConclusion.FAILURE }
			assertEquals("Docker E2E", failed.job)
			val fromLog = run.symptoms.single { it.kind == DURATION }.value.toLong()
			assertTrue(kotlin.math.abs(fromLog - failed.durationMs!!) <= 1000, id)
			assertEquals(failed.durationMs.toString(), run.facts()["duration-ms"])
		}
	}

	@Test
	fun truncatedLogsNeverThrow() {
		for (id in listOf(green, reds[0])) {
			val full = RunFixtures.text("logs/$id.log")
			var state = 7L
			repeat(12) {
				state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
				val n = ((state ushr 33) % (full.length + 1)).toInt()
				SymptomExtractor.extract(full.take(n))
			}
			for (n in listOf(0, 1, 28, 29, 30, full.length - 1, full.length)) {
				SymptomExtractor.extract(full.take(n))
			}
		}
	}
}
