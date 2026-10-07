package dev.gmitch215.drift

import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.history.Evidence
import dev.gmitch215.drift.history.ExclusionReason
import dev.gmitch215.drift.history.FailingStep
import dev.gmitch215.drift.history.GreenToRed
import dev.gmitch215.drift.history.History
import dev.gmitch215.drift.history.HistoryEntry
import dev.gmitch215.drift.history.HistoryKey
import dev.gmitch215.drift.history.HistoryState
import dev.gmitch215.drift.history.RunOrder
import dev.gmitch215.drift.history.StepChange
import dev.gmitch215.drift.history.TransitionKind
import dev.gmitch215.drift.history.VacuousGreen
import dev.gmitch215.drift.history.Validity
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.spectrum.Ochiai
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
	private val key = HistoryKey("e2e", "Docker E2E")
	private val failing = FailingStep("Docker E2E", "Run Suite")

	private fun capsule(label: String, vararg attrs: Pair<String, String>) = Capsule(
		label,
		attrs.map { Attribute(it.first, it.second, "test") },
	)

	private fun steps(vararg conclusions: Pair<String, StepConclusion>) =
		conclusions.map { Step("Docker E2E", it.first, it.second, null) }

	private val ran =
		steps("Install" to StepConclusion.SUCCESS, "Run Suite" to StepConclusion.SUCCESS)

	private fun run(
		id: String,
		outcome: RunOutcome,
		steps: List<Step> = ran,
		capsule: Capsule? = null,
	) = Run(id, outcome, steps, capsule = capsule)

	private fun history(vararg outcomes: RunOutcome) = History(
		key,
		outcomes.mapIndexed { i, o -> run("${i + 1}", o) },
	)

	private val p = RunOutcome.PASS
	private val f = RunOutcome.FAIL

	// #region history

	@Test
	fun statesAreExplicit() {
		assertEquals(HistoryState.EMPTY, history().state)
		assertEquals(HistoryState.SINGLE, history(p).state)
		assertEquals(HistoryState.SINGLE, history(f).state)
		assertEquals(HistoryState.ALL_GREEN, history(p, p, p).state)
		assertEquals(HistoryState.NEVER_GREEN, history(f, f).state)
		assertEquals(HistoryState.REGRESSION, history(p, p, f, f).state)
		assertEquals(HistoryState.RECOVERED, history(f, p, p).state)
		assertEquals(HistoryState.FLAKY, history(p, f, p).state)
		assertEquals(HistoryState.FLAKY, history(f, p, f).state)
		assertEquals(HistoryState.FLAKY, history(p, f, p, f, p, f).state)
	}

	@Test
	fun emptySingleAndNeverGreenHaveNoTransition() {
		for (h in listOf(history(), history(p), history(f), history(f, f), history(f, p))) {
			assertNull(h.transition, h.toString())
		}
		assertNull(history(f, f).lastGreen)
		assertEquals("1", history(f, f).firstRed?.id)
		assertNull(history(p, p).firstRed)
	}

	@Test
	fun transitionIsTheLastGreenBeforeTheFirstRed() {
		val h = history(p, p, p, f, f)
		assertEquals("3", h.transition?.green?.id)
		assertEquals("4", h.transition?.red?.id)
		assertEquals("3", h.lastGreen?.id)
		assertEquals("4", h.firstRed?.id)
		assertEquals(1, h.flips)
	}

	@Test
	fun flakyKeepsTheFirstTransitionButCountsFlips() {
		val h = history(p, f, p, f)
		assertEquals(3, h.flips)
		assertEquals("1", h.transition?.green?.id)
		assertEquals("2", h.transition?.red?.id)
		assertEquals("3", h.lastGreen?.id)
	}

	@Test
	fun cancelledAndUnknownRunsDoNotBreakAdjacency() {
		val h = history(p, RunOutcome.CANCELLED, RunOutcome.UNKNOWN, f)
		assertEquals(HistoryState.REGRESSION, h.state)
		assertEquals("1", h.transition?.green?.id)
		assertEquals("4", h.transition?.red?.id)
		assertEquals(HistoryState.EMPTY, history(RunOutcome.CANCELLED).state)
	}

	@Test
	fun runIdOrderIsNumericAndStable() {
		val runs = listOf("100", "9", "0010", "100").map { run(it, p) }
		val ids = History.of(key, runs, RunOrder.RUN_ID).runs.map { it.id }
		assertEquals(listOf("9", "0010", "100", "100"), ids)
		assertEquals(runs, History.of(key, runs).runs)
		assertFailsWith<IllegalArgumentException> {
			History.of(key, listOf(run("abc", p)), RunOrder.RUN_ID)
		}
	}

	@Test
	fun groupSplitsByWorkflowAndJob() {
		val a = HistoryKey("e2e", "Docker E2E")
		val b = HistoryKey("e2e", null)
		val c = HistoryKey("build", "x")
		val groups = History.group(
			listOf(
				HistoryEntry(a, run("2", f)),
				HistoryEntry(b, run("1", p)),
				HistoryEntry(c, run("3", p)),
				HistoryEntry(a, run("1", p)),
			),
			RunOrder.RUN_ID,
		)
		assertEquals(listOf(c, b, a), groups.map { it.key })
		assertEquals(listOf("1", "2"), groups[2].runs.map { it.id })
	}

	// #endregion

	// #region green to red

	private val greenCapsule = capsule(
		"green",
		"ci.runner.image.version" to "1",
		"ci.provisioner.version" to "10",
		"deps.wrangler.version" to "4.1.0",
		"os.name" to "linux",
		"tool.git.version" to "2.50.0",
	)

	private val bundleRed = capsule(
		"red",
		"ci.runner.image.version" to "2",
		"ci.provisioner.version" to "11",
		"deps.wrangler.version" to "4.2.0",
		"os.name" to "linux",
		"tool.git.version" to "2.50.0",
		"tool.node.version" to "24.1.0",
	)

	@Test
	fun severalChangesAreABundle() {
		val withNode = ran + steps("Set up Node" to StepConclusion.SUCCESS)
		val transition = transitionOf(greenCapsule, bundleRed, withNode)
		val result = GreenToRed.of(transition, "Docker E2E")
		assertEquals(TransitionKind.BUNDLE, result.kind)
		assertEquals(
			listOf(
				"ci.provisioner.version",
				"ci.runner.image.version",
				"deps.wrangler.version",
				"tool.node.version",
			),
			result.changes.map { it.path }.sorted(),
		)
		assertEquals(listOf(StepChange("Docker E2E", "Set up Node", DiffKind.ADDED)), result.steps)
		assertEquals(2, result.background)
		assertTrue(result.ignored.isEmpty())
		val json = CanonicalJson.encode(result.toJson())
		assertEquals(BUNDLE_JSON, json)
		assertEquals(BUNDLE_GOLDEN, Sha256.hex(json))
	}

	private fun transitionOf(green: Capsule?, red: Capsule?, redSteps: List<Step> = ran) =
		History(key, listOf(run("1", p, capsule = green), run("2", f, redSteps, red))).transition!!

	@Test
	fun oneChangeIsSingleAndNoneIsUnexplained() {
		val one = capsule("red", "ci.runner.image.version" to "2")
		val before = capsule("green", "ci.runner.image.version" to "1")
		assertEquals(TransitionKind.SINGLE, GreenToRed.of(transitionOf(before, one)).kind)
		assertEquals(
			TransitionKind.UNEXPLAINED,
			GreenToRed.of(transitionOf(before, before)).kind,
		)
	}

	@Test
	fun volatileDifferenceIsIgnoredNotACause() {
		fun region(value: String) =
			Capsule("c", listOf(Attribute("ci.runner.region", value, "t", Stability.VOLATILE)))
		val before = region("eastus")
		val after = region("westus")
		val result = GreenToRed.of(transitionOf(before, after))
		assertEquals(TransitionKind.UNEXPLAINED, result.kind)
		assertEquals(listOf("ci.runner.region"), result.ignored.map { it.path })
		assertEquals(0, result.background)
	}

	@Test
	fun missingCapsuleIsNotAnEmptyDiff() {
		val added = ran + steps("New" to StepConclusion.SUCCESS)
		val result = GreenToRed.of(transitionOf(null, capsule("r", "a.b" to "1"), added))
		assertEquals(TransitionKind.NO_CAPSULE, result.kind)
		assertTrue(result.changes.isEmpty())
		assertEquals(listOf("New"), result.steps.map { it.name })
	}

	@Test
	fun stepChangesCountTowardTheBundleAndCanBeScopedToAJob() {
		val before = capsule("g", "ci.runner.image.version" to "1")
		val extra = ran + Step("Other", "Added", StepConclusion.SUCCESS, null) +
			Step("Docker E2E", "Gone", StepConclusion.SUCCESS, null)
		val bundle = GreenToRed.of(transitionOf(before, before, extra))
		assertEquals(TransitionKind.BUNDLE, bundle.kind)
		val scoped = GreenToRed.of(transitionOf(before, before, extra), "Other")
		assertEquals(TransitionKind.SINGLE, scoped.kind)
		val removed = GreenToRed.of(
			History(
				key,
				listOf(run("1", p, extra, before), run("2", f, ran, before)),
			).transition!!,
		)
		assertEquals(
			listOf(
				StepChange("Docker E2E", "Gone", DiffKind.REMOVED),
				StepChange("Other", "Added", DiffKind.REMOVED),
			),
			removed.steps,
		)
	}

	// #endregion

	// #region vacuous green

	@Test
	fun executedStepIsEvidence() {
		assertEquals(Validity.EXECUTED, VacuousGreen.assess(run("1", p), failing))
	}

	@Test
	fun missingStepIsVacuous() {
		val r = run("1", p, steps("Install" to StepConclusion.SUCCESS))
		assertEquals(Validity.VACUOUS, VacuousGreen.assess(r, failing))
	}

	@Test
	fun skippedStepIsVacuous() {
		val r = run("1", p, steps("Run Suite" to StepConclusion.SKIPPED))
		assertEquals(Validity.VACUOUS, VacuousGreen.assess(r, failing))
	}

	@Test
	fun noStepEvidenceIsUnknownNeverGreen() {
		assertEquals(Validity.UNKNOWN, VacuousGreen.assess(run("1", p, emptyList()), failing))
		val unknown = run("1", p, steps("Run Suite" to StepConclusion.UNKNOWN))
		assertEquals(Validity.UNKNOWN, VacuousGreen.assess(unknown, failing))
		val cancelled = run("1", p, steps("Run Suite" to StepConclusion.CANCELLED))
		assertEquals(Validity.UNKNOWN, VacuousGreen.assess(cancelled, failing))
	}

	@Test
	fun theStepMustBeInTheFailingJob() {
		val other = listOf(Step("Real Worker", "Run Suite", StepConclusion.SUCCESS, null))
		assertEquals(Validity.VACUOUS, VacuousGreen.assess(run("1", p, other), failing))
		assertEquals(
			Validity.EXECUTED,
			VacuousGreen.assess(run("1", p, other), FailingStep(null, "Run Suite")),
		)
	}

	@Test
	fun aSkippedCopyDoesNotHideAnExecutedOne() {
		val r = run(
			"1",
			p,
			steps("Run Suite" to StepConclusion.SKIPPED, "Run Suite" to StepConclusion.SUCCESS),
		)
		assertEquals(Validity.EXECUTED, VacuousGreen.assess(r, failing))
	}

	@Test
	fun redAndCancelledRunsAreNotPasses() {
		assertEquals(Validity.NOT_PASS, VacuousGreen.assess(run("1", f), failing))
		val cancelled = run("1", RunOutcome.CANCELLED)
		assertEquals(Validity.NOT_PASS, VacuousGreen.assess(cancelled, failing))
	}

	@Test
	fun failingStepComesFromTheRed() {
		val red = run(
			"1",
			f,
			steps(
				"Install" to StepConclusion.SUCCESS,
			"Run Suite" to StepConclusion.FAILURE,
			),
		)
		assertEquals(failing, FailingStep.of(red))
		assertNull(FailingStep.of(run("2", p)))
	}

	// #endregion

	// #region evidence

	private fun withAttrs(
		id: String,
		outcome: RunOutcome,
		steps: List<Step>,
		vararg attrs: Pair<String, String>,
	) = run(id, outcome, steps, capsule(id, *attrs))

	@Test
	fun evidenceExcludesVacuousUnknownAndEmptyRuns() {
		val runs = listOf(
			withAttrs("1", p, ran, "a" to "1", "b" to "1"),
			withAttrs("2", p, steps("Install" to StepConclusion.SUCCESS), "a" to "1"),
			withAttrs("3", p, emptyList(), "a" to "1"),
			withAttrs("4", RunOutcome.CANCELLED, ran, "a" to "1"),
			run("5", p, ran, null),
			withAttrs("6", f, ran, "a" to "2", "b" to "1", "c" to "1"),
		)
		val evidence = Evidence.of(runs, failing)
		assertEquals(
			listOf(
				"2" to ExclusionReason.VACUOUS,
				"3" to ExclusionReason.NO_STEP_EVIDENCE,
				"4" to ExclusionReason.NO_RESULT,
				"5" to ExclusionReason.NO_ATTRIBUTES,
			),
			evidence.excluded.map { it.runId to it.reason },
		)
		assertEquals(listOf(false, true), evidence.input.map { it.failing })
		assertEquals(3, evidence.universe)
	}

	@Test
	fun evidenceExposesSparsityInMicroUnits() {
		val runs = listOf(
			withAttrs("1", p, ran, "a" to "1", "b" to "1", "c" to "1"),
			withAttrs("2", f, ran, "a" to "2"),
		)
		val evidence = Evidence.of(runs, failing)
		assertEquals(listOf(3, 1), evidence.runs.map { it.attributes })
		assertEquals(listOf(1_000_000L, 333_333L), evidence.runs.map { it.coverage })
	}

	@Test
	fun evidenceKeepsStaticAttributesAndNormalizesOs() {
		val volatile = Attribute("ci.runner.region", "eastus", "t", Stability.VOLATILE)
		val c = Capsule("x", listOf(Attribute("os.name", "Darwin", "t"), volatile))
		val evidence = Evidence.of(listOf(Run("1", f, ran, capsule = c)), failing)
		assertEquals(
			setOf("os.name=macos"),
			evidence.input.single().elements.map {
			"${it.path}=${it.value}"
		}.toSet(),
		)
		assertEquals(1, evidence.universe)
	}

	@Test
	fun addingVacuousGreensNeverChangesTheRankingInput() {
		val rng = Rng(2026)
		repeat(100) {
			val base = List(rng.next(6) + 2) { i ->
				val outcome = if (rng.next(2) == 0) p else f
				withAttrs("r$i", outcome, ran, *randomAttrs(rng))
			}.let { it + withAttrs("redA", f, ran, *randomAttrs(rng)) }
				.let { it + withAttrs("greenA", p, ran, *randomAttrs(rng)) }
			val noise = List(rng.next(5) + 1) { i ->
				val steps = when (rng.next(3)) {
					0 -> steps("Install" to StepConclusion.SUCCESS)
					1 -> steps("Run Suite" to StepConclusion.SKIPPED)
					else -> emptyList()
				}
				withAttrs("v$i", p, steps, *randomAttrs(rng))
			}
			val mixed = base.toMutableList()
			for (n in noise) mixed.add(rng.next(mixed.size + 1), n)
			val before = Evidence.of(base, failing)
			val after = Evidence.of(mixed, failing)
			assertEquals(before.input, after.input)
			assertEquals(before.runs, after.runs)
			assertEquals(before.universe, after.universe)
			assertEquals(Ochiai.rank(before.input), Ochiai.rank(after.input))
			assertEquals(noise.size, after.excluded.size - before.excluded.size)
		}
	}

	@Test
	fun addingAnExecutedGreenDoesChangeTheInput() {
		val base = listOf(withAttrs("1", f, ran, "a" to "1"))
		val more = base + withAttrs("2", p, ran, "a" to "2")
		assertNotEquals(Evidence.of(base, failing).input, Evidence.of(more, failing).input)
	}

	private fun randomAttrs(rng: Rng): Array<Pair<String, String>> =
		(0 until 5).filter { rng.next(2) == 0 }.map { "k$it" to "v${rng.next(3)}" }
			.ifEmpty { listOf("k0" to "v0") }.toTypedArray()

	// #endregion

	private companion object {
		const val BUNDLE_GOLDEN = "15fe485f3d4f813809e80e855a2a1a9a58c633947ed697bdfbd8d67cee94065c"

		val BUNDLE_JSON = listOf(
			"""{"background":2,"changes":[""",
			"""{"after":"24.1.0","before":null,"dimension":"runtime","kind":"added",""",
			""""order":null,""",
			""""path":"tool.node.version","stability":"static"},""",
			"""{"after":"11","before":"10","dimension":"build","kind":"changed","order":"less",""",
			""""path":"ci.provisioner.version","stability":"static"},""",
			"""{"after":"2","before":"1","dimension":"build","kind":"changed","order":"less",""",
			""""path":"ci.runner.image.version","stability":"static"},""",
			"""{"after":"4.2.0","before":"4.1.0","dimension":"build","kind":"changed",""",
			""""order":"less",""",
			""""path":"deps.wrangler.version","stability":"static"}],""",
			""""green":"1","ignored":[],"kind":"bundle","probes":[],"red":"2",""",
			""""steps":[{"job":"Docker E2E","kind":"added","name":"Set up Node"}]}""",
		).joinToString("")
	}
}
