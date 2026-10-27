package dev.gmitch215.drift.cli

import dev.gmitch215.drift.DRIFT_VERSION
import dev.gmitch215.drift.case.Tar
import dev.gmitch215.drift.ci.Execution
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.fixtures.SetFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriftCommandTest {
    private val host = FakeHost(platform = "fake", env = mapOf("TZ" to "UTC"), canRun = false)

    @Test
    fun versionNamesThePlatform() {
        val result = DriftCommand(host).test("--version")
        assertEquals(0, result.statusCode)
        assertEquals("drift version $DRIFT_VERSION (fake)", result.stdout.trim())
    }

    @Test
    fun captureEmitsParsableCapsule() {
        val result = DriftCommand(host).test("capture --label demo")
        assertEquals(0, result.statusCode)
        val capsule = Capsule.parse(result.stdout.trim())
        assertEquals("demo", capsule.label)
        assertTrue(capsule.attributes.any { it.path == "env.TZ" && it.value == "UTC" })
    }

    @Test
    fun captureReadsProjectFactsOnlyWithTheProjectFlag() {
        val project = FakeHost(files = mapOf("go.mod" to "go 1.22.1\n"), canRun = false)
        fun paths(args: String) = Capsule.parse(DriftCommand(project).test(args).stdout.trim())
            .attributes.map { it.path }

        assertTrue("project.go.version" in paths("capture --project"))
        assertTrue(paths("capture").none { it.startsWith("project.") })
    }

    @Test
    fun captureBytesSurviveEveryLineSeparatorAndALongLine() {
        val tricky = "a\u0085b\u2028c\u2029d\r\n\te\u0000f\uFFFDg${"\u0085\u2028".repeat(10)}"
        val long = "x".repeat(1 shl 20) + "\u2028" + "y".repeat(10)
        val tough = FakeHost(env = mapOf("TRICKY" to tricky, "LONG" to long), canRun = false)
        val result = DriftCommand(tough).test("capture")
        assertEquals(0, result.statusCode)
        assertEquals("\n", result.stdout.takeLast(1))
        val byPath = Capsule.parse(result.stdout.dropLast(1)).attributes.associateBy { it.path }
        assertEquals(tricky.trim(), byPath.getValue("env.TRICKY").value)
        assertEquals(long, byPath.getValue("env.LONG").value)
        assertEquals(0, result.stderr.length)
    }

    @Test
    fun keepIdentifiersWarnsOnStderrAndKeepsTheName() {
        val me = FakeHost(env = mapOf("USER" to "zorbulax", "HOME" to "/home/zorbulax"))
        val kept = DriftCommand(me).test("capture --keep-identifiers")
        assertTrue("\"value\":\"zorbulax\"" in kept.stdout, kept.stdout)
        assertTrue("warning: the capsule keeps your account" in kept.stderr, kept.stderr)
        val plain = DriftCommand(me).test("capture")
        assertTrue("zorbulax" !in plain.stdout, plain.stdout)
        assertEquals("", plain.stderr)
    }

    @Test
    fun unknownCommandFails() {
        val result = DriftCommand(host).test("nope")
        assertEquals(1, result.statusCode)
        assertTrue("nope" in result.stderr, result.stderr)
    }

    private fun diagnoseHost(red: String = "37114464625") = FakeHost(
        canRun = false,
        files = mapOf(
            "green.json" to Fixtures.text("36702683742.json"),
            "red.json" to Fixtures.text("$red.json"),
            "bad.json" to "{not json",
        ),
    )

    @Test
    fun diagnoseOutputIsIdenticalOnEveryTarget() {
        val result = DriftCommand(diagnoseHost()).test("diagnose green.json red.json")
        assertEquals(0, result.statusCode)
        assertEquals(DIAGNOSE_SHA, Sha256.hex(result.stdout))
        assertTrue("\"ci.runner.image.version\"" in result.stdout, result.stdout)
    }

    @Test
    fun diagnoseSelfDiffHasNoChanges() {
        val host = FakeHost(
            canRun = false,
            files = mapOf(
                "a.json" to Fixtures.text("36702683742.json"),
            ),
        )
        val result = DriftCommand(host).test("diagnose a.json a.json")
        assertEquals(0, result.statusCode)
        assertTrue("\"relevant\":[]" in result.stdout, result.stdout)
    }

    @Test
    fun diagnoseNamesAMissingFile() {
        val result = DriftCommand(diagnoseHost()).test("diagnose green.json nope.json")
        assertEquals(1, result.statusCode)
        assertTrue("cannot read capsule file: nope.json" in result.stderr, result.stderr)
    }

    @Test
    fun diagnoseNamesAMalformedFile() {
        val result = DriftCommand(diagnoseHost()).test("diagnose bad.json red.json")
        assertEquals(1, result.statusCode)
        assertTrue("invalid capsule file bad.json" in result.stderr, result.stderr)
    }

    @Test
    fun diagnoseNeedsTwoFiles() {
        val result = DriftCommand(diagnoseHost()).test("diagnose green.json")
        assertEquals(1, result.statusCode)
    }

    private fun againstHost(): FakeHost {
        val green = Fixtures.text("36702683742.json")
        val red = Fixtures.text("37114464625.json")
        return FakeHost(
            canRun = false,
            files = mapOf(
                "base/capsule.json" to green,
                "cur/capsule.json" to red,
                "green.json" to green,
                "red.json" to red,
                "broken/capsule.json" to "{",
            ),
        )
    }

    @Test
    fun diagnoseAgainstADirectoryMatchesTheTwoFileForm() {
        val host = againstHost()
        val direct = DriftCommand(host).test("diagnose green.json red.json")
        val against = DriftCommand(host).test("diagnose --against base cur")
        assertEquals(0, against.statusCode, against.stderr)
        assertEquals(direct.stdout, against.stdout)
        val mixed = DriftCommand(host).test("diagnose --against green.json cur")
        assertEquals(direct.stdout, mixed.stdout)
        val dirs = DriftCommand(host).test("diagnose base cur")
        assertEquals(direct.stdout, dirs.stdout)
    }

    @Test
    fun diagnoseAgainstCapturesTheCurrentEnvironmentWhenNoneIsGiven() {
        val host = FakeHost(
            canRun = false,
            env = mapOf("TZ" to "UTC"),
            files = mapOf("base/capsule.json" to Fixtures.text("36702683742.json")),
        )
        val result = DriftCommand(host).test("diagnose --against base --detail summary")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.isNotEmpty())
    }

    @Test
    fun diagnoseAgainstNeedsABaselineAndAtMostOneCapsule() {
        val host = againstHost()
        val extra = DriftCommand(host).test("diagnose --against base cur cur")
        assertEquals(1, extra.statusCode)
        assertTrue("--against takes at most one capsule" in extra.stderr, extra.stderr)
        val none = DriftCommand(host).test("diagnose")
        assertEquals(1, none.statusCode)
        val missing = DriftCommand(host).test("diagnose --against nope cur")
        assertTrue("cannot read capsule file: nope" in missing.stderr, missing.stderr)
        val broken = DriftCommand(host).test("diagnose --against broken cur")
        assertTrue("invalid capsule directory broken" in broken.stderr, broken.stderr)
    }

    // clikt's test tokenizer treats an unquoted # as a comment; quote selector arguments
    private fun archiveHost(
        members: Map<String, String> = mapOf(
            "environments/passing.json" to Fixtures.text("36702683742.json"),
            "environments/failing.json" to Fixtures.text("37114464625.json"),
        ),
        bytes: ByteArray = Tar.write(members),
    ) = FakeHost(canRun = false, files = mapOf("case.driftcase" to bytes.decodeToString()))

    @Test
    fun diagnoseReadsTheArmCapsulesOfAnArchive() {
        val plain = DriftCommand(diagnoseHost()).test("diagnose green.json red.json")
        val archive = DriftCommand(archiveHost()).test(
            "diagnose 'case.driftcase#passing' 'case.driftcase#failing'",
        )
        assertEquals(0, archive.statusCode, archive.stderr)
        assertEquals(plain.stdout, archive.stdout)
    }

    @Test
    fun diagnoseAsksForASelectorOnAnArchive() {
        val result = DriftCommand(archiveHost()).test(
            "diagnose case.driftcase 'case.driftcase#failing'",
        )
        assertEquals(1, result.statusCode)
        val selectors = "select a capsule with case.driftcase#passing or case.driftcase#failing"
        assertTrue(selectors in result.stderr, result.stderr)
        assertTrue("drift verify, drift report and drift open" in result.stderr, result.stderr)
    }

    @Test
    fun diagnoseNamesVerifyReportAndOpenForABareArchive() {
        val result = DriftCommand(archiveHost()).test("diagnose case.driftcase")
        assertEquals(1, result.statusCode)
        assertTrue("drift verify, drift report and drift open" in result.stderr, result.stderr)
    }

    @Test
    fun diagnoseNamesAnArchiveMemberItLacks() {
        val passing = mapOf("environments/passing.json" to Fixtures.text("36702683742.json"))
        val result = DriftCommand(archiveHost(passing)).test(
            "diagnose 'case.driftcase#passing' 'case.driftcase#failing'",
        )
        assertEquals(1, result.statusCode)
        val missing = "case.driftcase has no environments/failing.json"
        assertTrue(missing in result.stderr, result.stderr)
    }

    @Test
    fun diagnoseNamesAnArchiveThatFailsItsTarChecks() {
        val bytes = Tar.write(mapOf("environments/passing.json" to "{}"))
        bytes[0] = (bytes[0] + 1).toByte()
        val result = DriftCommand(archiveHost(bytes = bytes)).test(
            "diagnose 'case.driftcase#passing' 'case.driftcase#failing'",
        )
        assertEquals(1, result.statusCode)
        assertTrue("invalid archive case.driftcase" in result.stderr, result.stderr)
    }

    @Test
    fun diagnoseRejectsASelectorOnAFileThatIsNotAnArchive() {
        val result = DriftCommand(diagnoseHost()).test("diagnose 'green.json#passing' red.json")
        assertEquals(1, result.statusCode)
        val message = "green.json is not a .driftcase archive; drop #passing"
        assertTrue(message in result.stderr, result.stderr)
    }

    private fun setHost(vararg extra: Pair<String, String>) = FakeHost(
        canRun = false,
        files = SetFixtures.names.filter { it.endsWith(".json") }
            .associateWith { SetFixtures.text(it) } + extra,
    )

    private val toolchain = "'toolchain/pass-1.json#pass' 'toolchain/pass-2.json#pass' " +
        "'toolchain/fail-1.json#fail' 'toolchain/fail-2.json#fail'"

    @Test
    fun diagnoseSetRanksTheSeparatingKeyFirstFromSelectors() {
        val result = DriftCommand(setHost()).test("diagnose --set $toolchain")
        assertEquals(0, result.statusCode, result.stderr)
        val splits = CanonicalJson.parse(result.stdout.trim()).obj().require("splits").array()
        val top = splits.first().obj()
        assertEquals("tool.node.version", top.require("path").string())
        assertEquals("22.4.0", top.require("value").string())
        assertEquals(1_000_000, top.require("score").long())
        assertEquals(1, splits.count { it.obj().require("separates") == JsonBool(true) })
    }

    @Test
    fun diagnoseSetPrintsTextAtThreeDetails() {
        val host = setHost()
        val summary = DriftCommand(host).test("diagnose --set $toolchain --detail summary")
        val detail = DriftCommand(host).test("diagnose --set $toolchain --detail detail")
        val full = DriftCommand(host).test("diagnose --set $toolchain --detail full")
        assertEquals(0, summary.statusCode, summary.stderr)
        assertTrue(summary.stdout.startsWith("set: 2 failing, 2 passing, 1 separating\n1. "))
        assertEquals(7, summary.stdout.lines().size)
        assertTrue(detail.stdout.lines().size > 6, detail.stdout)
        assertEquals(detail.stdout, full.stdout)
        assertEquals(
            DriftCommand(host).test("diagnose --set $toolchain --json").stdout,
            DriftCommand(host).test("diagnose --set $toolchain").stdout,
        )
    }

    @Test
    fun diagnoseSetReadsTheOutcomeACapsuleDirectoryRecords() {
        fun dir(name: String, outcome: RunOutcome?, exit: Int? = null): List<Pair<String, String>> {
            val capsule = Capsule.parse(SetFixtures.text("toolchain/$name.json"))
            return listOfNotNull(
                "$name/capsule.json" to capsule.canonical(),
                outcome?.let { "$name/run.json" to Run("r", it, capsule = null).canonical() },
                exit?.let {
                    "$name/result.json" to
                        CanonicalJson.encode(Execution("cmd", it, 1, false).toJson())
                },
            )
        }
        val host = setHost(
            *(
                dir("pass-1", RunOutcome.PASS) + dir("pass-2", null, 0) +
                    dir("fail-1", RunOutcome.FAIL) + dir("fail-2", null, 2)
                ).toTypedArray(),
        )
        val recorded = DriftCommand(host).test("diagnose --set pass-1 pass-2 fail-1 fail-2")
        assertEquals(0, recorded.statusCode, recorded.stderr)
        fun splits(out: String) = CanonicalJson.parse(out.trim()).obj().require("splits")

        val selected = DriftCommand(host).test("diagnose --set $toolchain")
        assertEquals(splits(selected.stdout), splits(recorded.stdout))
        val flipped = DriftCommand(host).test(
            "diagnose --set 'pass-1#fail' pass-2 fail-1 fail-2 --detail summary",
        )
        assertTrue(flipped.stdout.startsWith("set: 3 failing, 1 passing"), flipped.stdout)
    }

    @Test
    fun diagnoseSetWorksOnAnArchiveMember() {
        val host = FakeHost(
            canRun = false,
            files = mapOf(
                "case.driftcase" to Tar.write(
                    mapOf(
                        "environments/passing.json" to SetFixtures.text("toolchain/pass-1.json"),
                        "environments/failing.json" to SetFixtures.text("toolchain/fail-1.json"),
                    ),
                ).decodeToString(),
            ),
        )
        val result = DriftCommand(host).test(
            "diagnose --set 'case.driftcase#passing' 'case.driftcase#failing' --detail summary",
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.startsWith("set: 1 failing, 1 passing"), result.stdout)
    }

    @Test
    fun diagnoseSetRefusesWhatItCannotRank() {
        val host = setHost("broken/capsule.json" to "{")
        fun fails(args: String, message: String) {
            val result = DriftCommand(host).test("diagnose --set $args")
            assertEquals(1, result.statusCode, args)
            assertTrue(message in result.stderr, result.stderr)
        }
        fails("'toolchain/pass-1.json#pass'", "at least two capsules")
        fails(
            "'toolchain/pass-1.json#pass' 'toolchain/pass-2.json#pass'",
            "at least one passing and one failing capsule",
        )
        fails(
            "'toolchain/fail-1.json#fail' 'toolchain/fail-2.json#fail'",
            "at least one passing and one failing capsule",
        )
        fails(
            "toolchain/pass-1.json 'toolchain/fail-1.json#fail'",
            "toolchain/pass-1.json has no recorded outcome; end it with #pass or #fail",
        )
        fails("broken 'toolchain/fail-1.json#fail'", "invalid capsule directory broken")
        fails(
            "'nope.json#pass' 'toolchain/fail-1.json#fail'",
            "cannot read capsule file: nope.json",
        )
        fails("--against base $toolchain", "cannot be combined with --against or --case")
        fails("--case out $toolchain", "cannot be combined with --against or --case")
    }

    @Test
    fun diagnoseWithoutSetStillNeedsExactlyTwoCapsules() {
        val three = DriftCommand(diagnoseHost()).test("diagnose green.json red.json red.json")
        assertEquals(1, three.statusCode)
        assertTrue("expected a passing and a failing capsule" in three.stderr, three.stderr)
    }

    private companion object {
        const val DIAGNOSE_SHA = "99f637b2314ac9a6b75f2a212313f80ee376521a1248a2219910bd391ed838f7"
    }
}
