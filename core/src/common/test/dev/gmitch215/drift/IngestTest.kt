package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.AlreadyIngested
import dev.gmitch215.drift.lab.ArmMismatch
import dev.gmitch215.drift.lab.BadCounts
import dev.gmitch215.drift.lab.CaseNotIntact
import dev.gmitch215.drift.lab.Counts
import dev.gmitch215.drift.lab.DecisionRule
import dev.gmitch215.drift.lab.Ingest
import dev.gmitch215.drift.lab.IngestOutcome
import dev.gmitch215.drift.lab.MalformedResult
import dev.gmitch215.drift.lab.Outcome
import dev.gmitch215.drift.lab.RuleMismatch
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.lab.UnknownExperiment
import dev.gmitch215.drift.lab.UnsupportedResultSchema
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.rank.Ranker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IngestTest {
	private val green = Capsule.parse(LabFixtures.text("node.json"))
	private val red = Capsule(
		"lab-node-red",
		green.attributes.filter { it.path != "env.TZ" && it.path != "env.LANG" } +
			listOf(
				Attribute("env.TZ", "America/New_York", "test"),
				Attribute("env.LANG", "en_US.UTF-8", "test"),
			),
	)
	private val ranking = Ranker.rank(green, red)
	private val plan: Plan = Planner.from(ranking, Frame.environments("g", "r"), Options(1, 3, 3))
	private val case: CaseFile = CaseBuilder.build("ingest", green, red, ranking, plan)
	private val rule = DecisionRule(
		requireNotNull(plan.trials.perArm),
		requireNotNull(plan.trials.threshold),
		plan.trials.alpha,
		plan.trials.rate,
	)
	private val perArm = rule.perArm
	private val next = requireNotNull(plan.next).experiment

	private fun result(
		id: String = next.id,
		hash: String = rule.sha256(),
		control: Counts = Counts(0, perArm),
		treatment: Counts = Counts(perArm, perArm),
	) = Ingest.template(id, hash, control, treatment)

	private fun done(files: Map<String, String>, from: CaseFile = case): IngestOutcome.Done =
		assertIs<IngestOutcome.Done>(Ingest.apply(from, files))

	private fun refused(files: Map<String, String>, from: CaseFile = case) =
		assertIs<IngestOutcome.Refused>(Ingest.apply(from, files)).problems

	@Test
	fun theSkeletonHoldsTheRuleHashAndNullFailuresAndIsRefusedUntilFilled() {
		val files = Ingest.skeleton(case)
		assertTrue("${next.id}.json" in files.keys)
		val text = files.getValue("${next.id}.json")
		assertTrue("\"ruleSha256\":\"${rule.sha256()}\"" in text, text)
		assertTrue("\"failures\":null" in text && "\"trials\":$perArm" in text, text)
		assertEquals(rule.sha256(), Ingest.rule(case.json("experiments/plan.json"))?.sha256())
		val problem = refused(mapOf("a.json" to text)).single()
		assertEquals("a.json: control failures are not filled in", problem.message())
		val filled = text.replace("\"failures\":null", "\"failures\":0")
		assertEquals(1, done(mapOf("a.json" to filled)).accepted.size)
	}

	@Test
	fun theSkeletonLeavesOutExperimentsThatAlreadyHaveAResult() {
		val after = done(mapOf("a.json" to result())).case
		assertEquals(setOf(next.id), Ingest.recorded(after))
		assertTrue("${next.id}.json" !in Ingest.skeleton(after).keys)
	}

	@Test
	fun aResultThatSupportsTheExperimentNarrowsTheCaseAndKeepsItIntact() {
		val out = done(mapOf("a.json" to result()))
		val a = out.accepted.single()
		assertEquals(Outcome.SUPPORTED, a.outcome)
		assertEquals(Counts(perArm, perArm), a.treatment)
		assertEquals(emptyList(), out.case.check())
		assertEquals(emptyList(), SolveCertificate.check(out.case))
		val cert = SolveCertificate.certificate(out.case)!!
		val verdict = cert.fields.getValue("verdict") as JsonObject
		assertEquals("narrowed", (verdict.fields.getValue("kind") as JsonString).value)
		val statement = (verdict.fields.getValue("statement") as JsonString).value
		assertTrue("not confirmed" in statement, statement)
		val posteriors = cert.fields.getValue("posteriors") as JsonArray
		assertEquals(2, posteriors.items.size)
		assertTrue("results/0001.json" in out.case.files)
		assertTrue(out.case.text("results/0001.json")!!.contains("\"source\":\"ingested\""))
	}

	@Test
	fun aResultThatRefutesRulesOutItsCandidatesAndMovesTheBelief() {
		val out = done(mapOf("a.json" to result(treatment = Counts(0, perArm))))
		assertEquals(Outcome.REFUTED, out.accepted.single().outcome)
		val cert = SolveCertificate.certificate(out.case)!!
		val verdict = CanonicalJson.encode(cert.fields.getValue("verdict"))
		assertTrue("ruled out" in verdict, verdict)
		val belief = CanonicalJson.encode(cert.fields.getValue("posteriors"))
		assertTrue("not-reproduced".isNotEmpty() && belief.contains("refuted"))
	}

	@Test
	fun theSameResultsGiveTheSameBytesAndAPinnedCertificate() {
		val a = done(mapOf("a.json" to result())).case
		val b = done(mapOf("a.json" to result())).case
		assertEquals(a.files, b.files)
		assertEquals(
			GOLDEN,
			Sha256.hex(a.text(SolveCertificate.CERTIFICATE)!!),
			"ingested certificate bytes changed",
		)
	}

	@Test
	fun everyShapeProblemIsATypedRefusalAndNothingIsApplied() {
		val unknown = refused(mapOf("a.json" to result(id = "e999"))).single()
		assertIs<UnknownExperiment>(unknown)
		val wrongRule = refused(mapOf("a.json" to result(hash = "0".repeat(64)))).single()
		assertIs<RuleMismatch>(wrongRule)
		assertEquals(rule.sha256(), wrongRule.expected)
		val noRule = result().replace("ruleSha256", "other")
		val named = assertIs<RuleMismatch>(refused(mapOf("a.json" to noRule)).single())
		assertEquals(null, named.found)
		val swapped = result().replace("\"control\"", "\"x\"")
		assertIs<ArmMismatch>(refused(mapOf("a.json" to swapped)).single())
		assertIs<BadCounts>(
			refused(mapOf("a.json" to result(treatment = Counts(1, perArm + 1)))).single(),
		)
		assertIs<UnsupportedResultSchema>(
			refused(mapOf("a.json" to result().replace("\"schema\":1", "\"schema\":2"))).single(),
		)
		assertIs<MalformedResult>(refused(mapOf("a.json" to "{\"schema\":1")).single())
		assertIs<MalformedResult>(refused(mapOf("a.json" to "[]")).single())
		val missingArm = result().replace("\"failures\":0,", "")
		assertIs<MalformedResult>(refused(mapOf("a.json" to missingArm)).single())
		val mixed = refused(mapOf("a.json" to result(), "b.json" to result(id = "e999")))
		assertEquals(1, mixed.size)
		assertEquals("b.json", mixed.single().file)
	}

	@Test
	fun failuresAboveTheTrialsAndATrialCountOffThePreregisteredOneAreRefused() {
		val high = result().replace(
			"\"failures\":$perArm,\"id\":\"treatment\"",
			"\"failures\":${perArm + 3},\"id\":\"treatment\"",
		)
		assertIs<BadCounts>(refused(mapOf("a.json" to high)).single())
		val fewer = Ingest.template(
			next.id,
			rule.sha256(),
			Counts(0, perArm - 1),
			Counts(1, perArm - 1),
		)
		val problem = assertIs<BadCounts>(refused(mapOf("a.json" to fewer)).single())
		assertTrue("preregistered count is $perArm" in problem.message(), problem.message())
	}

	@Test
	fun aSecondResultForTheSameExperimentIsRefusedInOneBatchAndAcrossBatches() {
		val first = done(mapOf("a.json" to result()))
		val again = refused(mapOf("b.json" to result()), first.case).single()
		assertIs<AlreadyIngested>(again)
		val batch = refused(mapOf("a.json" to result(), "b.json" to result()))
		assertTrue(batch.single() is AlreadyIngested)
	}

	@Test
	fun aTamperedCaseIsRefusedBeforeAnythingIsRead() {
		val edited = case.files.toMutableMap()
		edited["experiments/plan.json"] = edited.getValue("experiments/plan.json") + " "
		val problem = refused(mapOf("a.json" to result()), CaseFile(edited)).single()
		assertIs<CaseNotIntact>(problem)
		val first = done(mapOf("a.json" to result())).case
		val forged = first.files.toMutableMap()
		forged["results/0001.json"] =
			forged.getValue("results/0001.json").replace("\"supported\"", "\"refuted\"")
		forged[CaseFile.MANIFEST] = CaseFile.manifest(forged)
		val chain = refused(mapOf("b.json" to result(id = "e1")), CaseFile(forged)).single()
		assertTrue("results chain broken" in chain.message(), chain.message())
	}

	@Test
	fun aConfirmedCaseKeepsItsVerdictWhenAnotherResultIsIngested() {
		val cause = { arm: dev.gmitch215.drift.lab.RunConfig ->
			arm.capsule.attributes.any { it.path == "env.TZ" && it.value == "America/New_York" }
		}
		val files = dev.gmitch215.drift.case.MemoryCaseFiles()
		val executor = dev.gmitch215.drift.lab.FakeExecutor(
			behave = { arm, _ ->
				dev.gmitch215.drift.lab.RawTrial(
					if (cause(arm)) 1 else 0,
					if (cause(arm)) "wrong day" else "ok",
					1,
				)
			},
		)
		val solved = dev.gmitch215.drift.lab.Solver(
			executor,
			files,
			"case",
			dev.gmitch215.drift.lab.SolveOptions(
				"sh t.sh",
				dev.gmitch215.drift.lab.FailWhen.parse("wrong day"),
				dev.gmitch215.drift.lab.Budget(300, 30),
				pilot = 6,
			),
		).solve(green, red)
		assertEquals(dev.gmitch215.drift.lab.VerdictKind.CONFIRMED, solved.verdict.kind)
		val initial = CanonicalJson.parse(solved.case.text("experiments/plan.json")!!) as JsonObject
		val trials = initial.fields.getValue("trials") as JsonObject
		val r = DecisionRule(
			(trials.fields.getValue("perArm") as dev.gmitch215.drift.json.JsonInt).value.toInt(),
			(trials.fields.getValue("threshold") as dev.gmitch215.drift.json.JsonInt).value.toInt(),
			(trials.fields.getValue("alpha") as dev.gmitch215.drift.json.JsonInt).value,
			(trials.fields.getValue("rate") as dev.gmitch215.drift.json.JsonInt).value,
		)
		val result = Ingest.template("e1", r.sha256(), Counts(0, r.perArm), Counts(0, r.perArm))
		val out = done(mapOf("a.json" to result), solved.case)
		val certificate = SolveCertificate.certificate(out.case)!!
		val verdict = certificate.fields.getValue("verdict") as JsonObject
		assertEquals("confirmed", (verdict.fields.getValue("kind") as JsonString).value)
		assertEquals(emptyList(), out.case.check())
		assertEquals(emptyList(), SolveCertificate.check(out.case))
		assertEquals(solved.runs.size + 1, out.case.files.keys.count { it.startsWith("results/0") })
	}

	companion object {
		const val GOLDEN = "ab0b3c3bcc801e51e577e0573f8851ecc3d1522366ca6281e45801153fa308b0"
	}
}
