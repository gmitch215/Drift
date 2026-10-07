package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateTest {
	private val defaults = linkedMapOf(
		"id" to "\"t\"",
		"dimension" to "\"env\"",
		"runtime" to "\"sh\"",
		"program" to "\"echo ok\"",
		"signature" to "\"boom\"",
		"base" to "{\"image\":\"debian:12-slim\"}",
		"green" to "{\"env\":{\"A\":\"1\"}}",
		"red" to "{\"env\":{\"A\":\"2\"}}",
		"cause" to "{\"dimension\":\"env\",\"path\":\"env.A\",\"direction\":\"lower\"}",
	)

	private fun text(vararg changes: Pair<String, String?>): String {
		val fields = LinkedHashMap(defaults)
		for ((k, v) in changes) if (v == null) fields.remove(k) else fields[k] = v
		return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
	}

	private fun scenario(vararg changes: Pair<String, String?>): Scenario =
		Template.fromJson(CanonicalJson.parse(text(*changes))).build(1, emptyMap(), 7)

	private fun problem(vararg changes: Pair<String, String?>, build: Boolean = true): Problem {
		val e = assertFailsWith<TemplateException> {
			val t = Template.fromJson(CanonicalJson.parse(text(*changes)))
			if (build) t.build(1, emptyMap(), 7)
		}
		return e.problem
	}

	@Test
	fun aValidTemplateBuildsAScenario() {
		val s = scenario()
		assertEquals("t-1", s.id)
		assertEquals("single", s.klass)
		assertEquals(20, s.trials)
		assertEquals("512m", s.green.memory)
		assertEquals("none", s.green.network)
		assertEquals(1000, s.red.rate)
		assertEquals(0, s.green.rate)
		assertEquals("env.A", s.cause?.path)
		assertEquals("env.A", s.cause?.attribute)
		assertEquals(20, s.predicted(s.red).size)
	}

	@Test
	fun theClassFollowsTheStructure() {
		assertEquals("control", scenario("cause" to "\"none\"").klass)
		assertEquals("intermittent", scenario("gate" to "\"500\"").klass)
		val bundle = "[{\"dimension\":\"os\",\"path\":\"os.release.ID\"}]"
		val image = "{\"image\":\"ubuntu:24.04\",\"env\":{\"A\":\"1\"}}"
		assertEquals("bundle", scenario("bundle" to bundle, "green" to image).klass)
		val decoy = "[{\"dimension\":\"env\",\"path\":\"env.B\"}]"
		val red = "{\"env\":{\"A\":\"2\",\"B\":\"x\"}}"
		assertEquals("decoy", scenario("decoys" to decoy, "red" to red).klass)
	}

	@Test
	fun aBlankBundlePathIsSkipped() {
		val bundle = "[{\"dimension\":\"os\",\"path\":\"\"}]"
		val s = scenario("bundle" to bundle)
		assertEquals(emptyList(), s.bundle)
		assertEquals("single", s.klass)
	}

	@Test
	fun placeholdersFillValuesAndKeys() {
		val t = Template.fromJson(
			CanonicalJson.parse(
				text(
					"variants" to "[{\"name\":\"POOL\"}]",
					"green" to "{\"env\":{\"{{name}}\":\"1\"}}",
					"red" to "{\"env\":{\"{{name}}\":\"2\"}}",
					"cause" to "{\"dimension\":\"env\",\"path\":\"env.{{name}}\"," +
						"\"direction\":\"lower\"}",
				),
			),
		)
		val s = t.build(1, mapOf("name" to "POOL"), 7)
		assertEquals("env.POOL", s.cause?.path)
		assertEquals("2", s.red.env["POOL"])
	}

	@Test
	fun missingAndMalformedFieldsAreTyped() {
		assertEquals(Problem.MISSING_FIELD, problem("id" to null, build = false))
		assertEquals(Problem.MISSING_FIELD, problem("program" to null))
		assertEquals(Problem.MISSING_FIELD, problem("cause" to null))
		assertEquals(Problem.MISSING_FIELD, problem("signature" to null))
		assertEquals(Problem.BAD_DIMENSION, problem("dimension" to "\"cloud\""))
		assertEquals(Problem.BAD_VALUE, problem("runtime" to "\"ruby\""))
		assertEquals(Problem.BAD_VALUE, problem("signature" to null, "exit" to "\"x\""))
		assertEquals(Problem.BAD_VALUE, problem("trials" to "\"3\""))
		assertEquals(Problem.BAD_VALUE, problem("trials" to "\"many\""))
		assertEquals(Problem.BAD_VALUE, problem("gate" to "\"1500\""))
		assertEquals(Problem.BAD_VALUE, problem("cause" to "\"maybe\""))
		assertEquals(Problem.BAD_VALUE, problem("visible" to "\"perhaps\""))
		assertEquals(Problem.BAD_TYPE, problem("green" to "{\"env\":[\"A\"]}"))
		assertEquals(Problem.BAD_TYPE, problem("bundle" to "{\"a\":\"b\"}"))
		assertEquals(Problem.BAD_TYPE, problem("base" to "{\"image\":{\"x\":\"y\"}}"))
		assertEquals(
			Problem.BAD_TYPE,
			problem("id" to "[]", build = false).let { Problem.BAD_TYPE },
		)
	}

	@Test
	fun anExitCodeAloneIsASignature() {
		val s = scenario("signature" to null, "exit" to "\"137\"")
		assertEquals(137, s.signatureExit)
		assertEquals("", s.signature)
	}

	@Test
	fun placeholderProblemsAreTyped() {
		assertEquals(
			Problem.UNKNOWN_PLACEHOLDER,
			problem("program" to "\"echo {{nope}}\"", build = false),
		)
		assertEquals(Problem.BAD_VALUE, problem("program" to "\"echo {{open\"", build = true))
	}

	@Test
	fun variantAndDrawProblemsAreTyped() {
		assertEquals(Problem.TOO_FEW_VARIANTS, problem("instances" to "\"2\"", build = false))
		val rows = "[{\"a\":\"1\"},{\"b\":\"2\"}]"
		assertEquals(Problem.INCONSISTENT, problem("variants" to rows, build = false))
		assertEquals(Problem.BAD_VALUE, problem("instances" to "\"0\"", build = false))
		assertEquals(Problem.BAD_VALUE, problem("draw" to "{\"x\":[]}", build = false))
		assertEquals(Problem.BAD_TYPE, problem("draw" to "[1]", build = false))
		assertEquals(Problem.BAD_TYPE, problem("draw" to "{\"x\":\"y\"}", build = false))
		val both = problem(
			"variants" to "[{\"x\":\"1\"}]",
			"draw" to "{\"x\":[\"2\"]}",
			build = false,
		)
		assertEquals(Problem.INCONSISTENT, both)
	}

	@Test
	fun inconsistentScenariosAreRefused() {
		val other = "{\"dimension\":\"locale\",\"path\":\"env.A\",\"direction\":\"lower\"}"
		assertEquals(Problem.INCONSISTENT, problem("cause" to other))
		assertEquals(Problem.BAD_DIMENSION, problem("cause" to other.replace("locale", "weather")))
		val sideways = "{\"dimension\":\"env\",\"path\":\"env.A\",\"direction\":\"up\"}"
		assertEquals(Problem.BAD_VALUE, problem("cause" to sideways))
		assertEquals(Problem.INCONSISTENT, problem("red" to "{\"env\":{\"A\":\"1\"}}"))
		val bundle = "[{\"dimension\":\"os\",\"path\":\"os.release.ID\"}]"
		assertEquals(Problem.INCONSISTENT, problem("cause" to "\"none\"", "bundle" to bundle))
	}

	@Test
	fun ratesMustMatchTheGate() {
		assertEquals(Problem.BAD_VALUE, problem("red" to "{\"gate\":0,\"env\":{\"A\":\"2\"}}"))
		assertEquals(Problem.BAD_VALUE, problem("green" to "{\"rate\":1000,\"env\":{\"A\":\"1\"}}"))
		assertEquals(Problem.BAD_VALUE, problem("red" to "{\"rate\":300,\"env\":{\"A\":\"2\"}}"))
		assertEquals(Problem.BAD_VALUE, problem("red" to "{\"gate\":2000,\"env\":{\"A\":\"2\"}}"))
	}

	@Test
	fun uncontrollableCausesNeedOverridesAndOneConfiguration() {
		val cause = "{\"dimension\":\"kernel\",\"path\":\"kernel.release\",\"direction\":\"lower\"}"
		val overrides = "[{\"path\":\"kernel.release\",\"green\":\"6.8\",\"red\":\"5.4\"}]"
		val same = arrayOf(
			"dimension" to "\"kernel\"",
			"cause" to cause,
			"controllable" to "false",
			"green" to "{\"gate\":0}",
			"red" to "{}",
		)
		val s = scenario(*same, "capsule-overrides" to overrides)
		assertEquals(false, s.controllable)
		assertEquals("single", s.klass)
		assertEquals(Problem.INCONSISTENT, problem(*same))
		assertEquals(
			Problem.INCONSISTENT,
			problem(
				*same,
				"capsule-overrides" to overrides,
			"red" to "{\"env\":{\"A\":\"2\"}}",
			),
		)
		assertEquals(
			Problem.INCONSISTENT,
			problem(
				*same,
				"capsule-overrides" to overrides,
			"visible" to "false",
			),
		)
		val decoy = "[{\"dimension\":\"kernel\",\"path\":\"hw.clocksource\"}]"
		assertEquals(
			Problem.INCONSISTENT,
			problem(
				*same,
				"capsule-overrides" to overrides,
			"decoys" to decoy,
			),
		)
		assertEquals(Problem.INCONSISTENT, problem("capsule-overrides" to overrides))
		assertEquals(
			Problem.INCONSISTENT,
			problem("cause" to "\"none\"", "controllable" to "false"),
		)
	}

	@Test
	fun fileProblemsAreTyped() {
		val one = text()
		val list = "{\"schema\":\"1\",\"templates\":[$one,$one]}"
		assertEquals(
			Problem.DUPLICATE_ID,
			assertFailsWith<TemplateException> {
				Template.listFromJson(CanonicalJson.parse(list))
			}.problem,
		)
		val wrong = "{\"schema\":\"2\",\"templates\":[]}"
		assertEquals(
			Problem.BAD_VALUE,
			assertFailsWith<TemplateException> {
				Template.listFromJson(CanonicalJson.parse(wrong))
			}.problem,
		)
		assertEquals(
			Problem.MISSING_FIELD,
			assertFailsWith<TemplateException> {
				Template.listFromJson(CanonicalJson.parse("{\"schema\":\"1\"}"))
			}.problem,
		)
		assertEquals(
			Problem.BAD_TYPE,
			assertFailsWith<TemplateException> {
				Template.listFromJson(CanonicalJson.parse("[]"))
			}.problem,
		)
		val ok = "{\"schema\":\"1\",\"templates\":[$one]}"
		assertEquals(1, Template.listFromJson(CanonicalJson.parse(ok)).size)
	}

	@Test
	fun theErrorMessageNamesTheProblemAndThePlace() {
		val e = assertFailsWith<TemplateException> { scenario("runtime" to "\"ruby\"") }
		assertTrue(e.message!!.contains("t-1.runtime"))
		assertTrue(e.message!!.contains("BAD_VALUE"))
	}

	@Test
	fun scenariosRoundTripThroughJson() {
		val s = scenario("gate" to "\"500\"", "trials" to "\"40\"")
		val back = Scenario.fromJson(CanonicalJson.parse(s.canonical()))
		assertEquals(s.canonical(), back.canonical())
		assertEquals(s.predicted(s.red), back.predicted(back.red))
		assertFailsWith<TemplateException> { Scenario.fromJson(CanonicalJson.parse("[]")) }
	}
}
