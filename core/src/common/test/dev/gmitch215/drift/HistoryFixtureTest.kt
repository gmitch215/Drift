package dev.gmitch215.drift

import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.history.Evidence
import dev.gmitch215.drift.history.FailingStep
import dev.gmitch215.drift.history.GreenToRed
import dev.gmitch215.drift.history.History
import dev.gmitch215.drift.history.HistoryKey
import dev.gmitch215.drift.history.HistoryState
import dev.gmitch215.drift.history.RunOrder
import dev.gmitch215.drift.history.TransitionKind
import dev.gmitch215.drift.history.VacuousGreen
import dev.gmitch215.drift.history.Validity
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
import dev.gmitch215.drift.symptom.SymptomExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryFixtureTest {
	private val green = "36702683742"
	private val nightlies = listOf("35585969640", "36232485230", "36311117223", "36412389380")
	private val reds = listOf("36995781138", "37114464625", "37195797966")
	private val greens = nightlies + green
	private val job = "Docker E2E"
	private val step = "Run Integration Suite with Coverage"

	private fun steps(id: String): List<Step> =
		CanonicalJson.parse(RunFixtures.text("runs/$id.json")).obj().require("jobs").array()
			.flatMap { j ->
				val name = j.obj().require("name").string()
				j.obj().require("steps").array().map { s ->
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

	private fun run(id: String, withSteps: Boolean = true): Run {
		val summary = CanonicalJson.parse(RunFixtures.text("runs/$id.json")).obj()
		return Run(
			id = id,
			outcome = RunOutcome.ofConclusion(summary.require("conclusion").string()),
			steps = if (withSteps) steps(id) else emptyList(),
			symptoms = SymptomExtractor.extract(RunFixtures.text("logs/$id.log")),
			capsule = Capsule.parse(RunFixtures.text("capsules/$id.json")),
		)
	}

	private val history = History.of(
		HistoryKey("e2e", job),
		(reds + greens).shuffled(Rng(5)).map { run(it) },
		RunOrder.RUN_ID,
	)

	private val failing = FailingStep.of(run(reds[0]))!!

	@Test
	fun historyIsARegressionAtTheSep30Run() {
		assertEquals(greens + reds, history.runs.map { it.id })
		assertEquals(HistoryState.REGRESSION, history.state)
		assertEquals(1, history.flips)
		assertEquals(green, history.transition?.green?.id)
		assertEquals(reds[0], history.transition?.red?.id)
		assertEquals(green, history.lastGreen?.id)
	}

	@Test
	fun failingStepComesFromTheRunJson() {
		for (red in reds) assertEquals(FailingStep(job, step), FailingStep.of(run(red)), red)
	}

	@Test
	fun everyGreenExecutedTheFailingStep() {
		for (id in greens) {
			assertEquals(Validity.EXECUTED, VacuousGreen.assess(run(id), failing), id)
		}
	}

	@Test
	fun theLogsAloneCannotNameTheStep() {
		for (id in greens) {
			assertEquals(Validity.UNKNOWN, VacuousGreen.assess(run(id, false), failing), id)
		}
		val evidence = Evidence.of((greens + reds).map { run(it, it in reds) }, failing)
		assertEquals(greens.size, evidence.excluded.size)
		assertEquals(reds.size, evidence.input.size)
	}

	@Test
	fun theStepRanInEveryGreenButTheFailingTestsAreNotAllInThem() {
		fun line(id: String, file: String): String? =
			plain(RunFixtures.text("logs/$id.log")).lineSequence()
				.firstOrNull { it.startsWith(job) && it.contains("  e2e  tests/e2e/$file (") }
				?.substringAfter("tests/e2e/")
		for (id in greens) {
			val text = plain(RunFixtures.text("logs/$id.log"))
			assertTrue(line(id, "to-worker.spec.ts")!!.contains("(8 tests)"), id)
			assertTrue(line(id, "modify.spec.ts")!!.contains("(3 tests)"), id)
			assertEquals(null, line(id, "preview.spec.ts"), id)
			assertTrue(!text.contains("pinned production modules"), id)
		}
		for (id in reds) {
			assertTrue(line(id, "to-worker.spec.ts")!!.contains("(8 tests | 2 failed)"), id)
			assertTrue(line(id, "modify.spec.ts")!!.contains("(8 tests |"), id)
			assertTrue(line(id, "preview.spec.ts")!!.contains("(3 tests | 3 skipped)"), id)
		}
	}

	private fun plain(text: String): String {
		val out = StringBuilder()
		var i = 0
		while (i < text.length) {
			if (text.startsWith("^[[", i)) {
				i += 3
				while (i < text.length && (text[i] in '0'..'9' || text[i] == ';')) i++
				if (i < text.length && text[i] == 'm') i++
			} else {
				out.append(text[i++])
			}
		}
		return out.toString()
	}

	@Test
	fun theTransitionIsABundle() {
		val result = GreenToRed.of(history.transition!!, job)
		assertEquals(TransitionKind.BUNDLE, result.kind)
		assertEquals(
			listOf(
				"ci.provisioner.build-date",
				"ci.provisioner.version",
				"ci.runner.image.version",
				"deps.@napi-rs/keyring.version",
				"deps.prettier-plugin-sh.version",
				"deps.wrangler.version",
				"tool.node.version",
				"tool.npm.version",
				"tool.yarn.version",
			),
			result.changes.map { it.path }.sorted(),
		)
		assertEquals(
			listOf("Dump Host State on Failure", "Post Set up Node", "Set up Node"),
			result.steps.map { it.name },
		)
		assertTrue(result.steps.all { it.kind == DiffKind.ADDED && it.job == job })
		assertEquals(21, result.background)
		assertEquals(
			RESULT_GOLDEN,
			Sha256.hex(CanonicalJson.encode(result.toJson())),
		)
	}

	@Test
	fun evidenceOverTheRealHistory() {
		val evidence = Evidence.of(history.runs, failing)
		assertTrue(evidence.excluded.isEmpty())
		assertEquals(29, evidence.universe)
		assertEquals(List(5) { 25 } + List(3) { 29 }, evidence.runs.map { it.attributes })
		assertEquals(
			List(5) { 862_068L } + List(3) { 1_000_000L },
			evidence.runs.map { it.coverage },
		)
	}

	private companion object {
		const val RESULT_GOLDEN = "e7e7a16c9016ab28e606eb03e2231e96c2e0383cad627cb4bd80f6a5dc583f62"
	}
}
