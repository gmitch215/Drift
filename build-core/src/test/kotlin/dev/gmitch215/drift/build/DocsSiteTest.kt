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
            "<nav><!-- nav --></nav><!-- guides --><!-- hero -->" +
            "<main><!-- readme --></main><!-- pager -->"
        val icons = root.resolve("assets").also {
            it.mkdirs()
            for (name in listOf("drift.png", "drift_128.png", "drift.ico", "docs-render.js")) {
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
                    "and [more](#start-here).\n\n## What It Looks At\n\n## Install and Run\n\n" +
                    "## Try It\n",
            )
            root.resolve("docs").mkdirs()
            for (page in DocsSiteBuilder.PAGES.filter { it.guide }) {
                root.resolve(page.source).writeText("# ${page.title}\n\n## Part\n")
            }
            root.resolve("docs/install.md").writeText(
                "# Install\n\nSee [usage](../ADVANCED_USAGE.md#run-it), " +
                    "[limits](limits.md#part), " +
                    "[home](../README.md) and [manual](../MANUAL.md).\n",
            )
            root.resolve("docs/manual").mkdirs()
            root.resolve("docs/manual/home.png").writeBytes(byteArrayOf(7, 7))
            root.resolve("MANUAL.md").writeText(
                "# Studio Manual\n\n## Home\n\n![Home](docs/manual/home.png)\n",
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
                "index.html", "docs.html", "why.html", "install.html", "try-it.html",
                "portability-atlas.html", "architecture.html", "shared-code.html", "limits.html",
                "manual.html", "advanced-usage.html", "technical-report.html",
                "docs/manual/home.png", "drift.png", "drift_128.png", "drift.ico",
                "docs-render.js",
                "atlas/index.html", "engine/index.html", ".nojekyll", "CNAME",
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
    fun theFrontPageLeadsWithTheOnboardingPathInOrder() {
        val r = repo()
        r.assemble()
        val front = r.html("index.html")
        val path = front.substringAfter("<ol class=\"steps\">").substringBefore("</ol>")
        val hrefs = Regex("href=\"([^\"]+)\"").findAll(path).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "index.html#what-it-looks-at",
                "install.html",
                "try-it.html",
                "advanced-usage.html",
                "technical-report.html",
            ),
            hrefs,
        )
        assertFalse("<ol class=\"steps\">" in r.html("technical-report.html"))
    }

    @Test
    fun aStepThatPointsAtAMissingHeadingFailsTheBuild() {
        val r = repo()
        r.root.resolve("README.md").writeText(
            r.root.resolve("README.md").readText().replace("## What It Looks At", "## Looks"),
        )
        failure(r, "Learn", "index.html#what-it-looks-at", "no such heading")
    }

    @Test
    fun everyPageLinksToItsNeighboursInReadingOrder() {
        val r = repo()
        r.assemble()
        val front = r.html("index.html")
        assertFalse("rel=\"prev\"" in front)
        assertTrue("href=\"docs.html\" rel=\"next\"" in front)
        val limits = r.html("limits.html")
        assertTrue("href=\"shared-code.html\" rel=\"prev\"" in limits)
        assertTrue("href=\"manual.html\" rel=\"next\"" in limits)
        val manual = r.html("manual.html")
        assertTrue("href=\"limits.html\" rel=\"prev\"" in manual)
        assertTrue("href=\"advanced-usage.html\" rel=\"next\"" in manual)
        val usage = r.html("advanced-usage.html")
        assertTrue("href=\"manual.html\" rel=\"prev\"" in usage)
        assertTrue("href=\"technical-report.html\" rel=\"next\"" in usage)
        val report = r.html("technical-report.html")
        assertTrue("href=\"advanced-usage.html\" rel=\"prev\"" in report)
        assertFalse("rel=\"next\"" in report)
    }

    @Test
    fun aTemplateWithoutAPlaceholderFailsAndNamesIt() {
        val r = repo()
        for (slot in listOf("title", "description", "nav", "guides", "hero", "pager", "readme")) {
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
        for (label in listOf("Guides", "Manual", "Usage", "Report", "Atlas", "Engine", "GitHub")) {
            assertTrue(">$label</a>" in usage, label)
        }
        assertTrue("<a href=\"atlas/#atlas\">Atlas</a>" in usage)
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
    fun theManualRendersAsItsOwnPageWithItsImagesAndANavEntry() {
        val r = repo()
        r.assemble()
        val manual = r.html("manual.html")
        assertTrue("<title>Studio Manual - Drift</title>" in manual, manual)
        assertTrue("<img src=\"docs/manual/home.png\" alt=\"Home\" />" in manual)
        assertEquals(
            listOf<Byte>(7, 7),
            r.out.resolve("docs/manual/home.png").readBytes().toList(),
        )
        assertTrue("<a href=\"manual.html\" aria-current=\"page\">Manual</a>" in manual)
        assertTrue("<a href=\"manual.html\">Manual</a>" in r.html("index.html"))
        assertEquals(0, SiteLinks.scan(r.out).broken.size)
        r.root.resolve("docs/manual/home.png").delete()
        failure(r, "MANUAL.md", "docs/manual/home.png", "not a repository file")
    }

    @Test
    fun guidesRenderAsPagesWithLinksResolvedFromTheirOwnFolder() {
        val r = repo()
        r.assemble()
        val install = r.html("install.html")
        assertTrue("<title>Install - Drift</title>" in install, install)
        assertTrue("<a href=\"advanced-usage.html#run-it\">usage</a>" in install, install)
        assertTrue("<a href=\"limits.html#part\">limits</a>" in install)
        assertTrue("<a href=\"index.html\">home</a>" in install)
        assertTrue("<a href=\"manual.html\">manual</a>" in install)
        assertEquals(0, SiteLinks.scan(r.out).broken.size)
    }

    @Test
    fun guidePagesCarryTheGuideListAndTheOtherPagesDoNot() {
        val r = repo()
        r.assemble()
        val guide = r.html("limits.html")
        val list = guide.substringAfter("<div class=\"guides\">").substringBefore("</div>")
        val hrefs = Regex("href=\"([^\"]+)\"").findAll(list).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "docs.html",
                "why.html",
                "install.html",
                "try-it.html",
                "portability-atlas.html",
                "architecture.html",
                "shared-code.html",
                "limits.html",
            ),
            hrefs,
        )
        assertTrue("<a href=\"limits.html\" aria-current=\"page\">Limits</a>" in list, list)
        assertTrue("<a href=\"docs.html\" aria-current=\"page\">Guides</a>" in guide)
        assertFalse("class=\"guides\"" in r.html("advanced-usage.html"))
        assertFalse("class=\"guides\"" in r.html("index.html"))
    }

    @Test
    fun aGuideLinkToAMissingFileOrHeadingFailsTheBuild() {
        val r = repo()
        val limits = r.root.resolve("docs/limits.md")
        limits.writeText("# Limits\n\n## Part\n\n[x](nope.md)\n")
        failure(r, "docs/limits.md", "nope.md", "not a file or directory")
        limits.writeText("# Limits\n\n## Part\n\n[x](install.md#absent)\n")
        failure(r, "docs/limits.md", "docs/install.md#absent", "no such heading")
        limits.writeText("# Limits\n\n## Part\n\n[x](../../outside.md)\n")
        failure(r, "outside the repository")
        limits.delete()
        failure(r, "docs/limits.md", "does not exist")
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
        r.icons.resolve("drift.ico").writeBytes(byteArrayOf(1))
        r.icons.resolve("docs-render.js").delete()
        failure(r, "docs-render.js")
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
        assertTrue("<h2 id=\"try-it\">Try It</h2>" in front)
        assertTrue("href=\"install.html\"" in front && "href=\"limits.html\"" in front)
        val install = out.resolve("install.html").readText()
        assertTrue("href=\"advanced-usage.html#install-and-uninstall\"" in install)
        assertTrue("<h2 id=\"build-from-source\">" in install)
        val architecture = out.resolve("architecture.html").readText()
        assertTrue("<code class=\"language-mermaid\">flowchart TD" in architecture)
        assertTrue(out.resolve("docs-render.js").isFile)
        val hrefs = Regex("href=\"[^\"]*\"").findAll(front).joinToString { it.value }
        assertFalse("ADVANCED_USAGE.md" in hrefs)
        assertTrue("<h2 id=\"command-index\">" in out.resolve("advanced-usage.html").readText())
        val manual = out.resolve("manual.html").readText()
        assertTrue("<h2 id=\"retaking-screenshots\">" in manual)
        val shots = repo.resolve("docs/manual").listFiles { f -> f.extension == "png" }!!
        assertTrue(shots.size >= 12, shots.size.toString())
        for (shot in shots) {
            assertTrue("docs/manual/${shot.name}" in manual, shot.name)
            assertTrue(out.resolve("docs/manual/${shot.name}").isFile, shot.name)
        }
        for (page in DocsSiteBuilder.PAGES) {
            val html = out.resolve(page.file).readText()
            assertFalse("<!--" in html, page.file)
            if (!page.guide) assertTrue("<table>" in html && "language-" in html, page.file)
        }
    }
}
