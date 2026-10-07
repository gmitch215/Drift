package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RunTest {
	private val symptoms = listOf(
		Symptom(SymptomKind.STEP, "bun run test", 12, "E2E"),
		Symptom(SymptomKind.DURATION, "198273", 12, "E2E"),
		Symptom(SymptomKind.TRANSPORT, "connection reset", 40, "E2E", 2),
		Symptom(SymptomKind.TRANSPORT, "broken pipe", 41),
		Symptom(SymptomKind.SIGNATURE, "Error: boom", 50, "E2E"),
		Symptom(SymptomKind.SIGNATURE, "Error: bang", 51, "E2E"),
		Symptom(SymptomKind.EXIT, "2", 60, "E2E"),
		Symptom(SymptomKind.EXIT, "1", 61, "E2E"),
		Symptom(SymptomKind.SIGNAL, "SIGTERM", 62),
	)

	private val run = Run(
		id = "1",
		outcome = RunOutcome.FAIL,
		steps = listOf(
			Step("E2E", "Set up job", StepConclusion.SUCCESS, 1000),
			Step("E2E", "Test", StepConclusion.FAILURE, 199_000),
			Step(null, "Post", StepConclusion.SKIPPED, null),
		),
		symptoms = symptoms,
		capsule = Capsule("partial", listOf(Attribute("ci.runner.image", "ubuntu", "log"))),
	)

	@Test
	fun roundTripKeepsEveryField() {
		assertEquals(run, Run.parse(run.canonical()))
		assertEquals(run.canonical(), Run.parse(run.canonical()).canonical())
		assertEquals(run.hash(), Run.parse(run.canonical()).hash())
		val bare = Run("2", RunOutcome.UNKNOWN)
		assertEquals(bare, Run.parse(bare.canonical()))
	}

	@Test
	fun stepOrderIsKept() {
		val reversed = run.copy(steps = run.steps.reversed())
		assertNotEquals(run.hash(), reversed.hash())
		assertEquals(reversed, Run.parse(reversed.canonical()))
	}

	@Test
	fun parseRejectsWhatItDoesNotKnow() {
		fun edit(key: String, value: String): String {
			val fields = (CanonicalJson.parse(run.canonical()) as JsonObject).fields.toMutableMap()
			fields[key] = JsonString(value)
			return CanonicalJson.encode(JsonObject(fields))
		}
		assertFailsWith<JsonException> { Run.parse(edit("outcome", "maybe")) }
		val bare = Run("2", RunOutcome.UNKNOWN).canonical()
		assertFailsWith<JsonException> { Run.parse(bare.replace("\"schema\":1", "\"schema\":2")) }
		assertFailsWith<JsonException> {
			val known = "\"conclusion\":\"failure\""
			Run.parse(run.canonical().replace(known, "\"conclusion\":\"meh\""))
		}
		assertFailsWith<JsonException> {
			Run.parse(run.canonical().replace("\"kind\":\"exit\"", "\"kind\":\"vibes\""))
		}
	}

	@Test
	fun conclusionsMapToOutcomes() {
		assertEquals(RunOutcome.PASS, RunOutcome.ofConclusion("success"))
		assertEquals(RunOutcome.FAIL, RunOutcome.ofConclusion("failure"))
		assertEquals(RunOutcome.FAIL, RunOutcome.ofConclusion("timed_out"))
		assertEquals(RunOutcome.CANCELLED, RunOutcome.ofConclusion("cancelled"))
		assertEquals(RunOutcome.UNKNOWN, RunOutcome.ofConclusion("skipped"))
		assertEquals(RunOutcome.UNKNOWN, RunOutcome.ofConclusion(null))
		assertEquals(StepConclusion.SUCCESS, StepConclusion.ofConclusion("success"))
		assertEquals(StepConclusion.FAILURE, StepConclusion.ofConclusion("failure"))
		assertEquals(StepConclusion.FAILURE, StepConclusion.ofConclusion("timed_out"))
		assertEquals(StepConclusion.SKIPPED, StepConclusion.ofConclusion("skipped"))
		assertEquals(StepConclusion.CANCELLED, StepConclusion.ofConclusion("cancelled"))
		assertEquals(StepConclusion.UNKNOWN, StepConclusion.ofConclusion("neutral"))
	}

	@Test
	fun factsFromTheStepsWhenAStepFailed() {
		val facts = run.facts()
		assertEquals(
			listOf("signature", "exit", "signal", "step", "duration-ms", "transport"),
			facts.keys.toList(),
		)
		assertEquals("1", facts["exit"])
		assertEquals("SIGTERM", facts["signal"])
		assertEquals("Test", facts["step"])
		assertEquals("199000", facts["duration-ms"])
		assertEquals("broken pipe\nconnection reset", facts["transport"])
		assertEquals(16, facts.getValue("signature").length)
	}

	@Test
	fun factsFromTheLogWhenNoStepFailed() {
		val facts = run.copy(steps = emptyList()).facts()
		assertEquals("bun run test", facts["step"])
		assertEquals("198273", facts["duration-ms"])
	}

	@Test
	fun signatureFactIgnoresOrderAndCounts() {
		val shuffled = run.copy(
			symptoms = symptoms.reversed().map { it.copy(count = 9, line = it.line + 100) },
		)
		assertEquals(run.facts()["signature"], shuffled.facts()["signature"])
		val other = run.copy(symptoms = symptoms.filter { it.value != "Error: bang" })
		assertNotEquals(run.facts()["signature"], other.facts()["signature"])
	}

	@Test
	fun noEvidenceMeansNoFacts() {
		assertEquals(emptyMap(), Run("3", RunOutcome.PASS).facts())
		assertEquals(emptyMap(), Run("4", RunOutcome.PASS, steps = run.steps.take(1)).facts())
	}

	@Test
	fun depsPrefixIsBuild() {
		assertEquals(Dimension.BUILD, Dimension.of("deps.wrangler.version"))
		assertEquals(Dimension.BUILD, Dimension.of("deps.@napi-rs/keyring.version"))
		assertEquals(Dimension.RUNTIME, Dimension.of("tool.node.version"))
		assertEquals(null, Dimension.of("dependencies.x"))
	}
}
