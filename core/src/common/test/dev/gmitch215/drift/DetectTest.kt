package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.RuleException
import dev.gmitch215.drift.know.RuleTier
import dev.gmitch215.drift.know.Verdict
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.model.Step
import dev.gmitch215.drift.model.StepConclusion
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectTest {
	private fun attrs(vararg p: Pair<String, String>) = Env(attrs = mapOf(*p))

	private fun run(detect: String, a: Env, b: Env) = Detect.match(demoRule(detect), a, b)

	private fun evidence(detect: String, a: Env, b: Env) =
		run(detect, a, b).match?.evidence?.map { it.ref }

	private class Case(
		val name: String,
		val detect: String,
		val a: Env,
		val b: Env,
		val verdict: Verdict,
		val evidence: List<String>?,
	)

	private val gcc = GCC_14
	private val patched = """[">=2.34.2,<2.35.0",">=2.35.2"]"""
	private val git = """{"all":[{"b":"tool.git.version","ver":$patched},
		|{"not":{"a":"tool.git.version","ver":$patched}}]}
""".trimMargin().replace("\n", "")

	private val cases = listOf(
		Case(
			"E1 gcc crosses 14",
			gcc,
			cc("13.2.1"),
			cc("14.2.0"),
			Verdict.MATCH,
			listOf("tool.cc.version"),
		),
		Case("E2 sides swapped", gcc, cc("14.2.0"), cc("13.2.1"), Verdict.NO_MATCH, null),
		Case(
			"E3 symmetric boundary",
			"""{"cross":"tool.php.version","ver":">=8.0.0"}""",
			attrs("tool.php.version" to "7.4.33"),
			attrs("tool.php.version" to "8.1.34"),
			Verdict.MATCH,
			listOf("tool.php.version"),
		),
		Case(
			"E3 symmetric boundary, swapped",
			"""{"cross":"tool.php.version","ver":">=8.0.0"}""",
			attrs("tool.php.version" to "8.1.34"),
			attrs("tool.php.version" to "7.4.33"),
			Verdict.MATCH,
			listOf("tool.php.version"),
		),
		Case(
			"E3 both on one side of the boundary",
			"""{"cross":"tool.php.version","ver":">=8.0.0"}""",
			attrs("tool.php.version" to "8.0.1"),
			attrs("tool.php.version" to "8.1.34"),
			Verdict.NO_MATCH,
			null,
		),
		Case(
			"E4 java 8 below 17",
			"""{"all":[{"a":"tool.java.version","ver":"<17"},
				|{"b":"tool.java.version","ver":">=17"}]}
""".trimMargin().replace("\n", ""),
			attrs("tool.java.version" to "1.8.0_504"),
			attrs("tool.java.version" to "21.0.12.1"),
			Verdict.MATCH,
			listOf("tool.java.version"),
		),
		Case(
			"E5 enum",
			"""{"all":[{"a":"tool.coreutils.flavor","eq":"gnu"},
				|{"b":"tool.coreutils.flavor","eq":"busybox"}]}
""".trimMargin().replace("\n", ""),
			attrs("tool.coreutils.flavor" to "gnu"),
			attrs("tool.coreutils.flavor" to "busybox"),
			Verdict.MATCH,
			listOf("tool.coreutils.flavor"),
		),
		Case(
			"E6 or of ranges plus not",
			git,
			attrs("tool.git.version" to "2.34.1"),
			attrs("tool.git.version" to "2.45.4"),
			Verdict.MATCH,
			listOf("tool.git.version"),
		),
		Case(
			"E6 both patched",
			git,
			attrs("tool.git.version" to "2.34.2"),
			attrs("tool.git.version" to "2.45.4"),
			Verdict.NO_MATCH,
			null,
		),
		Case("E7 unparsed is unknown", gcc, cc("unparsed"), cc("14.2.0"), Verdict.UNKNOWN, null),
		Case(
			"E8 probe text",
			"""{"all":[{"b":"tool.go.version","ver":"<1.25"},
				|{"b":"probe:resources.cgroup-cpu","has":"limited cpus="}]}"""
				.trimMargin().replace("\n", ""),
			Env(),
			Env(
				attrs = mapOf("tool.go.version" to "1.24.13"),
				probes = mapOf("resources.cgroup-cpu" to "cpu-max = limited cpus=2.0"),
			),
			Verdict.MATCH,
			listOf("probe:resources.cgroup-cpu", "tool.go.version"),
		),
		Case(
			"E9 prefix change",
			"""{"changed":"os.release.*"}""",
			attrs("os.release.VERSION_ID" to "3.20.8", "os.release.ID" to "alpine"),
			attrs("os.release.VERSION_ID" to "3.22.2", "os.release.ID" to "alpine"),
			Verdict.MATCH,
			listOf("os.release.VERSION_ID"),
		),
		Case(
			"E10 symptom plus fact",
			"""{"all":[{"b":"run:signature","has":"Connection reset by peer"},
				|{"b":"tool.node.version","ver":">=22"}]}
""".trimMargin().replace("\n", ""),
			Env(),
			Env(
				attrs = mapOf("tool.node.version" to "24.21.0"),
				run = mapOf("signature" to "workerd: Connection reset by peer"),
			),
			Verdict.MATCH,
			listOf("run:signature", "tool.node.version"),
		),
		Case(
			"E11 not over a missing fact",
			"""{"not":{"a":"tool.cc.version","ver":">=14"}}""",
			Env(),
			cc("14.2.0"),
			Verdict.UNKNOWN,
			null,
		),
	)

	@Test
	fun workedExamples() {
		for (c in cases) {
			val out = run(c.detect, c.a, c.b)
			assertEquals(c.verdict, out.verdict, c.name)
			assertEquals(c.evidence, out.match?.evidence?.map { it.ref }, c.name)
		}
	}

	@Test
	fun matchCarriesTheFullStructuredResult() {
		val out = run(gcc, cc("13.2.1"), cc("14.2.0"))
		val m = out.match!!
		assertEquals("demo-rule", m.ruleId)
		assertEquals("Demo", m.title)
		assertEquals(RuleTier.KNOWN, m.tier)
		assertEquals("M.", m.mechanism)
		assertEquals(Basis.INFERRED, m.basis)
		assertEquals(
			"{\"basis\":\"inferred\",\"evidence\":[{\"ref\":\"tool.cc.version\"}]," +
				"\"mechanism\":\"M.\",\"rule\":\"demo-rule\",\"severity\":\"low\"," +
				"\"tier\":\"known\",\"title\":\"Demo\"}",
			CanonicalJson.encode(m.toJson()),
		)
		assertNull(run(gcc, cc("unparsed"), cc("14.2.0")).match)
	}

	@Test
	fun basisIsVerifiedOnlyWhenEverySymptomIs() {
		val both = """[{"text":"a","basis":"verified"},{"text":"b","basis":"inferred"}]"""
		val mixed = Detect.match(demoRule(gcc, "symptoms" to both), cc("13"), cc("14"))
		assertEquals(Basis.INFERRED, mixed.match!!.basis)
		val one = """[{"text":"a","basis":"verified"}]"""
		val solo = Detect.match(demoRule(gcc, "symptoms" to one), cc("13"), cc("14"))
		assertEquals(Basis.VERIFIED, solo.match!!.basis)
	}

	@Test
	fun versionPredicates() {
		fun ver(range: String, path: String, value: String?): Verdict {
			val rule = demoRule("""{"b":"$path","ver":$range}""")
			val env = if (value == null) Env() else attrs(path to value)
			return Detect.match(rule, Env(), env).verdict
		}
		val java = "tool.java.version"
		assertEquals(Verdict.MATCH, ver("\"<9\"", java, "1.8.0_504"))
		assertEquals(Verdict.NO_MATCH, ver("\"<9\"", java, "11.0.32+1"))
		assertEquals(Verdict.MATCH, ver("\"==8.0.504\"", java, "1.8.0_504"))
		assertEquals(Verdict.MATCH, ver("\"<=2\"", "cpu.count", "2"))
		assertEquals(Verdict.NO_MATCH, ver("\"<=2\"", "cpu.count", "4"))
		assertEquals(Verdict.MATCH, ver("\"!=4\"", "cpu.count", "2"))
		assertEquals(Verdict.NO_MATCH, ver("\">2\"", "cpu.count", "2"))
		assertEquals(Verdict.MATCH, ver("\">=1.5, <9\"", "tool.go.version", "go1.8"))
		assertEquals(Verdict.NO_MATCH, ver("\">=1.5,<9\"", "tool.go.version", "9.0"))
		assertEquals(Verdict.MATCH, ver("[\"<1\",\">=3\"]", "tool.go.version", "3.1"))
		assertEquals(Verdict.NO_MATCH, ver("[\"<1\",\">=3\"]", "tool.go.version", "2"))
		assertEquals(Verdict.UNKNOWN, ver("\"<9\"", java, "unparsed"))
		assertEquals(Verdict.UNKNOWN, ver("\"<9\"", java, ""))
		assertEquals(Verdict.UNKNOWN, ver("\"<9\"", java, "99999999999999999999"))
		assertEquals(Verdict.UNKNOWN, ver("\"<9\"", java, null))
	}

	@Test
	fun stringPredicates() {
		fun on(pred: String, value: String?): Verdict {
			val env = if (value == null) Env() else attrs("env.LANG" to value)
			return Detect.match(demoRule("""{"b":"env.LANG",$pred}"""), Env(), env).verdict
		}
		assertEquals(Verdict.MATCH, on("\"eq\":\"C\"", "C"))
		assertEquals(Verdict.NO_MATCH, on("\"eq\":\"C\"", "c"))
		assertEquals(Verdict.UNKNOWN, on("\"eq\":\"C\"", null))
		assertEquals(Verdict.MATCH, on("\"ne\":\"C\"", "POSIX"))
		assertEquals(Verdict.NO_MATCH, on("\"ne\":\"C\"", "C"))
		assertEquals(Verdict.UNKNOWN, on("\"ne\":\"C\"", null))
		assertEquals(Verdict.MATCH, on("\"in\":[\"C\",\"POSIX\"]", "POSIX"))
		assertEquals(Verdict.NO_MATCH, on("\"in\":[\"C\",\"POSIX\"]", "en"))
		assertEquals(Verdict.MATCH, on("\"has\":\"UTF\"", "en_US.UTF-8"))
		assertEquals(Verdict.NO_MATCH, on("\"has\":\"utf\"", "en_US.UTF-8"))
		assertEquals(Verdict.MATCH, on("\"starts\":\"en_\"", "en_US.UTF-8"))
		assertEquals(Verdict.NO_MATCH, on("\"starts\":\"US\"", "en_US.UTF-8"))
		assertEquals(Verdict.MATCH, on("\"present\":true", "x"))
		assertEquals(Verdict.NO_MATCH, on("\"present\":true", null))
		assertEquals(Verdict.MATCH, on("\"present\":false", null))
		assertEquals(Verdict.NO_MATCH, on("\"present\":false", "x"))
	}

	@Test
	fun changedAndCross() {
		fun changed(path: String, a: Env, b: Env) =
			Detect.match(demoRule("""{"changed":"$path"}"""), a, b)
		val x = attrs("os.arch" to "x86_64", "os.release.ID" to "alpine")
		val y = attrs("os.arch" to "aarch64", "os.release.ID" to "debian")
		assertEquals(Verdict.MATCH, changed("os.arch", x, y).verdict)
		assertEquals(Verdict.NO_MATCH, changed("os.arch", x, x).verdict)
		assertEquals(Verdict.UNKNOWN, changed("os.arch", x, Env()).verdict)
		assertEquals(Verdict.UNKNOWN, changed("os.arch", Env(), Env()).verdict)
		assertEquals(Verdict.UNKNOWN, changed("os.release.*", Env(), Env()).verdict)
		assertEquals(Verdict.NO_MATCH, changed("os.release.*", x, x).verdict)
		assertEquals(listOf("os.release.ID"), evidence("""{"changed":"os.release.*"}""", x, y))
		val p = Env(probes = mapOf("numeric.parse" to "1"))
		val q = Env(probes = mapOf("numeric.parse" to "2"))
		assertEquals(Verdict.MATCH, changed("probe:numeric.parse", p, q).verdict)
		val cross = """{"cross":"probe:numeric.parse","has":"2"}"""
		assertEquals(Verdict.UNKNOWN, Detect.match(demoRule(cross), p, Env()).verdict)
	}

	@Test
	fun evidenceIsDroppedUnderNotAndWhenTheRuleIsNotAMatch() {
		val inside = """{"all":[{"b":"tool.cc.version","ver":">=14"},
			|{"not":{"a":"tool.cc.version","ver":">=14"}}]}
""".trimMargin().replace("\n", "")
		assertEquals(listOf("tool.cc.version"), evidence(inside, cc("13"), cc("14")))
		assertNull(evidence(inside, Env(), cc("14")))
		val any = """{"any":[{"b":"tool.cc.version","ver":">=14"},
			|{"b":"tool.cc.version","ver":">=15"}]}
""".trimMargin().replace("\n", "")
		assertEquals(listOf("tool.cc.version"), evidence(any, Env(), cc("16")))
	}

	@Test
	fun directionIsFixedGreenIsPassingRedIsFailing() {
		val rule = demoRule(gcc)
		assertEquals(Verdict.MATCH, Detect.match(rule, cc("13.2.1"), cc("14.2.0")).verdict)
		assertEquals(Verdict.NO_MATCH, Detect.match(rule, cc("14.2.0"), cc("13.2.1")).verdict)
	}

	@Test
	fun capsulesContributeStaticAttributesAndOkProbesOnly() {
		fun capsule(version: String, stability: Stability, status: ProbeStatus) = Capsule(
			"c",
			listOf(Attribute("tool.cc.version", version, "scan", stability)),
			listOf(ProbeResult("numeric.parse", status, "t")),
		)
		val green = capsule("13.2.1", Stability.STATIC, ProbeStatus.OK)
		val red = capsule("14.2.0", Stability.STATIC, ProbeStatus.UNAVAILABLE)
		val env = Detect.env(red, mapOf("signature" to "s"))
		assertEquals(mapOf("tool.cc.version" to "14.2.0"), env.attrs)
		assertEquals(emptyMap(), env.probes)
		assertEquals(mapOf("signature" to "s"), env.run)
		assertEquals(Verdict.MATCH, Detect.match(demoRule(gcc), green, red).verdict)
		val volatile = capsule("14.2.0", Stability.VOLATILE, ProbeStatus.OK)
		assertEquals(Verdict.UNKNOWN, Detect.match(demoRule(gcc), green, volatile).verdict)
	}

	// #region run
	private val failingRun = Run(
		"9",
		RunOutcome.FAIL,
		steps = listOf(Step("ci", "Test", StepConclusion.FAILURE, 199_000)),
		symptoms = listOf(
			Symptom(SymptomKind.SIGNATURE, "Error: bang", 1),
			Symptom(SymptomKind.EXIT, "1", 2),
			Symptom(SymptomKind.SIGNAL, "SIGTERM", 3),
			Symptom(SymptomKind.TRANSPORT, "broken pipe", 4),
			Symptom(SymptomKind.TRANSPORT, "connection reset", 5),
		),
	)

	private fun fires(detect: String, green: Run?, red: Run?): Verdict {
		val a = Capsule("a", listOf(Attribute("tool.cc.version", "13", "t")))
		val b = Capsule("b", listOf(Attribute("tool.cc.version", "14", "t")))
		return Detect.match(demoRule(detect), a, b, green, red).verdict
	}

	@Test
	fun everyRunFieldReadsFromRunFacts() {
		val facts = failingRun.facts()
		assertEquals(Detect.RUN_FIELDS, facts.keys)
		val leaves = listOf(
			"""{"b":"run:signature","eq":"${facts["signature"]}"}""",
			"""{"b":"run:exit","eq":"1"}""",
			"""{"b":"run:signal","eq":"SIGTERM"}""",
			"""{"b":"run:step","eq":"Test"}""",
			"""{"b":"run:duration-ms","ver":">=199000,<200000"}""",
			"""{"b":"run:transport","has":"broken pipe"}""",
			"""{"b":"run:transport","has":"connection reset"}""",
		)
		for (leaf in leaves) assertEquals(Verdict.MATCH, fires(leaf, null, failingRun), leaf)
		assertEquals(Verdict.NO_MATCH, fires("""{"b":"run:exit","eq":"2"}""", null, failingRun))
	}

	@Test
	fun aRunFieldTheRunLacksIsUnknownNotFalse() {
		val bare = Run("1", RunOutcome.FAIL)
		for (field in Detect.RUN_FIELDS) {
			val leaf = """{"b":"run:$field","eq":"x"}"""
			assertEquals(Verdict.UNKNOWN, fires(leaf, null, bare), field)
			assertEquals(Verdict.UNKNOWN, fires(leaf, null, null), field)
		}
	}

	@Test
	fun eachSideReadsItsOwnRun() {
		val exit = Symptom(SymptomKind.EXIT, "0", 1)
		val passing = Run("0", RunOutcome.PASS, symptoms = listOf(exit))
		val both = """{"all":[{"a":"run:exit","eq":"0"},{"b":"run:exit","eq":"1"}]}"""
		assertEquals(Verdict.MATCH, fires(both, passing, failingRun))
		assertEquals(Verdict.UNKNOWN, fires(both, null, failingRun))
		assertEquals(Verdict.NO_MATCH, fires(both, failingRun, passing))
	}

	@Test
	fun aMisspelledRunFieldIsACompileError() {
		assertFailsWith<RuleException> { demoRule("""{"b":"run:signatur","eq":"x"}""") }
		assertFailsWith<RuleException> { demoRule("""{"b":"run:","eq":"x"}""") }
	}
	// #endregion

	// #region properties
	private val paths = listOf(
		"tool.cc.version",
		"tool.java.version",
		"env.LANG",
		"probe:numeric.parse",
		"run:signature",
	)
	private val values =
		listOf("13.2.1", "14.2.0", "1.8.0_504", "unparsed", "", "C", "en_US.UTF-8", "2:1.0")

	private fun strings(vararg s: String) = JsonArray(s.map(::JsonString))

	private fun leaf(rng: Rng): JsonValue {
		val path = paths[rng.next(paths.size)]
		val side = if (rng.next(2) == 0) "a" else "b"
		return when (rng.next(9)) {
			0 -> obj(side to JsonString(path), "eq" to JsonString(values[rng.next(values.size)]))
			1 -> obj(side to JsonString(path), "ne" to JsonString("C"))
			2 -> obj(side to JsonString(path), "in" to strings("C", "14.2.0"))
			3 -> obj(side to JsonString(path), "has" to JsonString("1"))
			4 -> obj(side to JsonString(path), "starts" to JsonString("1"))
			5 -> obj(side to JsonString(path), "ver" to JsonString("<14"))
			6 -> obj(side to JsonString(path), "ver" to strings("<2", ">=3"))
			7 -> obj("cross" to JsonString(path), "ver" to JsonString(">=14"))
			else -> obj("changed" to JsonString(if (rng.next(2) == 0) "env.*" else path))
		}
	}

	private fun tree(rng: Rng, depth: Int = 0): JsonValue = when {
		depth > 2 || rng.next(10) < 4 -> leaf(rng)

		rng.next(10) < 3 -> obj("not" to tree(rng, depth + 1))

		else -> obj(
			(if (rng.next(2) == 0) "all" else "any") to
				JsonArray(List(rng.next(3) + 1) { tree(rng, depth + 1) }),
		)
	}

	private fun env(rng: Rng): Env {
		fun pick() = values[rng.next(values.size)]
		return Env(
			attrs = paths.filter { !it.contains(':') && rng.next(10) < 6 }.associateWith { pick() },
			probes = if (rng.next(2) == 0) mapOf("numeric.parse" to pick()) else emptyMap(),
			run = if (rng.next(3) == 0) mapOf("signature" to pick()) else emptyMap(),
		)
	}

	private fun verdictOf(tree: JsonValue, a: Env, b: Env) =
		Detect.match(demoRule(CanonicalJson.encode(tree)), a, b)

	private fun shuffled(tree: JsonValue, rng: Rng): JsonValue {
		val o = tree as JsonObject
		return JsonObject(
			o.fields.mapValues { (k, v) ->
				when {
					v is JsonArray && (k == "all" || k == "any") ->
						JsonArray(v.items.map { shuffled(it, rng) }.shuffled(rng))

					k == "not" -> shuffled(v, rng)

					else -> v
				}
			},
		)
	}

	@Test
	fun deMorganAndOrderIndependenceHoldOverRandomTrees() {
		val rng = Rng(11)
		var matches = 0
		repeat(300) {
			val a = env(rng)
			val b = env(rng)
			val x = tree(rng, 1)
			val y = tree(rng, 1)
			val notAll = verdictOf(obj("not" to obj("all" to JsonArray(listOf(x, y)))), a, b)
			val anyNot = verdictOf(
				obj("any" to JsonArray(listOf(obj("not" to x), obj("not" to y)))),
				a,
				b,
			)
			assertEquals(notAll.verdict, anyNot.verdict)
			val notAny = verdictOf(obj("not" to obj("any" to JsonArray(listOf(x, y)))), a, b)
			val allNot = verdictOf(
				obj("all" to JsonArray(listOf(obj("not" to x), obj("not" to y)))),
				a,
				b,
			)
			assertEquals(notAny.verdict, allNot.verdict)
			val t = tree(rng)
			val base = verdictOf(t, a, b)
			val shuffle = verdictOf(shuffled(t, rng), a, b)
			assertEquals(base, shuffle)
			assertEquals(base.verdict == Verdict.MATCH, base.match != null)
			if (base.verdict == Verdict.MATCH) matches++
		}
		assertTrue(matches > 20, "only $matches matching trees")
	}

	@Test
	fun parseAndEncodeRoundTripOverRandomTrees() {
		val rng = Rng(5)
		repeat(200) {
			val t = tree(rng)
			val node = Detect.parse(t, "detect")
			assertEquals(node, Detect.parse(Detect.toJson(node), "detect"))
		}
	}
	// #endregion
}
