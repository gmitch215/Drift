package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleCompiler
import dev.gmitch215.drift.know.RuleException
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.know.Violation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleCompilerTest {
	private fun fails(detect: String = GCC_14, vararg edits: Pair<String, String?>): Violation =
		assertFailsWith<RuleException> {
			RuleCompiler.compile(ruleJson(detect, *edits))
		}.violation

	@Test
	fun compilesAWellFormedRuleAndRoundTripsCanonically() {
		val rule = demoRule()
		assertEquals("demo-rule", rule.id)
		assertEquals(Status.ACTIVE, rule.status)
		assertNull(rule.probe)
		assertEquals(Basis.INFERRED, rule.symptoms.single().basis)
		val again = Rule.fromJson(CanonicalJson.parse(rule.canonical()))
		assertEquals(rule, again)
		assertEquals(rule.canonical(), again.canonical())
		assertEquals(rule.hash(), again.hash())
	}

	@Test
	fun optionalFieldsSurviveTheRoundTrip() {
		val rule = demoRule(
			GCC_14,
			"probe" to "\"numeric.parse\"",
			"status" to "\"pending-scanner\"",
			"provenance" to """[{"url":"fixture:x","kind":"measurement","verified":"2026-10-06",
				|"section":"s"}]
""".trimMargin().replace("\n", ""),
		)
		assertEquals("numeric.parse", rule.probe)
		assertEquals(Status.PENDING_SCANNER, rule.status)
		assertEquals("s", rule.provenance.single().section)
		assertEquals(rule, Rule.fromJson(CanonicalJson.parse(rule.canonical())))
	}

	@Test
	fun versionRangeShapeIsNormalizedToOneCanonicalForm() {
		val one = demoRule("""{"b":"tool.cc.version","ver":["<14"]}""")
		val text = """{"b":"tool.cc.version","ver":"<14"}"""
		assertEquals(demoRule(text).canonical(), one.canonical())
	}

	@Test
	fun unknownFieldsAreRejectedWithTheirPath() {
		assertEquals("extra", fails(GCC_14, "extra" to "1").path)
		val symptom = fails(GCC_14, "symptoms" to """[{"text":"t","basis":"inferred","x":1}]""")
		assertEquals("symptoms[0].x", symptom.path)
		val fixture = fails(
			GCC_14,
			"fixtures" to """{"positive":[],"negative":[],"neutral":[]}""",
		)
		assertEquals("fixtures.neutral", fixture.path)
		val side = fails(
			GCC_14,
			"fixtures" to """{"positive":[{"name":"p","a":{"attr":{}},"b":{}}],"negative":[]}""",
		)
		assertEquals("fixtures.positive[0].a.attr", side.path)
		assertEquals("demo-rule", side.ruleId)
	}

	@Test
	fun missingFieldsAndWrongTypesNameThePath() {
		assertEquals("title", fails(GCC_14, "title" to null).path)
		assertEquals("symptoms", fails(GCC_14, "symptoms" to "3").path)
		assertEquals("title", fails(GCC_14, "title" to "3").path)
		assertEquals("schema", fails(GCC_14, "schema" to "2").path)
		assertEquals("severity", fails(GCC_14, "severity" to "\"urgent\"").path)
		val basis = fails(GCC_14, "symptoms" to """[{"text":"t","basis":"x"}]""")
		assertEquals("symptoms[0].basis", basis.path)
		val kind = fails(
			GCC_14,
			"provenance" to """[{"url":"https://a","kind":"blog","verified":"2026-10-06"}]""",
		)
		assertEquals("provenance[0].kind", kind.path)
	}

	@Test
	fun malformedJsonIsAViolationNotACrash() {
		val e = assertFailsWith<RuleException> { RuleCompiler.compile("{", "demo-rule") }
		assertEquals("demo-rule", e.violation.ruleId)
		val notObject = assertFailsWith<RuleException> { RuleCompiler.compile("[]", "x") }
		assertEquals("x", notObject.violation.ruleId)
	}

	@Test
	fun fileNameMustEqualTheId() {
		val text = CanonicalJson.encode(ruleJson())
		assertEquals("demo-rule", RuleCompiler.compile(text, "demo-rule").id)
		val e = assertFailsWith<RuleException> {
			RuleCompiler.compile(text, "other")
		}
		assertEquals("id", e.violation.path)
	}

	private fun prov(url: String = "https://a", verified: String = "2026-10-06") =
		"provenance" to """[{"url":"$url","kind":"spec","verified":"$verified"}]"""

	private fun fixtures(positive: String, negative: String) =
		"fixtures" to """{"positive":$positive,"negative":$negative}"""

	private val fx = """{"name":"n","a":{},"b":{}}"""

	@Test
	fun theGateRejectsEachMissingPiece() {
		val rows = listOf(
			"id" to ("id" to "\"Demo_Rule\""),
			"id" to ("id" to "\"demo--rule\""),
			"title" to ("title" to "\" \""),
			"difference" to ("difference" to "\"\""),
			"mechanism" to ("mechanism" to "\"\""),
			"mechanism" to ("mechanism" to "\"No period\""),
			"mechanism" to ("mechanism" to "\"One. Two.\""),
			"mechanism" to ("mechanism" to "\"Line\\nbreak.\""),
			"applies" to ("applies" to "[]"),
			"applies[0]" to ("applies" to "[\"gpu\"]"),
			"probe" to ("probe" to "\" \""),
			"symptoms" to ("symptoms" to "[]"),
			"symptoms[0].text" to ("symptoms" to """[{"text":"","basis":"inferred"}]"""),
			"provenance" to ("provenance" to "[]"),
			"provenance[0].url" to prov(url = "http://a"),
			"provenance[0].url" to prov(url = "https://"),
			"provenance[0].verified" to prov(verified = "2026-13-06"),
			"provenance[0].verified" to prov(verified = "2026-1x-06"),
			"provenance[0].verified" to prov(verified = "2026/10/06"),
			"fixtures.positive" to fixtures("[]", "[$fx]"),
			"fixtures.negative" to fixtures("[$fx]", "[]"),
			"fixtures.positive[0].name" to fixtures("""[{"name":"","a":{},"b":{}}]""", "[$fx]"),
			"fixtures.negative[0].name" to fixtures("[$fx]", """[{"name":" ","a":{},"b":{}}]"""),
		)
		for ((path, edit) in rows) {
			assertEquals(path, fails(GCC_14, edit).path, edit.toString())
		}
	}

	@Test
	fun pathsMustStartWithADimensionPrefix() {
		assertEquals("detect", fails("""{"b":"tool.zzz.version","ver":">=14"}""").path)
		assertEquals("detect", fails("""{"changed":"capture.*"}""").path)
		demoRule("""{"changed":"os.release.*"}""")
		demoRule("""{"b":"probe:anything.goes","has":"x"}""")
		demoRule("""{"b":"run:signature","has":"x"}""")
	}

	@Test
	fun detectShapeErrorsAreCompileErrors() {
		val rows = listOf(
			"detect" to """{"a":"os.arch","b":"os.arch","eq":"x"}""",
			"detect" to """{"eq":"x"}""",
			"detect" to """{"a":"os.arch"}""",
			"detect" to """{"a":"os.arch","eq":"x","ne":"y"}""",
			"detect" to """{"all":[{"a":"os.arch","eq":"x"}],"any":[]}""",
			"detect.all" to """{"all":[]}""",
			"detect.any" to """{"any":[]}""",
			"detect.all" to """{"all":"x"}""",
			"detect" to """{"changed":"os.arch","eq":"x"}""",
			"detect.in" to """{"a":"os.arch","in":[]}""",
			"detect.a" to """{"a":"os arch","eq":"x"}""",
			"detect.a" to """{"a":"","eq":"x"}""",
			"detect.a" to """{"a":"os.*","eq":"x"}""",
			"detect.changed" to """{"changed":"os.*.x"}""",
			"detect.changed" to """{"changed":"probe:*"}""",
			"detect.cross" to """{"cross":"run:","has":"x"}""",
			"detect.ver" to """{"b":"tool.cc.version","ver":"<unparsed"}""",
			"detect.ver" to """{"b":"tool.cc.version","ver":"14"}""",
			"detect.ver" to """{"b":"tool.cc.version","ver":"<1 4"}""",
			"detect.ver" to """{"b":"tool.cc.version","ver":[]}""",
			"detect.ver" to """{"b":"tool.cc.version","ver":[3]}""",
			"detect.ver" to """{"b":"tool.cc.version","ver":3}""",
			"detect.not" to """{"not":3}""",
			"detect.eq" to """{"a":"os.arch","eq":3}""",
			"detect.present" to """{"a":"os.arch","present":"yes"}""",
		)
		for ((path, detect) in rows) assertEquals(path, fails(detect).path, detect)
	}

	@Test
	fun depthIsCappedAtEight() {
		fun nest(levels: Int): String =
			(1..levels).fold("""{"a":"os.arch","eq":"x"}""") { inner, _ -> """{"not":$inner}""" }
		demoRule(nest(8))
		assertTrue(fails(nest(9)).reason.contains("deeper"))
	}

	@Test
	fun violationNamesTheRuleAndThePath() {
		val v = fails(GCC_14, "mechanism" to "\"x\"")
		assertEquals(
			"rule 'demo-rule' at 'mechanism': must be one sentence ending in a period",
			v.toString(),
		)
	}
}
