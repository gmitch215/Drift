package dev.gmitch215.drift.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocsSiteTest {
	private class Repo(val root: File) {
		val template = "<title><!-- title --></title><meta content=\"<!-- description -->\">" +
			"<nav><!-- nav --></nav><!-- hero --><main><!-- readme --></main>"
		val icons = root.resolve("assets").also {
			it.mkdirs()
			for (name in listOf("drift.png", "drift_128.png", "drift.ico")) {
				it.resolve(name).writeBytes(byteArrayOf(1, 2, 3))
			}
		}
		val atlas = root.resolve("atlas-site").also {
			it.mkdirs()
			it.resolve("index.html").writeText("<p>atlas</p>")
		}
		val engine = root.resolve("engine-site").also {
			it.mkdirs()
			it.resolve("index.html").writeText("<p>engine</p>")
		}
		val out = root.resolve("out")

		init {
			root.resolve("README.md").writeText(
				"<div align=\"center\">\n    <h1>Drift</h1>\n" +
					"    <p style=\"x\">Why it fails</p>\n" +
					"    <div align=\"center\">\n    <img src=\"b\">\n    </div>\n</div>\n" +
					"\n---\n\n" +
					"## Start Here\n\nSee [usage](./ADVANCED_USAGE.md#run-it) " +
					"and [more](#start-here).\n",
			)
			root.resolve("ADVANCED_USAGE.md").writeText(
				"# Advanced Usage\n\n## Run It\n\nBack to [the report](TECHNICAL_REPORT.md).\n",
			)
			root.resolve("TECHNICAL_REPORT.md").writeText("# Technical Report\n\nText.\n")
		}

		fun assemble(template: String = this.template) = DocsSiteBuilder.assemble(
			out,
			root,
			template,
			icons,
			atlas,
			engine,
			"drift.example.dev",
		)

		fun html(name: String) = out.resolve(name).readText()
	}

	private fun repo() = Repo(Files.createTempDirectory("docs").toFile())

	private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
		.associate {
			it.relativeTo(dir).invariantSeparatorsPath to
				MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b ->
					"%02x".format(b)
				}
		}

	private fun failure(repo: Repo, vararg expected: String) {
		val message = assertFailsWith<DocsSiteException> { repo.assemble() }.message!!
		for (part in expected) assertTrue(part in message, message)
	}

	@Test
	fun writesThePagesTheIconsTheSubsitesAndTheDeployFiles() {
		val r = repo()
		r.assemble()
		assertEquals(
			setOf(
				"index.html", "advanced-usage.html", "technical-report.html", "drift.png",
				"drift_128.png", "drift.ico", "atlas/index.html", "engine/index.html", ".nojekyll",
				"CNAME",
			),
			tree(r.out).keys,
		)
		assertEquals("drift.example.dev\n", r.html("CNAME"))
		assertEquals("", r.html(".nojekyll"))
	}

	@Test
	fun theFrontPageReplacesTheReadmeHeaderWithTheHero() {
		val r = repo()
		r.assemble()
		val html = r.html("index.html")
		assertTrue("<title>Drift</title>" in html, html)
		assertTrue("content=\"Why it fails\"" in html)
		assertTrue("<div class=\"hero\">" in html && "<h1>Drift</h1>" in html)
		assertTrue("<p class=\"lead\">Why it fails</p>" in html)
		assertTrue("<img src=\"drift.png\"" in html)
		assertFalse("align=\"center\"" in html)
		assertTrue("<h2 id=\"start-here\">Start Here</h2>" in html, html)
		assertTrue("<a href=\"#start-here\">more</a>" in html)
		assertFalse("<!--" in html)
	}

	@Test
	fun linksToTheOtherPagesBecomeSitePagesAndKeepTheirAnchor() {
		val r = repo()
		r.assemble()
		assertTrue("<a href=\"advanced-usage.html#run-it\">usage</a>" in r.html("index.html"))
		val usage = r.html("advanced-usage.html")
		assertTrue("<a href=\"technical-report.html\">the report</a>" in usage)
		assertTrue("<title>Advanced Usage - Drift</title>" in usage)
	}

	@Test
	fun linksToRepositoryPathsBecomeGitHubUrlsAndExternalOnesStay() {
		val r = repo()
		r.root.resolve("fixtures/atlas").mkdirs()
		r.root.resolve("LICENSE").writeText("MIT")
		r.root.resolve("TECHNICAL_REPORT.md").writeText(
			"# Report\n\n[license](./LICENSE) [dir](./fixtures/atlas/) " +
				"[up](fixtures/../LICENSE#top) " +
				"[web](https://example.org/a?b=1#c) [mail](mailto:a@b.c) [root](/x) [empty](#)\n",
		)
		r.assemble()
		val html = r.html("technical-report.html")
		val repo = DocsSiteBuilder.REPO
		assertTrue("href=\"$repo/blob/master/LICENSE\">license" in html, html)
		assertTrue("href=\"$repo/tree/master/fixtures/atlas\">dir" in html, html)
		assertTrue("href=\"$repo/blob/master/LICENSE#top\">up" in html, html)
		assertTrue("href=\"https://example.org/a?b=1#c\">web" in html)
		assertTrue("href=\"mailto:a@b.c\">mail" in html)
		assertTrue("href=\"/x\">root" in html)
	}

	@Test
	fun aLinkToAMissingPathOrAnEscapingPathOrAMissingHeadingFailsTheBuild() {
		val r = repo()
		r.root.resolve("TECHNICAL_REPORT.md").writeText("# R\n\n[x](./nope/file.kt)\n")
		failure(r, "TECHNICAL_REPORT.md", "./nope/file.kt", "not a file or directory")
		r.root.resolve("TECHNICAL_REPORT.md").writeText("# R\n\n[x](../outside.md)\n")
		failure(r, "outside the repository")
		r.root.resolve("TECHNICAL_REPORT.md").writeText("# R\n\n[x](./ADVANCED_USAGE.md#absent)\n")
		failure(r, "TECHNICAL_REPORT.md", "ADVANCED_USAGE.md#absent", "no such heading")
		r.root.resolve("TECHNICAL_REPORT.md").writeText("# R\n\n[x](#absent)\n")
		failure(r, "TECHNICAL_REPORT.md#absent")
	}

	@Test
	fun aTemplateWithoutAPlaceholderFailsAndNamesIt() {
		val r = repo()
		for (slot in listOf("title", "description", "nav", "hero", "readme")) {
			val broken = r.template.replace("<!-- $slot -->", "")
			val message = assertFailsWith<DocsSiteException> { r.assemble(broken) }.message!!
			assertTrue("<!-- $slot -->" in message, message)
		}
	}

	@Test
	fun theFrontPageNeedsAHeaderWithATagline() {
		val r = repo()
		r.root.resolve("README.md").writeText("## Start\n")
		failure(r, "README.md", "header")
		r.root.resolve("README.md").writeText("<div>\n<h1>Drift</h1>\n</div>\n\ntext\n")
		failure(r, "tagline")
		r.root.resolve("README.md").delete()
		failure(r, "README.md")
	}

	@Test
	fun theNavMarksTheCurrentPageAndLinksTheSubsites() {
		val r = repo()
		r.assemble()
		val front = r.html("index.html")
		assertTrue("<a class=\"brand\" href=\"index.html\" aria-current=\"page\">" in front, front)
		assertFalse("<a href=\"advanced-usage.html\" aria-current=\"page\">" in front)
		val usage = r.html("advanced-usage.html")
		assertTrue("<a href=\"advanced-usage.html\" aria-current=\"page\">Usage</a>" in usage)
		assertFalse("<a class=\"brand\" href=\"index.html\" aria-current" in usage)
		assertEquals(1, Regex("aria-current").findAll(usage.substringBefore("</nav>")).count())
		for (label in listOf("Usage", "Report", "Atlas", "Engine", "GitHub")) {
			assertTrue(">$label</a>" in usage, label)
		}
		assertTrue("<a href=\"atlas/\">Atlas</a>" in usage)
		assertTrue("<a href=\"engine/\">Engine</a>" in usage)
		assertTrue("<a href=\"${DocsSiteBuilder.REPO}\">GitHub</a>" in usage)
	}

	@Test
	fun codeIsEscapedWithItsLanguageAndTablesAreWrapped() {
		val r = repo()
		r.root.resolve("TECHNICAL_REPORT.md").writeText(
			"# R\n\n```sh\necho \"<b>\" && x\n```\n\n| a | b |\n| --- | --- |\n| 1 | `<2>` |\n\n" +
				"Plain https://example.org/x link.\n",
		)
		r.assemble()
		val html = r.html("technical-report.html")
		val code = "<pre><code class=\"language-sh\">echo &quot;&lt;b&gt;&quot; &amp;&amp; x\n" +
			"</code></pre>"
		assertTrue(code in html, html)
		assertTrue("<div class=\"table\"><table>" in html && "</table></div>" in html)
		assertTrue("<code>&lt;2&gt;</code>" in html)
		assertTrue("<a href=\"https://example.org/x\">https://example.org/x</a>" in html)
	}

	@Test
	fun relativeImagesAreCopiedToTheSameSitePath() {
		val r = repo()
		r.root.resolve("assets/shot.png").writeBytes(byteArrayOf(9, 8))
		r.root.resolve("TECHNICAL_REPORT.md").writeText(
			"# R\n\n![shot](assets/shot.png) ![web](https://example.org/i.png)\n",
		)
		r.assemble()
		val html = r.html("technical-report.html")
		assertTrue("<img src=\"assets/shot.png\" alt=\"shot\" />" in html)
		assertTrue("<img src=\"https://example.org/i.png\"" in html)
		assertEquals(listOf<Byte>(9, 8), r.out.resolve("assets/shot.png").readBytes().toList())
		r.root.resolve("assets/shot.png").delete()
		failure(r, "assets/shot.png", "not a repository file")
	}

	@Test
	fun aMissingIconIsRefusedAndTheSameInputsGiveTheSameBytes() {
		val r = repo()
		r.out.resolve("stale").mkdirs()
		r.out.resolve("stale/old.html").writeText("old")
		r.assemble()
		val first = tree(r.out)
		assertFalse("stale/old.html" in first)
		r.assemble()
		assertEquals(first, tree(r.out))
		r.icons.resolve("drift.ico").delete()
		failure(r, "drift.ico")
	}

	@Test
	fun theTaskWrapsABuildProblemInAGradleException() {
		val r = repo()
		val project = ProjectBuilder.builder().build()
		val templateFile = r.root.resolve("template.html").also { it.writeText(r.template) }
		val task = project.tasks.register("docsSite", DocsSite::class.java) {
			pages.from(r.root.resolve("README.md"))
			template.set(templateFile)
			icons.set(r.icons)
			atlas.set(r.atlas)
			engine.set(r.engine)
			domain.set("drift.example.dev")
			repository.set(r.root)
			outputDir.set(r.out)
		}.get()
		task.assemble()
		assertTrue(r.out.resolve("index.html").isFile)
		r.root.resolve("TECHNICAL_REPORT.md").writeText("[x](./gone)\n")
		assertTrue("gone" in assertFailsWith<GradleException> { task.assemble() }.message!!)
	}

	@Test
	fun theLinkScanFindsUnresolvedRelativeLinksAndSkipsTheRest() {
		val root = Files.createTempDirectory("links").toFile()
		root.resolve("a").mkdirs()
		root.resolve("a/index.html").writeText("<p>a</p>")
		root.resolve("b with space.html").writeText("<p>b</p>")
		root.resolve("c.css").writeText("x")
		root.resolve("index.html").writeText(
			"<link href='c.css'><a href=\"a/\">dir</a> <a href=\"a/index.html?x=1#y\">file</a> " +
				"<a href=\"b%20with%20space.html\">space</a> <a href=\"/c.css\">abs</a> " +
				"<a href=\"#top\">frag</a> <a href=\"https://example.org/x\">web</a> " +
				"<a href=\"//cdn.example/x\">cdn</a> <a href=\"mailto:a@b.c\">mail</a> " +
				"<a href=\"gone.html\">gone</a> <img src=\"missing.png\"> " +
				"<a href=\"../index.html\">up</a> <a href=\"c.css?a=1&amp;b=2\">query</a> " +
				"<a data-href=\"nope.html\">data</a> <a href=\"%zz\">bad</a>",
		)
		root.resolve("a/other.html").writeText(
			"<a href=\"../b%20with%20space.html\">ok</a><a href=\"nope/\">no</a>",
		)
		val scan = SiteLinks.scan(root)
		assertEquals(
			listOf(
				"a/other.html nope/",
				"index.html gone.html",
				"index.html missing.png",
				"index.html ../index.html",
				"index.html %zz",
			).sorted(),
			scan.broken.map { "${it.page} ${it.href}" }.sorted(),
		)
		assertEquals(12, scan.checked)
	}

	@Test
	fun theCheckTaskFailsOnBrokenLinksExceptInTheUncheckedTrees() {
		val root = Files.createTempDirectory("check").toFile()
		root.resolve("engine").mkdirs()
		root.resolve("index.html").writeText("<a href=\"engine/page.html\">ok</a>")
		root.resolve("engine/page.html").writeText("<a href=\"--root--.html\">dokka</a>")
		val project = ProjectBuilder.builder().build()
		val task = project.tasks.register("checkSiteLinks", CheckSiteLinks::class.java) {
			siteDir.set(root)
			unchecked.add("engine")
		}.get()
		task.check()
		root.resolve("index.html").writeText("<a href=\"nope.html\">x</a>")
		val message = assertFailsWith<GradleException> { task.check() }.message!!
		assertTrue("1 unresolved" in message && "index.html: nope.html" in message, message)
		task.unchecked.set(emptyList())
		assertTrue("2 unresolved" in assertFailsWith<GradleException> { task.check() }.message!!)
	}

	@Test
	fun theRealReadmeAndReportsRenderWithEveryLinkAndAnchorResolving() {
		val repo = File("").absoluteFile.parentFile
		assertTrue(repo.resolve("README.md").isFile, repo.path)
		val work = Files.createTempDirectory("real").toFile()
		val r = Repo(work)
		val out = work.resolve("site")
		DocsSiteBuilder.assemble(
			out = out,
			repo = repo,
			template = repo.resolve("assets/site-template.html").readText(),
			icons = repo.resolve("assets"),
			atlas = r.atlas,
			engine = r.engine,
			domain = "drift.gmitch215.dev",
		)
		val scan = SiteLinks.scan(out)
		assertEquals(emptyList(), scan.broken.map { "${it.page} ${it.href}" })
		assertTrue(scan.checked > 30, scan.checked.toString())
		val front = out.resolve("index.html").readText()
		assertTrue("href=\"advanced-usage.html\"" in front)
		assertTrue("href=\"technical-report.html" in front)
		assertTrue("<h2 id=\"install-and-run\">Install and Run</h2>" in front)
		assertTrue("<h2 id=\"the-kotlin-portability-atlas\">" in front)
		assertTrue("href=\"advanced-usage.html#install-and-uninstall\"" in front)
		val hrefs = Regex("href=\"[^\"]*\"").findAll(front).joinToString { it.value }
		assertFalse("ADVANCED_USAGE.md" in hrefs)
		assertTrue("<h2 id=\"command-index\">" in out.resolve("advanced-usage.html").readText())
		for (page in DocsSiteBuilder.PAGES) {
			val html = out.resolve(page.file).readText()
			assertFalse("<!--" in html, page.file)
			assertTrue("<table>" in html && "language-" in html, page.file)
		}
	}
}
