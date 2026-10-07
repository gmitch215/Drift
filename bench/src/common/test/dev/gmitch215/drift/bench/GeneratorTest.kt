package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratorTest {
	private fun template(id: String, instances: Int, extra: String = ""): Template {
		val text = """{"id":"$id","instances":"$instances","dimension":"env","runtime":"sh",
			|"program":"echo {{n}}","signature":"boom",
			|"variants":[{"n":"a"},{"n":"b"},{"n":"c"}],
			|"draw":{"d":["x","y","z"]},
			|"base":{"image":"debian:12-slim"},"green":{"env":{"A":"1"}},
			|"red":{"env":{"A":"2"}},"gate":"500","trials":"40",
			|"cause":{"dimension":"env","path":"env.A","direction":"lower"}$extra}
		""".trimMargin()
		return Template.fromJson(CanonicalJson.parse(text))
	}

	private val templates = listOf(template("one", 2), template("two", 3))

	@Test
	fun theSameSeedGivesTheSameBytes() {
		val a = Generator.scenariosJson(Generator.generate(templates, 42))
		val b = Generator.scenariosJson(Generator.generate(templates, 42))
		assertEquals(a, b)
		assertNotEquals(a, Generator.scenariosJson(Generator.generate(templates, 43)))
	}

	@Test
	fun theOutputIsOneSortedScenarioPerLine() {
		val text = Generator.scenariosJson(Generator.generate(templates, 42))
		assertTrue(text.startsWith("[\n") && text.endsWith("\n]\n"))
		val parsed = CanonicalJson.parse(text) as JsonArray
		assertEquals(5, parsed.items.size)
		val ids = parsed.items.map { (it as JsonObject).fields.getValue("id").string() }
		assertEquals(ids.sorted(), ids)
		assertEquals(listOf("one-1", "one-2", "two-1", "two-2", "two-3"), ids)
	}

	@Test
	fun everySaltFitsTheDeclaredRate() {
		val all = Generator.generate(templates, 42)
		assertTrue(all.all { it.accepts() })
		assertTrue(all.all { it.predicted(it.red).size in 12..28 })
		assertTrue(all.all { it.predicted(it.green).isEmpty() })
	}

	@Test
	fun variantsAreDistinctAndTheOrderFollowsTheSeed() {
		val picks = Generator.expand(templates[1], 42).map { it.source }
		assertEquals(3, picks.toSet().size)
		val orders = (1L..12L).map { seed ->
			Generator.expand(templates[1], seed).map { it.source }
		}
		assertTrue(orders.all { it.toSet() == picks.toSet() })
		assertTrue(orders.toSet().size > 1)
		assertEquals(picks, Generator.expand(templates[1], 42).map { it.source })
	}

	@Test
	fun drawsAreSeededAndStayInTheirList() {
		val all = Generator.expand(template("d", 3, ",\"note\":\"{{d}}\""), 5)
		assertTrue(all.all { it.note in listOf("x", "y", "z") })
		assertEquals(
			all.map {
			it.note
		},
			Generator.expand(template("d", 3, ",\"note\":\"{{d}}\""), 5).map { it.note },
		)
	}

	@Test
	fun excludedIdsAreLeftOut() {
		val all = Generator.generate(templates, 42, setOf("two-2"))
		assertEquals(listOf("one-1", "one-2", "two-1", "two-3"), all.map { it.id })
	}

	@Test
	fun aTemplateWithNoFittingSaltIsRefused() {
		val e = assertFailsWith<TemplateException> { Generator.expand(templates[0], 1, tries = 0) }
		assertEquals(Problem.NO_SALT, e.problem)
	}

	@Test
	fun duplicateScenarioIdsAreRefused() {
		val e = assertFailsWith<TemplateException> {
			Generator.generate(listOf(templates[0], template("one", 1)), 42)
		}
		assertEquals(Problem.DUPLICATE_ID, e.problem)
	}

	@Test
	fun theSplitIsStableUnderReordering() {
		val ids = (1..200).map { "scenario-$it" }
		val a = SplitRule.split(ids)
		val b = SplitRule.split(ids.reversed())
		assertEquals(a.dev, b.dev)
		assertEquals(a.test, b.test)
		assertEquals(a.testSha256, b.testSha256)
		assertEquals(ids.sorted(), (a.dev + a.test).sorted())
	}

	@Test
	fun theRuleIsTheHashOfTheId() {
		for (id in listOf("alpha", "beta", "gamma", "delta")) {
			assertEquals(Hashing.hash32(id) % 100 < SplitRule.DEV_PERCENT, SplitRule.isDev(id))
		}
		assertEquals(0xBA7816BFL % 100 < 60, SplitRule.isDev("abc"))
	}

	@Test
	fun devIsAboutSixtyPercentOfManyIds() {
		val split = SplitRule.split((1..2000).map { "id-$it" })
		assertTrue(split.dev.size in 1100..1300, "dev was ${split.dev.size}")
	}

	@Test
	fun theTestHashCoversTheSortedTestIds() {
		val split = Split(listOf("a"), listOf("b", "c"))
		assertEquals(Sha256.hex("b\nc\n"), split.testSha256)
		val json = CanonicalJson.parse(split.toJson()) as JsonObject
		assertEquals(Sha256.hex("b\nc\n"), json.fields.getValue("test_sha256").string())
		assertEquals(listOf("b", "c"), json.fields.getValue("test").array().map { it.string() })
		assertEquals(SplitRule.DESCRIPTION, json.fields.getValue("rule").string())
	}
}
