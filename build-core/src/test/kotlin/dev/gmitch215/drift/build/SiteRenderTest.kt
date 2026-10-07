package dev.gmitch215.drift.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun sampleJson(): String {
	fun cell(target: String, version: String, lines: String) =
		"""{"hash":"x","lines":[$lines],"status":"ok","target":"$target","version":"$version"}"""

	fun probe(id: String, classification: String, divergent: Boolean, extra: String) = """
		{"cells":[
		${cell("jvm", "1.0", "\"a = 1\", \"b = <2>\"")},
		${cell(
		"wasm",
		"1.0",
		if (divergent) "\"a = 1\", \"b = 3\\u0085\"" else "\"a = 1\", \"b = <2>\"",
	)},
		${cell(
		"wasm-x",
		"1.0",
		if (divergent) "\"a = 1\", \"b = 3\\u0085\"" else "\"a = 1\", \"b = <2>\"",
	)}
		],
		"classification":{"basis":"verified","causes":["a","c"],"id":"$id",
		"classification":"$classification","note":"Why <it> differs.","references":[
		{"quote":"It is \"unspecified\".","url":"https://example.org/doc?a=1&b=2"}],
		"issues":[{"id":"KT-1","state":"Open","summary":"Sum & more"}]$extra},
		"divergence":{"changedAcrossVersions":[],"divergent":$divergent,"versions":[
		{"divergent":$divergent,"groups":[${
		if (divergent) "[\"jvm\"],[\"wasm\",\"wasm-x\"]" else "[\"jvm\",\"wasm\",\"wasm-x\"]"
	}],"version":"1.0"}]},
		"id":"$id","question":"What is b?","source":"line(\"b\", x)\n\tline(\"c\", \"\\u00a0\")"}
	""".trimIndent()

	return """{"axes":{"versions":["1.0"]},"schema":2,"probes":[
		${probe("kotlin.a.same", "documented", false, "")},
		${probe(
		"kotlin.b.split",
		"unclassified",
		true,
		",\"searched\":\"Searched <here>.\",\"repro\":\"atlas/repros/kotlin.b.split.md\"",
	)}
	]}
""".trimIndent()
}

class SiteRenderTest {
	private val dataset = SiteData.dataset(sampleJson())
	private val columns = listOf(SiteColumn("jvm", "A JVM <21>"))
	private val files = mapOf(("jvm" to "1.0") to "jvm.txt", ("wasm" to "1.0") to "wasm.txt")

	private fun page(id: String, repro: String? = null) = SiteRender.probe(
		dataset.probes.first { it.id == id },
		columns,
		"2026-01-02",
		files,
		repro,
	)

	@Test
	fun escapesMarkupAndMakesInvisibleCharactersVisible() {
		assertEquals("a&lt;b&gt;&amp;&quot;", SiteRender.escape("a<b>&\""))
		assertEquals(
			"a\\u0085b\\u00a0c\\u200bd\ne\tf",
			SiteRender.visible("a\u0085b\u00a0c\u200bd\ne\tf"),
		)
		assertEquals("\\u00e9x", SiteRender.ascii("\u00e9x"))
		assertEquals("\ud83d\ude00", SiteRender.visible("\ud83d\ude00"))
	}

	@Test
	fun probePageCarriesSourceResultsClassificationAndIssues() {
		val html = page("kotlin.b.split")
		assertTrue("<title>kotlin.b.split | Drift Atlas</title>" in html)
		assertTrue("line(&quot;b&quot;, x)\n    line(&quot;c&quot;, &quot;\\u00a0&quot;)" in html, html)
		assertTrue("2 different results; 1 of 2 lines differ." in html)
		assertTrue("<mark>b = 3\\u0085</mark>" in html)
		assertTrue("<tr class=\"differs\"><th scope=\"row\">jvm</th><td>A (differs from group B)" in html)
		assertFalse("<tr class=\"differs\"><th scope=\"row\">wasm</th>" in html)
		assertTrue("<a href=\"../data/transcripts/wasm.txt\">file</a>" in html)
		assertTrue("<strong>unclassified</strong> (basis: verified)" in html)
		assertTrue("causes: documented difference, Kotlin issue" in html)
		assertTrue("No explanation was found in the sources searched." in html)
		assertTrue("<p>It is &quot;unspecified&quot;.</p>" in html)
		assertTrue("<a href=\"https://example.org/doc?a=1&amp;b=2\">" in html)
		assertTrue("<a href=\"https://youtrack.jetbrains.com/issue/KT-1\">KT-1</a>" in html)
		assertTrue("<td>Open</td>" in html)
		assertTrue("Tracker state on 2026-01-02" in html)
		assertTrue("Searched &lt;here&gt;." in html)
		assertTrue("<li><strong>jvm</strong>: A JVM &lt;21&gt;</li>" in html)
		assertTrue("<h2>Kotlin versions measured</h2>\n<p>1.0</p>" in html)
	}

