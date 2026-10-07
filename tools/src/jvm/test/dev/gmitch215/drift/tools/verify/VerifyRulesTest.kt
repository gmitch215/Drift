package dev.gmitch215.drift.tools.verify

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyRulesTest {
	private val now = { Instant.parse("2026-10-06T00:00:00Z") }

	private fun rules(vararg rule: Pair<String, List<String>>): Path {
		val dir = Files.createTempDirectory("rules")
		for ((id, urls) in rule) {
			val sources = urls.joinToString("\n") { "  - url: '$it'\n    kind: release-note" }
			dir.resolve("$id.yml").writeText("id: $id\nprovenance:\n$sources\n")
		}
		return dir
	}

	private class Fake(val answers: Map<String, FetchResult>) : Fetcher {
		val asked = mutableListOf<String>()

		override fun get(url: String): FetchResult {
			asked += url
			return answers.getValue(url)
		}
	}

	private val okPage = FetchResult(200, "text/html", "https://a.test/final")

	@Test
	fun recordsStatusContentTypeAndFinalUrlPerRuleAndUrl() {
		val dir = rules("r1" to listOf("https://a.test/x"), "r2" to listOf("https://b.test/y"))
		val fake = Fake(
			mapOf(
				"https://a.test/x" to okPage,
				"https://b.test/y" to FetchResult(404, "text/plain", "https://b.test/y"),
			),
		)
		val checks = VerifyRules.run(dir, fake, offline = false, now = now)
		assertEquals(listOf("r1", "r2"), checks.map { it.ruleId })
		assertEquals(listOf(Outcome.OK, Outcome.HTTP_ERROR), checks.map { it.outcome })
		assertEquals("https://a.test/final", checks[0].finalUrl)
		assertEquals("text/html", checks[0].contentType)
		assertEquals(listOf("r2"), VerifyRules.failed(checks).map { it.ruleId })
	}

	@Test
	fun aSharedUrlIsFetchedOnceButReportedForEachRule() {
		val dir = rules("r1" to listOf("https://a.test/x"), "r2" to listOf("https://a.test/x"))
		val fake = Fake(mapOf("https://a.test/x" to okPage))
		val checks = VerifyRules.run(dir, fake, offline = false, now = now)
		assertEquals(2, checks.size)
		assertEquals(listOf("https://a.test/x"), fake.asked)
	}

	@Test
	fun aTransportErrorIsAFailureWithTheMessage() {
		val dir = rules("r1" to listOf("https://a.test/x"))
		val fake = Fake(mapOf("https://a.test/x" to FetchResult(null, null, null, "timeout")))
		val check = VerifyRules.run(dir, fake, offline = false, now = now).single()
		assertEquals(Outcome.ERROR, check.outcome)
		assertEquals("timeout", check.error)
		assertEquals(1, VerifyRules.failed(listOf(check)).size)
	}

	@Test
	fun offlineSkipsEveryUrlWithoutCallingTheFetcher() {
		val dir = rules("r1" to listOf("https://a.test/x", "https://a.test/z"))
		val fake = Fake(emptyMap())
		val checks = VerifyRules.run(dir, fake, offline = true, now = now)
		assertEquals(List(2) { Outcome.SKIPPED }, checks.map { it.outcome })
		assertTrue(fake.asked.isEmpty())
		assertTrue(VerifyRules.failed(checks).isEmpty())
	}

	@Test
	fun theReportListsRuleUrlStatusAndCheckedAt() {
		val dir = rules("r1" to listOf("https://a.test/x"))
		val fake = Fake(mapOf("https://a.test/x" to okPage))
		val checks = VerifyRules.run(dir, fake, offline = false, now = now)
		val row = CanonicalJson.parse(VerifyRules.report(checks)).array().single().obj()
		assertEquals(JsonString("r1"), row.require("rule"))
		assertEquals(JsonString("https://a.test/x"), row.require("url"))
		assertEquals(JsonInt(200), row.require("status"))
		assertEquals(JsonString("2026-10-06T00:00:00Z"), row.require("checkedAt"))
	}

	@Test
	fun theRealRulesDirectoryParses() {
		val dir = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
			.map { it.resolve("rules") }
			.first { Files.isDirectory(it) }
		val all = VerifyRules.sources(dir)
		assertTrue(all.size >= 32)
		assertTrue(all.any { it.second.startsWith("https://") })
	}

	@Test
	fun aNonHttpSourceIsSkippedNotFetched() {
		val dir = rules("r1" to listOf("fixture:transcripts/x.txt"))
		val fake = Fake(emptyMap())
		val check = VerifyRules.run(dir, fake, offline = false, now = now).single()
		assertEquals(Outcome.SKIPPED, check.outcome)
		assertEquals("not an http url", check.error)
		assertTrue(fake.asked.isEmpty())
	}

	@Test
	fun scalarsThatLookLikeBooleansOrNumbersStayStrings() {
		val dir = Files.createTempDirectory("rules")
		dir.resolve("a.yml").writeText("id: no\nprovenance:\n  - url: 1.20\n")
		assertEquals(listOf("no" to "1.20"), VerifyRules.sources(dir))
	}
}
