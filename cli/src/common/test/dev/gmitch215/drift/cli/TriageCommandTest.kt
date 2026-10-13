package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.know.Learned
import dev.gmitch215.drift.know.RuleCompiler
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.triage.Confidence
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageOptions
import dev.gmitch215.drift.triage.TriageText
import dev.gmitch215.drift.triage.Via
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriageCommandTest {
    private val fence = "`".repeat(3)
    private val body = """
        ### Node.js version

        20.11.1

        ### What happened?

        Worked on Node 18.19.0 and now fails on Node 20.11.1 with `TypeError: bad path`.
        Thanks @octocat, token ghp_abcdefghijklmnopqrstuvwxyz0123456789
        $fence
        $ node -v
        v20.11.1
        $fence
    """.trimIndent()

    private val host = FakeHost(
        canRun = false,
        files = mapOf("issue.md" to body, "/dev/stdin" to body, "empty.md" to ""),
    )

    private fun drift(args: String) = DriftCommand(host).test(args)

    @Test
    fun textIsTheDefaultAndMatchesTheEngine() {
        val result = drift("triage issue.md")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(TriageText.render(Triage.run(body)), result.stdout)
        assertTrue("tool.node.version" in result.stdout)
        assertTrue("Draft rule triage-tool-node-version-" in result.stdout)
    }

    @Test
    fun jsonIsCanonicalAndStandardInputIsRead() {
        val file = drift("triage issue.md --json")
        assertEquals(0, file.statusCode, file.stderr)
        val expected = CanonicalJson.encode(Triage.run(body).toJson()) + "\n"
        assertEquals(expected, file.stdout)
        assertEquals(expected, drift("triage - --json").stdout)
        val json = CanonicalJson.parse(file.stdout.trim()) as JsonObject
        assertEquals(JsonString(Triage.run(body).body), json.fields.getValue("body"))
        assertTrue((json.fields.getValue("facts") as JsonArray).items.isNotEmpty())
    }

    @Test
    fun secretsAndUsernamesNeverReachStandardOutput() {
        val text = drift("triage issue.md --author OctoCat").stdout
        val out = text + drift("triage issue.md --json").stdout
        assertTrue("ghp_" !in out)
        assertTrue("octocat" !in out.lowercase())
        assertTrue(Triage.hashUser("OctoCat") in text)
    }

    @Test
    fun theThresholdAndDateDecideWhetherADraftPublishes() {
        val drafts = drift("triage issue.md --json")
        assertTrue("\"disposition\":\"draft\"" in drafts.stdout)
        val published = drift("triage issue.md --json --publish-threshold 0.5 --date 2026-10-13")
        assertEquals(0, published.statusCode, published.stderr)
        assertTrue("\"disposition\":\"publish\"" in published.stdout)
        val expected = Triage.run(
            body,
            TriageOptions(Confidence(500), date = "2026-10-13"),
        )
        val draft = (CanonicalJson.parse(published.stdout.trim()) as JsonObject)
            .fields.getValue("draft") as JsonObject
        assertEquals(JsonString(expected.draft!!.id), draft.fields.getValue("id"))
        val below = drift("triage issue.md --json --publish-threshold 0.99 --date 2026-10-13")
        assertTrue("\"disposition\":\"draft\"" in below.stdout)
    }

    @Test
    fun badInputIsRefusedWithTheReason() {
        val missing = drift("triage nope.md")
        assertEquals(1, missing.statusCode)
        assertTrue("cannot read issue text: nope.md" in missing.stderr, missing.stderr)
        val bare = DriftCommand(FakeHost(canRun = false)).test("triage -")
        assertEquals(1, bare.statusCode)
        assertTrue("cannot read standard input" in bare.stderr, bare.stderr)
        for (bad in listOf("abc", "1.5", "-1", "0.1234")) {
            val r = drift("triage issue.md --publish-threshold $bad")
            assertEquals(1, r.statusCode, bad)
            assertTrue("--publish-threshold must be 0 to 1" in r.stderr, r.stderr)
        }
        for (bad in listOf("2026-13-01", "2026-1-01", "20261013", "2026-10-xx")) {
            val r = drift("triage issue.md --date $bad")
            assertEquals(1, r.statusCode, bad)
            assertTrue("--date must be yyyy-mm-dd" in r.stderr, r.stderr)
        }
    }

    @Test
    fun anEmptyReportGivesAnEmptyResult() {
        val result = drift("triage empty.md --json")
        assertEquals(0, result.statusCode, result.stderr)
        val json = CanonicalJson.parse(result.stdout.trim()) as JsonObject
        assertEquals(JsonArray(emptyList()), json.fields.getValue("facts"))
        assertNull(Triage.run("").draft)
    }

    private val date = "2026-10-13"
    private val store = "team/learned.json"
    private val draftConfidence = Triage.run(body, TriageOptions(date = date)).draft!!.confidence
    private val draftId = Triage.run(body, TriageOptions(date = date)).draft!!.id

    private fun publish(files: MemoryCaseFiles, threshold: Confidence, extra: String = "") =
        DriftCommand(host, files)
            .test(
                "triage issue.md --publish-threshold $threshold --date $date --learned team $extra",
            )

    @Test
    fun aDraftIsStoredOnlyAtOrAboveTheThreshold() {
        val below = MemoryCaseFiles()
        val under = publish(below, Confidence(draftConfidence.permille + 1))
        assertEquals(0, under.statusCode, under.stderr)
        assertTrue("$draftId: draft" in under.stdout, under.stdout)
        assertTrue(below.files.isEmpty())

        val at = MemoryCaseFiles()
        val exact = publish(at, draftConfidence)
        assertEquals(0, exact.statusCode, exact.stderr)
        assertTrue("$draftId: publish" in exact.stdout, exact.stdout)
        assertEquals(listOf(draftId), Learned.parse(at.files.getValue(store)).map { it.id })
        assertTrue("added $draftId to the learned store $store" in exact.stderr, exact.stderr)

        val above = MemoryCaseFiles()
        publish(above, Confidence(0))
        assertEquals(at.files, above.files)
    }

    @Test
    fun noThresholdNoDateOrNoStoreWritesNothing() {
        val files = MemoryCaseFiles()
        val none = DriftCommand(host, files).test("triage issue.md --date $date --learned team")
        assertEquals(0, none.statusCode, none.stderr)
        val undated = DriftCommand(host, files)
            .test("triage issue.md --publish-threshold 0 --learned team")
        assertEquals(0, undated.statusCode, undated.stderr)
        assertTrue("no --date" in undated.stdout, undated.stdout)
        val bare = DriftCommand(host, files)
            .test("triage issue.md --publish-threshold 0 --date $date")
        assertEquals(0, bare.statusCode, bare.stderr)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun publishingTwiceLeavesTheStoreAsItWas() {
        val files = MemoryCaseFiles()
        publish(files, Confidence(0))
        val once = files.files.getValue(store)
        val again = publish(files, Confidence(0))
        assertEquals(0, again.statusCode, again.stderr)
        assertEquals(once, files.files.getValue(store))
        assertEquals(1, Learned.parse(once).size)
    }

    @Test
    fun aBrokenStoreIsRefusedAndKeptAsItIs() {
        val files = MemoryCaseFiles(mutableMapOf(store to "{nope"))
        val result = publish(files, Confidence(0))
        assertEquals(1, result.statusCode)
        assertTrue("cannot read the learned store $store" in result.stderr, result.stderr)
        assertEquals("{nope", files.files.getValue(store))
    }

    private val nodeRule = RuleCompiler.compile(
        """{"schema":1,"id":"learned-node-20","title":"Node 20 breaks it","applies":["runtime"],
        |"difference":"d","symptoms":[{"text":"t","basis":"inferred"}],"mechanism":"M.",
        |"detect":{"all":[{"a":"tool.node.version","ver":"<20"},
        |{"b":"tool.node.version","ver":">=20"}]},"severity":"low",
        |"provenance":[{"url":"https://example.com/x","kind":"report","verified":"2026-10-13"}],
        |"fixtures":{"positive":[{"name":"p","a":{"attrs":{"tool.node.version":"18.19.0"}},
        |"b":{"attrs":{"tool.node.version":"20.11.1"}}}],
        |"negative":[{"name":"n","a":{"attrs":{"tool.node.version":"20.11.1"}},
        |"b":{"attrs":{"tool.node.version":"20.11.1"}}}]}}
        """.trimMargin().replace("\n", ""),
    )

    private fun capsule(label: String, node: String) =
        Capsule(label, listOf(Attribute("tool.node.version", node, "test"))).canonical()

    private val learnedHost = FakeHost(
        canRun = false,
        files = mapOf(
            "issue.md" to body,
            "green.json" to capsule("green", "18.19.0"),
            "red.json" to capsule("red", "20.11.1"),
        ),
    )

    private fun learnedFiles() = MemoryCaseFiles(mutableMapOf(store to Learned.add(null, nodeRule)))

    @Test
    fun learnedRulesReachDiagnoseUpgradeCheckAndTriage() {
        val files = learnedFiles()
        fun run(args: String) = DriftCommand(learnedHost, files).test(args)
        val check = "upgrade-check green.json --to tool.node.version=20.11.1"
        assertFalse("learned-node-20" in run(check).stdout)
        val checked = run("$check --learned team")
        assertEquals(0, checked.statusCode, checked.stderr)
        assertTrue("learned-node-20" in checked.stdout, checked.stdout)
        assertFalse("learned-node-20" in run("diagnose green.json red.json").stdout)
        val ranked = run("diagnose green.json red.json --learned team")
        assertEquals(0, ranked.statusCode, ranked.stderr)
        assertTrue("learned-node-20" in ranked.stdout, ranked.stdout)
        val body = run("triage issue.md --learned team")
        assertEquals(0, body.statusCode, body.stderr)
        assertEquals(TriageText.render(Triage.run(this.body, rules = rulesOf(files))), body.stdout)
    }

    private fun rulesOf(files: MemoryCaseFiles) =
        dev.gmitch215.drift.know.Rules.all + Learned.parse(files.files.getValue(store))

    @Test
    fun aMissingStoreAddsNoRules() {
        val result = DriftCommand(learnedHost, MemoryCaseFiles())
            .test("diagnose green.json red.json --learned nowhere")
        assertEquals(0, result.statusCode, result.stderr)
        assertFalse("learned-node-20" in result.stdout)
    }

    @Test
    fun aConfidenceTableReplacesTheStartingConfidenceOfAPath() {
        val files = MemoryCaseFiles(
            mutableMapOf(
                "t.json" to "{\"schema\":1,\"bases\":{\"pattern\":300,\"tool-output\":300}}",
                "bad.json" to "{\"schema\":1,\"bases\":{\"nope\":300}}",
                "junk.json" to "{nope",
            ),
        )
        val result = DriftCommand(host, files).test("triage issue.md --json --confidence t.json")
        assertEquals(0, result.statusCode, result.stderr)
        val low = TriageOptions(bases = mapOf(Via.PATTERN to 300, Via.TOOL to 300))
        assertEquals(CanonicalJson.encode(Triage.run(body, low).toJson()) + "\n", result.stdout)
        assertTrue(result.stdout != drift("triage issue.md --json").stdout)
        for ((file, reason) in listOf(
            "bad.json" to "unknown path: nope",
            "junk.json" to "not JSON",
        )) {
            val bad = DriftCommand(host, files).test("triage issue.md --confidence $file")
            assertEquals(1, bad.statusCode)
            assertTrue(
                "bad confidence table $file" in bad.stderr && reason in bad.stderr,
                bad.stderr,
            )
        }
        val missing = DriftCommand(host, files).test("triage issue.md --confidence none.json")
        assertTrue("cannot read confidence table: none.json" in missing.stderr, missing.stderr)
    }

    private fun corpusText(): String {
        val issues = (1..300).map { i ->
            val v = "20.${i % 9}.1"
            val text = "### Node.js version\n\n$v\n\n### What happened?\n\nIt broke on node $v"
            val json = CanonicalJson.encode(JsonString(text))
            (if (i == 1) "" else ",") + "{\"repo\":\"t/r\",\"number\":$i,\"body\":$json}"
        }
        return (listOf("{\"cutoff\":\"x\",\"issues\":[") + issues + "]").joinToString("\n")
    }

    @Test
    fun calibrateWritesATableThatTriageReads() {
        val files = MemoryCaseFiles(mutableMapOf("c.json" to corpusText()))
        val result = DriftCommand(host, files)
            .test("triage calibrate --corpus c.json --out t.json --min-facts 5")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("300 issues, split seed drift-triage-split-v1" in result.stdout, result.stdout)
        assertTrue("Brier" in result.stdout)
        val table = dev.gmitch215.drift.triage.ConfidenceTable.parse(files.files.getValue("t.json"))
        assertTrue(table.getValue(Via.PATTERN) > Via.PATTERN.base)
        assertEquals(
            0,
            DriftCommand(host, files).test("triage issue.md --confidence t.json").statusCode,
        )
    }

    @Test
    fun calibrateReadsZstdThroughTheUsersProgram() {
        val argv = listOf("zstd", "-dc", "--long=27", "c.json.zst")
        val text = corpusText()
        val ok = FakeHost(commands = mapOf(argv to CommandResult(0, text)))
        val files = MemoryCaseFiles()
        val result = DriftCommand(ok, files)
            .test("triage calibrate --corpus c.json.zst --out t.json --min-facts 5")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(listOf(argv), ok.ran)
        assertEquals(
            DriftCommand(host, MemoryCaseFiles(mutableMapOf("c.json" to text)))
                .test("triage calibrate --corpus c.json --out t.json --min-facts 5").stdout,
            result.stdout,
        )
        val failed = FakeHost(commands = mapOf(argv to CommandResult(1, "")))
        val bad = DriftCommand(
            failed,
            files,
        ).test("triage calibrate --corpus c.json.zst --out t.json")
        assertTrue("zstd could not read c.json.zst (exit 1)" in bad.stderr, bad.stderr)
        val none = DriftCommand(FakeHost(canRun = false), files)
            .test("triage calibrate --corpus c.json.zst --out t.json")
        assertTrue("needs the zstd program" in none.stderr, none.stderr)
    }

    @Test
    fun calibrateRefusesWhatItCannotRead() {
        val files = MemoryCaseFiles(mutableMapOf("bad.json" to "[\n{nope\n]"))
        for ((arg, reason) in listOf(
            "none.json" to "cannot read corpus: none.json",
            "bad.json" to "bad corpus bad.json",
        )) {
            val r = DriftCommand(host, files).test("triage calibrate --corpus $arg --out t.json")
            assertEquals(1, r.statusCode)
            assertTrue(reason in r.stderr, r.stderr)
        }
        assertTrue("t.json" !in files.files)
        for (args in listOf("calibrate --corpus bad.json", "calibrate --out t.json")) {
            val usage = DriftCommand(host, files).test("triage $args")
            assertEquals(1, usage.statusCode, args)
            assertTrue("triage calibrate needs" in usage.stderr, usage.stderr)
        }
        val stray = DriftCommand(host, files).test("triage issue.md --out t.json")
        assertEquals(1, stray.statusCode)
        assertTrue("belong to triage calibrate" in stray.stderr, stray.stderr)
    }
}