	@Test
	fun identicalProbesSaySoAndHaveNoHighlight() {
		val html = page("kotlin.a.same")
		assertTrue("Identical on every column." in html)
		assertFalse("class=\"differs\"" in html)
		assertFalse("<mark>" in html)
		assertTrue("<strong>documented</strong>" in html)
	}

	@Test
	fun reproDraftIsLabelledAsNotFiled() {
		val html = page("kotlin.b.split", "Draft for human review. Not filed.\n\nbody <x>")
		assertTrue("<strong>Draft for human review; not filed.</strong>" in html)
		assertTrue("<pre>Draft for human review. Not filed.\n\nbody &lt;x&gt;</pre>" in html)
		assertFalse("Repro draft" in page("kotlin.a.same"))
	}

	@Test
	fun indexListsEveryProbeColumnAndCount() {
		val html = SiteRender.index(dataset, columns, "2026-01-02", files)
		assertTrue("<a href=\"kotlin.a.same.html\">kotlin.a.same</a>" in html)
		assertTrue("<td>1.0</td><td>KT-1</td>" in html)
		assertTrue("<th scope=\"row\">unclassified</th><td>1</td><td>1</td>" in html)
		assertTrue("<th scope=\"row\">documented</th><td>1</td><td>0</td>" in html)
		assertTrue("2 Kotlin probes, 3 measured columns" in html)
		assertTrue("<a href=\"../data/transcripts/jvm.txt\">transcript</a>" in html)
		assertTrue("href=\"../\"" in html)
	}

	@Test
	fun everyPageIsSelfContainedAndAccessible() {
		val pages = listOf(
			page("kotlin.b.split", "x"),
			page("kotlin.a.same"),
			SiteRender.index(dataset, columns, "d", files),
			SiteRender.notFound(),
		)
		for (html in pages) {
			assertTrue(html.startsWith("<!doctype html>\n<html lang=\"en\">"))
			assertTrue("<meta name=\"viewport\"" in html)
			assertTrue("<a class=\"skip\" href=\"#main\">" in html)
			assertTrue("<main id=\"main\">" in html)
			assertTrue("<h1>" in html)
			assertFalse("<script" in html)
			assertFalse("<img" in html || "<link" in html || "src=" in html || "@import" in html)
			assertFalse(Regex("url\\(").containsMatchIn(html))
			assertEquals(1, Regex("<h1>").findAll(html).count())
			assertTrue(html.endsWith("</html>\n"))
		}
	}

	@Test
	fun everyPageLinksBackToTheDirectoryAboveTheSite() {
		assertTrue("<a href=\"../../\">Drift</a>" in page("kotlin.a.same"))
		assertTrue("<a href=\"../../\">Drift</a>" in SiteRender.index(dataset, columns, "d", files))
		assertTrue("<a href=\"./../\">Drift</a>" in SiteRender.notFound())
	}

	@Test
	fun malformedDatasetsAreRefused() {
		for (bad in listOf("[]", "{\"schema\":1}", "{\"schema\":2,\"probes\":[]}")) {
			val e = kotlin.runCatching { SiteData.dataset(bad) }.exceptionOrNull()
			assertTrue(e is SiteDataException, bad)
		}
	}

	@Test
	fun columnDescriptionsParse() {
		val list = SiteData.columns("schema: 1\ncolumns:\n  - target: a\n    how: b\n", "c.yml")
		assertEquals(listOf("a"), list.map { it.target })
		assertEquals("b", list.single().how)
	}
}
