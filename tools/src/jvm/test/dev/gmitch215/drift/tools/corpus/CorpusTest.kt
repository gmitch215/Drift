package dev.gmitch215.drift.tools.corpus

import dev.gmitch215.drift.triage.Via
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorpusTest {
    private val login = "octo-reporter"
    private val token = "gh" + "p_" + "Qw3".repeat(12)

    private fun manifest(cap: Int = 1000, cutoff: String = "2026-10-13") = CorpusManifest.parse(
        """
        schema: 1
        cutoff: $cutoff
        cap: $cap
        salt: test-salt
        repos:
          - repo: Zed/second
            host: github
            stratum: small
            form_policy: none
            why: test
          - repo: acme/first
            host: github
            stratum: large
            form_policy: forms-strict
            why: test
        """.trimIndent(),
    )

    private fun raw(n: Int, created: String = "2026-01-01T00:00:00Z", body: String = "body $n") =
        RawIssue(n, created, "closed", "title $n", login, body)

    private fun repo(m: CorpusManifest, name: String) = m.repos.first { it.repo == name }

    /** Newest first, pages of any size, optionally failing on chosen calls. */
    private class Fake(
        private val issues: List<RawIssue>,
        private val failures: (calls: Int, size: Int) -> Boolean = { _, _ -> false },
    ) : PageSource {
        var calls = 0
        val sizes = mutableListOf<Int>()

        override fun page(repo: CorpusRepo, cursor: String?, size: Int): Page {
            calls++
            sizes += size
            if (failures(calls, size)) throw SourceException("boom")
            val from = cursor?.toInt() ?: 0
            val to = minOf(from + size, issues.size)
            return Page(issues.subList(from, to), if (to < issues.size) to.toString() else null)
        }
    }

    private fun tmp(): Path = Files.createTempDirectory("corpus-test")

    private fun build(m: CorpusManifest, source: PageSource, dir: Path = tmp()): CorpusBuild =
        CorpusBuild(m, source, dir.resolve("work"), pause = {})

    // #region manifest
    @Test
    fun theCommittedManifestParsesAndEveryRepoIsFetchable() {
        val m = CorpusManifest.parse(Path.of("../triage/corpus.yml").readText())
        assertEquals(25000, m.cap)
        assertEquals(78765569L, m.asset?.bytes)
        assertEquals(64, m.asset?.sha256?.length)
        assertTrue(m.repos.size in 40..50, "repos: ${m.repos.size}")
        assertEquals(setOf("github", "gitlab"), m.repos.map { it.host }.toSet())
        assertEquals(setOf("large", "small"), m.repos.map { it.stratum }.toSet())
        assertTrue(m.repos.any { it.repo == "gmitch215/MobChip" })
        assertTrue(m.repos.any { it.repo == "datatags/MobChipLite" })
        assertTrue(m.repos.any { it.hasForms } && m.repos.any { !it.hasForms })
    }

    @Test
    fun aBadManifestIsRefusedWithTheReason() {
        val ok = "schema: 1\ncutoff: 2026-10-13\ncap: 5\nsalt: s\n"
        val repo = "  - repo: a/b\n    host: github\n    stratum: large\n" +
            "    form_policy: none\n    why: w\n"
        fun parse(text: String) = assertFailsWith<IllegalArgumentException> {
            CorpusManifest.parse(text)
        }.message.orEmpty()
        assertContains(parse("schema: 2\n"), "schema")
        assertContains(parse(ok.replace("2026-10-13", "yesterday") + "repos:\n$repo"), "cutoff")
        assertContains(parse(ok.replace("cap: 5", "cap: 0") + "repos:\n$repo"), "cap")
        assertContains(parse(ok + "repos:\n" + repo.replace("github", "gitea")), "host")
        assertContains(parse(ok + "repos:\n" + repo.replace("large", "huge")), "stratum")
        assertContains(parse(ok + "repos:\n" + repo.replace("none", "templates")), "form_policy")
        assertContains(parse(ok + "repos:\n" + repo.replace("a/b", "ab")), "owner/name")
        assertContains(parse(ok + "repos:\n" + repo + repo.replace("a/b", "A/B")), "duplicate")
        assertContains(parse(ok + "repos: []\n"), "no repos")
        assertContains(parse(ok + "repos:\n"), "invalid YAML")
        assertContains(parse("- a\n"), "not a mapping")
        val pinned = "asset:\n  url: https://example.test/c.zst\n  sha256: ab\n  bytes: 1\n"
        assertContains(parse(ok + pinned + "repos:\n$repo"), "sha256")
    }

    @Test
    fun anAssetEntryIsReadWhenPinned() {
        val sha = "a".repeat(64)
        val text = "schema: 1\ncutoff: 2026-10-13\ncap: 5\nsalt: s\nasset:\n" +
            "  url: https://example.test/c.zst\n  sha256: $sha\n  bytes: '12'\n" +
            "repos:\n  - repo: a/b\n    host: github\n    stratum: large\n" +
            "    form_policy: none\n    why: w\n"
        val asset = CorpusAsset("https://example.test/c.zst", sha, 12)
        assertEquals(asset, CorpusManifest.parse(text).asset)
        assertNull(manifest().asset)
    }
    // #endregion

    // #region redaction and hashing
    @Test
    fun aLoginIsHashedWithTheSaltAndNeverStored() {
        val a = CorpusClean("salt-one")
        val b = CorpusClean("salt-two")
        val h = a.user(login)
        assertEquals(h, a.user("@" + login.uppercase()))
        assertTrue(Regex("u[0-9a-f]{12}").matches(h), h)
        assertNotEquals(h, b.user(login))
        assertNotEquals(a.user("someone-else"), h)
        val issue = a.issue("a/b", raw(1, body = "ping @$login and @someone-else"))
        assertEquals(h, issue.author)
        assertFalse(issue.body.contains(login))
        assertContains(issue.body, "@$h")
    }

    @Test
    fun secretsAddressesAndLongLinesAreScrubbedNotDropped() {
        val clean = CorpusClean("s")
        val prose = "word ".repeat(300).trim()
        val body = listOf(
            "token $token and mail me at someone@private-corp.io from 10.20.30.40",
            "```java",
            "@Override",
            "```",
            prose,
            "tail @$login",
        ).joinToString("\r\n")
        val out = clean.body(body)
        assertFalse(out.contains(token))
        assertFalse(out.contains("someone@"))
        assertFalse(out.contains("10.20.30.40"))
        assertFalse(out.contains(login))
        assertContains(out, "@Override")
        assertFalse(out.contains("\r"))
        assertEquals(300, Regex("word").findAll(out).count())
        assertTrue(out.lines().all { it.length <= 500 })
    }

    @Test
    fun aHugeBodyIsCutAtALineAndATitleIsOneLine() {
        val clean = CorpusClean("s")
        val line = "x".repeat(99)
        val huge = (1..4000).joinToString("\n") { line }
        assertTrue(clean.body(huge).length <= dev.gmitch215.drift.triage.Triage.MAX_BODY)
        assertEquals("a b", clean.title("  a\n b  "))
    }
    // #endregion

    // #region sources
    private val node = """
        {"data":{"repository":{"issues":{"pageInfo":{"hasNextPage":true,"endCursor":"C1"},
        "nodes":[{"number":7,"createdAt":"2026-02-03T04:05:06Z","state":"CLOSED","title":"t",
        "body":null,"author":null},{"number":6,"createdAt":"2026-01-01T00:00:00Z","state":"OPEN",
        "title":"u","body":"b","author":{"login":"x"}}]}}}}
    """.trimIndent().replace("\n", "")

    @Test
    fun theGithubResponseIsParsedAndAskedForByCursor() {
        val page = GithubSource.parse(node)
        assertEquals("C1", page.next)
        assertEquals(listOf(7, 6), page.issues.map { it.number })
        assertEquals("closed", page.issues[0].state)
        assertEquals("", page.issues[0].body)
        assertNull(page.issues[0].author)
        assertEquals("x", page.issues[1].author)
        val seen = mutableListOf<List<String>>()
        val source = GithubSource { args ->
            seen += args
            node.replace("true", "false")
        }
        val m = manifest()
        assertNull(source.page(repo(m, "acme/first"), null, 50).next)
        source.page(repo(m, "acme/first"), "C0", 25)
        assertFalse(seen[0].any { it.startsWith("cursor=") })
        assertContains(seen[1], "cursor=C0")
        assertContains(seen[1], "owner=acme")
        assertContains(seen[1], "name=first")
        assertContains(seen[1], "size=25")
        assertContains(seen[0].joinToString(" "), "direction: DESC")
        assertFailsWith<SourceException> { GithubSource.parse("{\"errors\":[{\"type\":\"X\"}]}") }
        assertFailsWith<SourceException> { GithubSource.parse("{\"data\":{}}") }
    }

    @Test
    fun theGitlabResponseIsPagedByNumber() {
        val json = "[{\"iid\":3,\"created_at\":\"2026-01-01T00:00:00Z\",\"state\":\"opened\"," +
            "\"title\":\"t\",\"description\":null,\"author\":{\"username\":\"u\"}}]"
        val full = GitlabSource.parse(json, 1, 4)
        assertEquals("5", full.next)
        assertEquals("open", full.issues[0].state)
        assertNull(GitlabSource.parse(json, 2, 4).next)
        val calls = mutableListOf<List<String>>()
        val source = GitlabSource { args ->
            calls += args
            json
        }
        val gl = CorpusRepo("gitlab-org/gitlab", "gitlab", "large", "md-blank", "w")
        source.page(gl, "3", 100)
        assertEquals("glab", calls[0][0])
        assertContains(calls[0][2], "projects/gitlab-org%2Fgitlab/issues")
        assertContains(calls[0][2], "page=3")
        assertContains(calls[0][2], "per_page=100")
        assertFailsWith<SourceException> { GitlabSource.parse("{}", 1, 1) }
    }

    @Test
    fun aHostGoesToItsOwnSource() {
        val hits = mutableListOf<String>()
        val source = HostSource(
            { r, _, _ -> hits.add("gh " + r.repo).let { Page(emptyList(), null) } },
            { r, _, _ -> hits.add("gl " + r.repo).let { Page(emptyList(), null) } },
        )
        source.page(CorpusRepo("a/b", "github", "large", "none", "w"), null, 1)
        source.page(CorpusRepo("c/d", "gitlab", "large", "none", "w"), null, 1)
        assertFailsWith<SourceException> {
            source.page(CorpusRepo("e/f", "gitea", "large", "none", "w"), null, 1)
        }
        assertEquals(listOf("gh a/b", "gl c/d"), hits)
    }

    @Test
    fun aFailedCommandIsReportedWithItsStderr() {
        val failing = listOf("sh", "-c", "echo no >&2; exit 3")
        val e = assertFailsWith<SourceException> { ProcessShell.run(failing) }
        assertContains(e.message.orEmpty(), "exited 3")
        assertContains(e.message.orEmpty(), "no")
        assertEquals("out\n", ProcessShell.run(listOf("sh", "-c", "echo out")))
    }
    // #endregion

    // #region build
    private fun issues(n: Int) = (n downTo 1).map { raw(it) }

    @Test
    fun pagesAreWalkedToTheEndAndTheCapKeepsTheNewest() {
        val m = manifest(cap = 120)
        val all = Fake(issues(250))
        val b = build(m, all)
        assertEquals(120, b.fetch(repo(m, "acme/first")))
        assertEquals(2, all.calls)
        b.fetch(repo(m, "Zed/second"))
        val json = tmp().resolve("c.json")
        val summary = b.assemble(json)
        assertEquals(120, summary.counts.getValue("acme/first"))
        val kept = CorpusReader.use(json) { s ->
            s.filter { it.repo == "acme/first" }.map { it.number }.toList()
        }
        assertEquals((131..250).toList(), kept)
    }

    @Test
    fun anIssueCreatedAfterTheCutoffIsSkipped() {
        val m = manifest(cutoff = "2026-06-30")
        val list = listOf(raw(3, "2026-07-01T00:00:00Z"), raw(2, "2026-06-30T23:59:59Z"), raw(1))
        val b = build(m, Fake(list))
        assertEquals(2, b.fetch(repo(m, "acme/first")))
    }

    private fun full(m: CorpusManifest, source: () -> PageSource, dir: Path): CorpusSummary {
        val b = build(m, source(), dir)
        b.fetchAll()
        return b.assemble(dir.resolve("c.json"))
    }

    @Test
    fun theFileIsSortedCanonicalAndTheSameBytesComeBackForTheSameInput() {
        val m = manifest()
        val shuffled = { Fake((1..130).map { raw(it) }.shuffled(java.util.Random(7))) }
        val ordered = { Fake((1..130).map { raw(it) }.sortedByDescending { it.number }) }
        val d1 = tmp()
        val d2 = tmp()
        val s1 = full(m, ordered, d1)
        val s2 = full(m, ordered, d2)
        assertContentEquals(d1.resolve("c.json").readBytes(), d2.resolve("c.json").readBytes())
        assertEquals(s1.jsonSha256, s2.jsonSha256)
        assertEquals(CorpusBuild.sha256(d1.resolve("c.json")), s1.jsonSha256)
        val d3 = tmp()
        assertEquals(s1.jsonSha256, full(m, shuffled, d3).jsonSha256)
        val read = CorpusReader.use(d1.resolve("c.json")) { it.toList() }
        assertEquals(260, read.size)
        assertEquals(listOf("acme/first", "Zed/second"), read.map { it.repo }.distinct())
        assertEquals((1..130).toList(), read.filter { it.repo == "acme/first" }.map { it.number })
        val text = d1.resolve("c.json").readText()
        assertTrue(text.startsWith("{\"cutoff\":\"2026-10-13\",\"issues\":[\n{\"author\""))
        assertTrue(text.endsWith("]}\n"))
        assertContains(text, "\"schema\":1,\"sources\":[{\"count\":130,")
        assertFalse(text.contains(login))
    }

    @Test
    fun aStoppedRunResumesAtTheLastPageAndGivesTheSameFile() {
        val m = manifest()
        val list = issues(350)
        val whole = tmp()
        val sWhole = full(m, { Fake(list) }, whole)
        val dir = tmp()
        val first = build(m, Fake(list) { calls, _ -> calls > 2 }, dir)
        assertFailsWith<SourceException> { first.fetch(repo(m, "acme/first")) }
        val state = dir.resolve("work").resolve("acme_first__state").readText()
        assertContains(state, "count=200")
        assertContains(state, "done=false")
        val resumed = Fake(list)
        val second = build(m, resumed, dir)
        assertEquals(350, second.fetch(repo(m, "acme/first")))
        assertEquals(2, resumed.calls)
        second.fetch(repo(m, "Zed/second"))
        assertEquals(sWhole.jsonSha256, second.assemble(dir.resolve("c.json")).jsonSha256)
    }

    @Test
    fun aHalfWrittenPageIsDiscardedOnResume() {
        val m = manifest()
        val dir = tmp()
        val list = issues(150)
        val b = build(m, Fake(list) { calls, _ -> calls > 1 }, dir)
        assertFailsWith<SourceException> { b.fetch(repo(m, "acme/first")) }
        val data = dir.resolve("work").resolve("acme_first__jsonl")
        Files.writeString(data, "{\"torn\":", java.nio.file.StandardOpenOption.APPEND)
        val again = build(m, Fake(list), dir)
        assertEquals(150, again.fetch(repo(m, "acme/first")))
        assertEquals(150, data.readText().lines().count { it.isNotEmpty() })
        assertFalse(data.readText().contains("torn"))
    }

    @Test
    fun aFailingPageIsRetriedAtASmallerSize() {
        val m = manifest()
        val source = Fake(issues(130)) { _, size -> size > 30 }
        val log = mutableListOf<String>()
        val b = CorpusBuild(m, source, tmp().resolve("work"), log::add) {}
        assertEquals(130, b.fetch(repo(m, "acme/first")))
        assertEquals(listOf(100, 50, 25), source.sizes.take(3))
        assertTrue(log.any { it.contains("attempt 1 failed") })
    }

    @Test
    fun aPageThatNeverWorksGivesUpAndLeavesTheStateAlone() {
        val m = manifest()
        val dir = tmp()
        val b = build(m, Fake(issues(10)) { _, _ -> true }, dir)
        assertFailsWith<SourceException> { b.fetch(repo(m, "acme/first")) }
        assertFalse(dir.resolve("work").resolve("acme_first__state").exists())
        assertFailsWith<IllegalStateException> { b.assemble(dir.resolve("c.json")) }
    }

    @Test
    fun aMissingWorkFileWithACountIsAnError() {
        val m = manifest()
        val dir = tmp()
        val b = build(m, Fake(issues(150)) { calls, _ -> calls > 1 }, dir)
        assertFailsWith<SourceException> { b.fetch(repo(m, "acme/first")) }
        Files.delete(dir.resolve("work").resolve("acme_first__jsonl"))
        val retry = build(m, Fake(issues(150)), dir)
        assertFailsWith<IOException> { retry.fetch(repo(m, "acme/first")) }
    }

    private fun zstd(): Boolean = try {
        ProcessBuilder("zstd", "-V").start().waitFor() == 0
    } catch (e: IOException) {
        false
    }

    @Test
    fun theCorpusCompressesWithZstdAndReadsBack() {
        if (!zstd()) return
        val m = manifest()
        val dir = tmp()
        val summary = full(m, { Fake(issues(40)) }, dir)
        val zst = dir.resolve("c.json.zst")
        val done = CorpusBuild.compress(summary, dir.resolve("c.json"), zst)
        assertEquals(CorpusBuild.sha256(zst), done.zstSha256)
        assertEquals(Files.size(zst), done.zstBytes)
        assertTrue(done.zstBytes!! < done.jsonBytes)
        val back = CorpusReader.use(zst) { it.map { i -> i.repo + i.number }.toList() }
        val plain = CorpusReader.use(dir.resolve("c.json")) {
            it.map { i -> i.repo + i.number }.toList()
        }
        assertEquals(plain, back)
        assertEquals(80, back.size)
        val j = done.toJson().toString()
        assertContains(j, "total")
    }
    // #endregion

    // #region download
    @Test
    fun aDownloadMustMatchThePinnedHashAndSize() {
        val data = "corpus bytes".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        val asset = CorpusAsset("https://example.test/c.zst", sha, data.size.toLong())
        val dir = tmp()
        val out = dir.resolve("sub").resolve("c.zst")
        var opened = 0
        val open = { _: String ->
            opened++
            ByteArrayInputStream(data)
        }
        assertEquals(out, CorpusDownload.fetch(asset, out, open))
        assertContentEquals(data, out.readBytes())
        CorpusDownload.fetch(asset, out, open)
        assertEquals(1, opened)
        val wrong = out.resolveSibling("w.zst")
        val e = assertFailsWith<IOException> {
            CorpusDownload.fetch(asset.copy(sha256 = "0".repeat(64)), wrong, open)
        }
        assertContains(e.message.orEmpty(), "does not match")
        assertFalse(wrong.exists())
        assertFalse(out.resolveSibling("w.zst.part").exists())
        assertFailsWith<IOException> { CorpusDownload.fetch(asset.copy(bytes = 3), wrong, open) }
    }
    // #endregion

    // #region evaluation
    private val form = """
        ### Node.js version

        v18.17.0

        ### Operating system

        Ubuntu 22.04

        ### What happened?

        It crashes on Node 18.17.0 under Ubuntu 22.04 when I start it.

        ### Logs

        Error: boom
    """.trimIndent()

    private fun issue(repo: String, n: Int, body: String) =
        CorpusIssue(repo, n, "2026-01-01T00:00:00Z", "closed", "t", null, body)

    @Test
    fun theFormSectionsThatGaveTheTruthAreCutOut() {
        val truth = dev.gmitch215.drift.triage.Triage.run(form, rules = emptyList()).facts
            .filter { it.via == Via.SECTION }
        val rest = EvalTriage.withoutSections(form, truth)
        assertFalse(rest.contains("### Node.js version"))
        assertFalse(rest.contains("### Operating system"))
        assertContains(rest, "### What happened?")
        assertContains(rest, "### Logs")
        assertContains(rest, "Error: boom")
    }

    @Test
    fun aFormBodyIsScoredAgainstItsOwnFieldsOnTheRestOfTheText() {
        val m = manifest()
        val eval = EvalTriage(m)
        for (n in 1..40) {
            eval.add(issue("acme/first", n, form))
        }
        eval.add(issue("Zed/second", 1, form))
        eval.add(issue("other/repo", 1, form))
        val r = eval.result()
        assertEquals(41, r.bodies)
        val scored = r.dev.all.scored + r.heldOut.all.scored
        val correct = r.dev.all.correct + r.heldOut.all.correct
        assertTrue(scored >= 40, "scored $scored")
        assertEquals(scored, correct)
        assertTrue(r.dev.all.truth + r.heldOut.all.truth >= 40)
        assertTrue(r.dev.all.truth > 0 && r.heldOut.all.truth > 0)
        assertEquals(setOf("large"), r.dev.byStratum.keys + r.heldOut.byStratum.keys)
        val text = EvalTriage.render(r)
        assertContains(text, "== dev ==")
        assertContains(text, "== held-out ==")
        assertContains(text, "by field")
        assertContains(text, "coverage by stratum/form policy")
    }

    @Test
    fun aWrongValueOnAFormKeyIsReportedAsAMiss() {
        val wrong = form.replace("It crashes on Node 18.17.0", "It crashes on Node 20.1.0")
        val seen = mutableListOf<Triple<Int, String, Set<String>>>()
        val eval = EvalTriage(
            manifest(),
            onMiss = { i, f, wanted -> seen += Triple(i.number, f.value, wanted) },
        )
        eval.add(issue("acme/first", 9, wrong))
        assertEquals(listOf(Triple(9, "20.1.0", setOf("18.17.0"))), seen)
        val r = eval.result()
        val scored = r.dev.all.scored + r.heldOut.all.scored
        assertEquals(1L, scored - r.dev.all.correct - r.heldOut.all.correct)
    }

    @Test
    fun aBodyWithoutFormSectionsHasNoTruthAndOnlyCoverage() {
        val eval = EvalTriage(manifest())
        eval.add(issue("acme/first", 5, "it fails"))
        eval.add(issue("Zed/second", 6, form))
        val r = eval.result()
        assertEquals(0, r.dev.all.scored + r.heldOut.all.scored)
        val cov = (r.dev.coverage.values + r.heldOut.coverage.values)
        assertEquals(2, cov.sumOf { it.bodies })
        assertEquals(0L, cov.sumOf { it.forms })
    }

    @Test
    fun versionsAgreeWhenOneIsTheOtherCutAtADot() {
        assertTrue(EvalTriage.agrees("8.1", "8.1.0"))
        assertTrue(EvalTriage.agrees("7.0.100-preview.7", "7"))
        assertTrue(EvalTriage.agrees("18", "18"))
        assertFalse(EvalTriage.agrees("8.1", "8.10"))
        assertFalse(EvalTriage.agrees("5", "8.0.13"))
        assertFalse(EvalTriage.agrees("6.0.108", "7.0.100-preview.7"))
    }

    @Test
    fun theSplitIsSeededStableAndRoughlyEven() {
        val a = EvalTriage(manifest())
        val b = EvalTriage(manifest(), "another-seed")
        val devs = (1..2000).count { a.isDev("acme/first", it) }
        assertTrue(devs in 900..1100, "dev: $devs")
        assertEquals(devs, (1..2000).count { a.isDev("acme/first", it) })
        assertNotEquals(devs, (1..2000).count { b.isDev("acme/first", it) })
    }

    @Test
    fun scoresReliabilityAndProposedBasesFollowTheirDefinitions() {
        val s = Score()
        repeat(80) { s.fact(900, true) }
        repeat(20) { s.fact(900, false) }
        assertEquals(0.8, s.precision, 1e-9)
        assertEquals(0.9, s.meanConfidence, 1e-9)
        assertEquals((80 * 100 * 100 + 20 * 900 * 900) / 100.0 / 1_000_000, s.brier, 1e-9)
        assertEquals(100L, s.binScored[9])
        assertEquals(80L, s.binCorrect[9])
        assertTrue(s.recall.isNaN())
        s.truth(true)
        s.truth(false)
        assertEquals(0.5, s.recall, 1e-9)
        assertEquals(800, EvalTriage.proposed(Via.TOOL, s))
        assertNull(EvalTriage.proposed(Via.TOOL, Score().also { it.fact(500, true) }))
        assertEquals("n/a", EvalTriage.pct(Double.NaN))
        assertEquals("0.800", EvalTriage.pct(0.8))
        assertEquals("0.1234", EvalTriage.brier(0.12344))
    }

    @Test
    fun theEvalCommandCreatesTheDirectoriesOfItsOutputFiles() {
        val dir = tmp()
        full(manifest(), { Fake(issues(5)) }, dir)
        val file = dir.resolve("corpus.yml")
        file.writeText(
            "schema: 1\ncutoff: 2026-10-13\ncap: 1000\nsalt: s\nrepos:\n" +
                "  - repo: acme/first\n    host: github\n    stratum: large\n" +
                "    form_policy: forms-strict\n    why: test\n",
        )
        val report = dir.resolve("out/report.txt")
        val claims = dir.resolve("out/claims.tsv")
        val misses = dir.resolve("other/misses.tsv")
        main(
            arrayOf("eval", "$file", "${dir.resolve("c.json")}", "$report", "$claims", "$misses"),
        )
        assertTrue(report.exists() && claims.exists() && misses.exists())
    }
    // #endregion
}
