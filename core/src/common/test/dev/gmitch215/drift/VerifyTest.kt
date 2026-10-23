package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.Chain
import dev.gmitch215.drift.case.Tar
import dev.gmitch215.drift.fixtures.FlagshipAFixtures
import dev.gmitch215.drift.fixtures.FlagshipBFixtures
import dev.gmitch215.drift.fixtures.FlagshipCFixtures
import dev.gmitch215.drift.fixtures.FlagshipDFixtures
import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchiveOpen
import dev.gmitch215.drift.lab.ArchivePack
import dev.gmitch215.drift.lab.CheckStatus
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.lab.Verify
import dev.gmitch215.drift.lab.VerifyReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VerifyTest {
    private val ids = listOf(
        "tar",
        "manifest",
        "observation-chain",
        "results-chain",
        "capsules",
        "counts",
        "fisher",
        "rules",
        "decision",
        "posteriors",
        "ranking",
        "plan",
        "verdict",
        "report",
    )

    private val labels = mapOf(
        "confirmed" to "CONFIRMED:",
        "bundle" to "CONFIRMED EFFECT (bundle)",
        "narrowed" to "NARROWED",
        "stuck at the pilot" to "STUCK",
        "stuck with refuted candidates" to "STUCK",
    )

    private fun archive(case: CaseFile): Map<String, String> =
        assertIs<ArchivePack.Packed>(Archive.pack(case)).case.files

    private fun parse(text: String) = CanonicalJson.parse(text) as JsonObject

    private fun set(v: JsonValue, path: List<Any>, value: JsonValue): JsonValue {
        if (path.isEmpty()) return value
        val head = path.first()
        return when (v) {
            is JsonObject -> JsonObject(
                v.fields + (head as String to set(v.fields.getValue(head), path.drop(1), value)),
            )

            is JsonArray -> JsonArray(
                v.items.mapIndexed { i, x -> if (i == head) set(x, path.drop(1), value) else x },
            )

            else -> error("no such path")
        }
    }

    private fun get(v: JsonValue, path: List<Any>): JsonValue = path.fold(v) { acc, key ->
        when (acc) {
            is JsonObject -> acc.fields.getValue(key as String)
            is JsonArray -> acc.items[key as Int]
            else -> error("no such path")
        }
    }

    private fun edit(
        files: MutableMap<String, String>,
        file: String,
        path: List<Any>,
        value: JsonValue,
    ) {
        files[file] = CanonicalJson.encode(set(parse(files.getValue(file)), path, value))
    }

    private fun reseal(files: MutableMap<String, String>) {
        val cert = parse(files.getValue(SolveCertificate.CERTIFICATE))
        var prev = (get(cert, listOf("chain", "observationsHead")) as JsonString).value
        for (path in files.keys.filter { it.startsWith("results/0") }.sorted()) {
            val content = Chain.content(parse(files.getValue(path)))
            val hash = Chain.link(prev, content)
            files[path] = CanonicalJson.encode(
                JsonObject(
                    content.fields + mapOf("prev" to JsonString(prev), "hash" to JsonString(hash)),
                ),
            )
            prev = hash
        }
        edit(files, SolveCertificate.CERTIFICATE, listOf("chain", "resultsHead"), JsonString(prev))
    }

    private fun finish(files: MutableMap<String, String>): VerifyReport {
        files[CaseFile.MANIFEST] = CaseFile.manifest(files)
        return Verify.open(Tar.write(files))
    }

    private fun supported(files: Map<String, String>): Pair<String, Int> {
        val path = files.keys.sorted().first {
            val text = files.getValue(it)
            it.startsWith("results/0") && "\"outcome\":\"supported\"" in text &&
                "\"role\":\"forward\"" in text
        }
        return path to path.removePrefix("results/").removeSuffix(".json").toInt() - 1
    }

    private fun idOf(files: Map<String, String>, path: String) =
        (parse(files.getValue(path))["experiment"] as JsonString).value

    private fun firstFailure(report: VerifyReport) = assertNotNull(report.first, report.text())

    @Test
    fun everyVerdictClassVerifiesWithEveryCheckInOrder() {
        for ((name, case) in LabCases.all) {
            val report = Verify.open(assertIs<ArchivePack.Packed>(Archive.pack(case)).bytes)
            assertTrue(report.ok, "$name\n${report.text()}")
            assertEquals(ids, report.checks.map { it.id }, name)
            assertTrue(report.checks.none { it.status == CheckStatus.FAIL }, name)
            assertTrue(report.verdict!!.startsWith(labels.getValue(name)), name)
        }
    }

    @Test
    fun theReportSaysPlainlyThatNothingWasRerun() {
        val report = Verify.open(
            assertIs<ArchivePack.Packed>(Archive.pack(LabCases.confirmed)).bytes,
        )
        assertTrue(report.text().endsWith("verify: ok"))
        assertTrue(
            "experiments are not re-run; the recorded results are taken as given" in report.text(),
        )
        val json = parse(report.json())
        assertEquals(JsonBool(true), json["ok"])
        assertEquals(JsonString(Verify.NOT_RERUN), json["note"])
        assertEquals(ids.size, (json["checks"] as JsonArray).items.size)
        assertEquals(report.json(), CanonicalJson.encode(json))
    }

    @Test
    fun aNaiveEditOfACountIsCaughtByTheManifestAndAForgedOneByTheChainOrTheCounts() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val (path, _) = supported(files)
        val flipped = files.getValue(path).replace(
            "\"status\":\"fail\"",
            "\"status\":\"pass\"",
        )
        assertNotEquals(files.getValue(path), flipped)
        val naive = Verify.open(
            Tar.write(files.toMutableMap().also { it[path] = flipped }),
        )
        assertEquals("manifest", firstFailure(naive).id)
        val manifestFixed = files.toMutableMap().also { it[path] = flipped }
        assertEquals("results-chain", firstFailure(finish(manifestFixed)).id)
        val forged = files.toMutableMap().also { it[path] = flipped }
        reseal(forged)
        val report = finish(forged)
        val failure = firstFailure(report)
        assertEquals("counts", failure.id, report.text())
        assertNotEquals(failure.expected, failure.recorded)
    }

    @Test
    fun anEditedPValueEvenWithItsChainResealedIsCaughtByTheExactTest() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val (path, at) = supported(files)
        val fake = JsonObject(
            mapOf(
                "numerator" to JsonInt(1),
                "denominator" to JsonInt(1000),
                "micro" to JsonInt(1000),
            ),
        )
        edit(files, path, listOf("p"), fake)
        edit(files, SolveCertificate.CERTIFICATE, listOf("experiments", at, "p"), fake)
        reseal(files)
        val failure = firstFailure(finish(files))
        assertEquals("fisher", failure.id)
        assertNotEquals("1/1000 (1000)", failure.expected)
        assertEquals("1/1000 (1000)", failure.recorded)
    }

    @Test
    fun anEditedOutcomeIsCaughtByTheDecisionRule() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val (path, at) = supported(files)
        edit(files, path, listOf("outcome"), JsonString("refuted"))
        edit(
            files,
            SolveCertificate.CERTIFICATE,
            listOf("experiments", at, "outcome"),
            JsonString("refuted"),
        )
        reseal(files)
        val failure = firstFailure(finish(files))
        assertEquals("decision", failure.id)
        assertEquals("supported", failure.expected)
        assertEquals("refuted", failure.recorded)
    }

    @Test
    fun anEditedPosteriorIsCaughtByTheBayesianReplay() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val certificate = parse(files.getValue(SolveCertificate.CERTIFICATE))
        val masses = get(certificate, listOf("posteriors", 1, "belief", "masses")) as JsonArray
        val first = masses.items[0] as JsonObject
        val bumped = JsonObject(
            first.fields + ("micro" to JsonInt(((first["micro"] as JsonInt).value) - 1)),
        )
        edit(
            files,
            SolveCertificate.CERTIFICATE,
            listOf("posteriors", 1, "belief", "masses", 0),
            bumped,
        )
        val failure = firstFailure(finish(files))
        assertEquals("posteriors", failure.id)
        assertNotEquals(failure.expected, failure.recorded)
        val dropped = archive(LabCases.confirmed).toMutableMap()
        edit(dropped, SolveCertificate.CERTIFICATE, listOf("posteriors"), JsonArray(emptyList()))
        assertEquals("posteriors", firstFailure(finish(dropped)).id)
    }

    @Test
    fun anEditedVerdictIsCaughtByTheRecordedExperiments() {
        val confirmed = archive(LabCases.confirmed).toMutableMap()
        edit(
            confirmed,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "minimalSet"),
            JsonArray(listOf(JsonString("env.LANG"))),
        )
        assertEquals("verdict", firstFailure(finish(confirmed)).id)
        val stuck = archive(LabCases.stuckRefuted).toMutableMap()
        edit(
            stuck,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "kind"),
            JsonString("confirmed"),
        )
        edit(
            stuck,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "label"),
            JsonString("CONFIRMED"),
        )
        assertEquals("verdict", firstFailure(finish(stuck)).id)
        val bundle = archive(LabCases.bundle).toMutableMap()
        edit(
            bundle,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "kind"),
            JsonString("confirmed"),
        )
        edit(
            bundle,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "label"),
            JsonString("CONFIRMED"),
        )
        assertEquals("verdict", firstFailure(finish(bundle)).id)
        val narrowed = archive(LabCases.narrowed).toMutableMap()
        edit(narrowed, SolveCertificate.CERTIFICATE, listOf("verdict", "kind"), JsonString("stuck"))
        edit(
            narrowed,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "label"),
            JsonString("STUCK"),
        )
        edit(
            narrowed,
            SolveCertificate.CERTIFICATE,
            listOf("verdict", "reason"),
            JsonString("budget"),
        )
        assertEquals("verdict", firstFailure(finish(narrowed)).id)
    }

    @Test
    fun aConfirmedVerdictOverAnUnmeasuredDiffFailsAtTheVerdictCheck() {
        val files = archive(LabCases.unmeasured).toMutableMap()
        val cert = SolveCertificate.CERTIFICATE
        edit(files, cert, listOf("verdict", "kind"), JsonString("confirmed"))
        edit(files, cert, listOf("verdict", "label"), JsonString("CONFIRMED"))
        edit(files, cert, listOf("verdict", "notIsolated"), JsonArray(emptyList()))
        val failure = firstFailure(finish(files))
        assertEquals("verdict", failure.id)
        assertTrue("measured diff" in failure.detail, failure.detail)
    }

    @Test
    fun anIsolationClaimTheRecordedDiffsDoNotSupportIsCaught() {
        val files = archive(LabCases.unmeasured).toMutableMap()
        val claim = JsonString("measured")
        edit(files, SolveCertificate.CERTIFICATE, listOf("conditions", "isolation"), claim)
        val failure = firstFailure(finish(files))
        assertEquals("verdict", failure.id)
        assertEquals("predicted", failure.expected)
    }

    @Test
    fun aWrongCountOfCapturedAttributesIsCaught() {
        val files = archive(LabCases.confirmed).toMutableMap()
        edit(files, SolveCertificate.CERTIFICATE, listOf("conditions", "isolatedAmong"), JsonInt(3))
        val failure = firstFailure(finish(files))
        assertEquals("verdict", failure.id)
        assertEquals("3", failure.recorded)
    }

    @Test
    fun aCertificateWithoutTheIsolationFieldsIsReadFromItsRecordedDiffs() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val cert = parse(files.getValue(SolveCertificate.CERTIFICATE))
        val conditions = cert.fields.getValue("conditions") as JsonObject
        val legacy = JsonObject(conditions.fields - "isolation" - "isolatedAmong")
        edit(files, SolveCertificate.CERTIFICATE, listOf("conditions"), legacy)
        assertTrue(finish(files).ok)
    }

    @Test
    fun anUnmeasuredEffectVerifiesAndTheRecordedIsolationIsPredicted() {
        val packed = assertIs<ArchivePack.Packed>(Archive.pack(LabCases.unmeasured))
        val report = Verify.open(packed.bytes)
        assertTrue(report.ok, report.text())
        assertTrue(report.verdict!!.startsWith("CONFIRMED EFFECT (isolation not measured)"))
    }

    @Test
    fun aRewrittenTrialScheduleIsCaughtEvenWithItsChainResealed() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val (path, at) = supported(files)
        val order = (get(parse(files.getValue(path)), listOf("schedule", "order")) as JsonString)
        val fake = JsonString("CT".repeat(order.value.length / 2))
        assertNotEquals(order, fake)
        edit(files, path, listOf("schedule", "order"), fake)
        val cert = SolveCertificate.CERTIFICATE
        edit(files, cert, listOf("experiments", at, "schedule", "order"), fake)
        reseal(files)
        val failure = firstFailure(finish(files))
        assertEquals("rules", failure.id)
        assertTrue("trial schedule" in failure.detail, failure.detail)
    }

    @Test
    fun aWidenedPilotCapOrALoweredPilotMinimumIsCaught() {
        val cert = SolveCertificate.CERTIFICATE
        val cap = archive(LabCases.confirmed).toMutableMap()
        edit(cap, cert, listOf("pilot", "max"), JsonInt(31))
        val capped = firstFailure(finish(cap))
        assertEquals("rules", capped.id)
        assertEquals("30", capped.expected)
        val min = archive(LabCases.confirmed).toMutableMap()
        edit(min, cert, listOf("pilot", "min"), JsonInt(3))
        val lowered = firstFailure(finish(min))
        assertEquals("rules", lowered.id)
        assertTrue("pilot trials per arm" in lowered.detail, lowered.detail)
    }

    @Test
    fun aRewrittenFamilySizeOrBoundIsCaught() {
        val cert = SolveCertificate.CERTIFICATE
        val size = archive(LabCases.confirmed).toMutableMap()
        edit(size, cert, listOf("family", "size"), JsonInt(1))
        val resized = firstFailure(finish(size))
        assertEquals("rules", resized.id)
        assertTrue("family" in resized.detail, resized.detail)
        val bound = archive(LabCases.confirmed).toMutableMap()
        edit(bound, cert, listOf("family", "falseConfirmedBound"), JsonInt(1))
        assertEquals("rules", firstFailure(finish(bound)).id)
    }

    @Test
    fun aCertificateWithItsFamilyRemovedFailsTheRuleAlphaCheck() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val certificate = parse(files.getValue(SolveCertificate.CERTIFICATE))
        files[SolveCertificate.CERTIFICATE] =
            CanonicalJson.encode(JsonObject(certificate.fields - "family"))
        assertEquals("rules", firstFailure(finish(files)).id)
    }

    @Test
    fun aStatementDroppingTheRateBoundOfANotReproducedRunIsCaught() {
        val files = archive(LabCases.notSeen).toMutableMap()
        val text = files.getValue(SolveCertificate.CERTIFICATE)
        assertTrue("below 10% at 95%" in text)
        files[SolveCertificate.CERTIFICATE] = text.replace("below 10% at 95%", "small")
        assertEquals("verdict", firstFailure(finish(files)).id)
    }

    @Test
    fun aStatementQuotingAnotherPValueIsCaught() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val text = files.getValue(SolveCertificate.CERTIFICATE)
        val quoted = "exact one-sided p " + text.substringAfter("exact one-sided p ")
            .substringBefore(")")
        assertTrue(quoted in text && quoted != "exact one-sided p 0.000001")
        files[SolveCertificate.CERTIFICATE] = text.replace(
            quoted,
            "exact one-sided p 0.000001",
        )
        assertEquals("verdict", firstFailure(finish(files)).id)
    }

    @Test
    fun aBrokenObservationChainIsNamedBeforeAnythingElse() {
        val files = archive(LabCases.confirmed).toMutableMap()
        files["observations/0001.json"] = files.getValue("observations/0001.json").replace(
            "\"label\":\"",
            "\"label\":\"x",
        )
        val failure = firstFailure(finish(files))
        assertEquals("observation-chain", failure.id)
        assertTrue("entry 0" in failure.detail, failure.detail)
    }

    @Test
    fun aMissingFileIsNamedByTheManifestCheck() {
        val files = archive(LabCases.confirmed).toMutableMap()
        files.remove("results/0002.json")
        val failure = firstFailure(Verify.open(Tar.write(files)))
        assertEquals("manifest", failure.id)
        assertEquals("results/0002.json", failure.expected)
        assertEquals("missing", failure.recorded)
        val extra = archive(LabCases.confirmed).toMutableMap()
        extra["notes.txt"] = "hi"
        assertEquals("manifest", firstFailure(Verify.open(Tar.write(extra))).id)
    }

    @Test
    fun aLoosenedPreregisteredRuleIsCaughtEvenWhenEveryHashIsRecomputed() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val (path, at) = supported(files)
        val spec = "experiments/${idOf(files, path)}.json"
        val specJson = parse(files.getValue(spec))
        val loose = JsonObject(
            (get(specJson, listOf("rule")) as JsonObject).fields + ("alpha" to JsonInt(200_000)),
        )
        val ruleSha = Sha256.hex(CanonicalJson.encode(loose))
        edit(files, spec, listOf("rule"), loose)
        edit(files, spec, listOf("ruleSha256"), JsonString(ruleSha))
        val specSha = Sha256.hex(files.getValue(spec))
        edit(files, path, listOf("ruleSha256"), JsonString(ruleSha))
        edit(files, path, listOf("specSha256"), JsonString(specSha))
        edit(files, SolveCertificate.CERTIFICATE, listOf("experiments", at, "rule"), loose)
        edit(
            files,
            SolveCertificate.CERTIFICATE,
            listOf("experiments", at, "ruleSha256"),
            JsonString(ruleSha),
        )
        edit(
            files,
            SolveCertificate.CERTIFICATE,
            listOf("experiments", at, "specSha256"),
            JsonString(specSha),
        )
        reseal(files)
        val failure = firstFailure(finish(files))
        assertEquals("rules", failure.id)
        assertEquals("4166", failure.expected, "the plan's alpha over a family of 12")
        assertEquals("200000", failure.recorded)
    }

    @Test
    fun aSwappedRuleFileIsCaughtByItsHash() {
        val files = archive(LabCases.confirmed).toMutableMap()
        edit(files, "experiments/x1.json", listOf("rule", "threshold"), JsonInt(1))
        val failure = firstFailure(finish(files))
        assertEquals("rules", failure.id)
        assertTrue("spec hash" in failure.detail, failure.detail)
    }

    @Test
    fun aCaseDirectoryVerifiesToo() {
        val report = Verify.run(LabCases.bundle)
        assertTrue(report.ok, report.text())
        assertEquals(CheckStatus.SKIP, report.checks.last().status)
        assertEquals("report", report.checks.last().id)
    }

    @Test
    fun aMissingOrGarbledCertificateFailsWithoutAStackTrace() {
        val files = archive(LabCases.confirmed).toMutableMap()
        files[SolveCertificate.CERTIFICATE] = "{nope"
        val report = finish(files)
        assertFalse(report.ok)
        assertTrue(report.text().contains("verify: failed at"))
        val none = archive(LabCases.confirmed).toMutableMap()
        none.remove(SolveCertificate.CERTIFICATE)
        assertEquals("certificate", finish(none).first!!.id)
    }

    @Test
    fun aCertificateNestedFarTooDeepFailsWithoutCrashing() {
        val files = archive(LabCases.confirmed).toMutableMap()
        files[SolveCertificate.CERTIFICATE] = "[".repeat(200_000) + "]".repeat(200_000)
        val report = finish(files)
        assertFalse(report.ok)
        assertEquals("certificate", firstFailure(report).id)
    }

    @Test
    fun aRankingRecordedUnderAnotherFitLabelStillVerifies() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val path = listOf("weights", "probability", "fit")
        edit(files, "hypotheses/ranking.json", path, JsonString("dev split of 3 scenarios"))
        val report = finish(files)
        assertTrue(report.ok, report.text())
    }

    @Test
    fun aRankingRecordedUnderOtherWeightsFailsAtTheRanking() {
        val files = archive(LabCases.confirmed).toMutableMap()
        val path = listOf("weights", "probability", "none")
        edit(files, "hypotheses/ranking.json", path, JsonInt(1))
        assertEquals("ranking", firstFailure(finish(files)).id)
    }

    @Test
    fun certificatesFromTheLinuxBinaryVerifyAndRepackToTheSameBytes() {
        val expected = mapOf(
            "locale-tz-date-2" to "CONFIRMED:",
            "runtime-node-api-1" to "CONFIRMED EFFECT (bundle)",
            "env-retries-flaky-plain-1" to "NARROWED",
            "limits-nofile-1" to "STUCK",
        )
        for ((name, label) in expected) {
            val bytes = LabFixtures.bytes("cases/$name.driftcase")
            val report = Verify.open(bytes)
            assertTrue(report.ok, "$name\n${report.text()}")
            assertTrue(report.verdict!!.startsWith(label), name)
            val opened = assertIs<ArchiveOpen.Opened>(Archive.open(bytes))
            val again = assertIs<ArchivePack.Packed>(Archive.pack(opened.case))
            assertTrue(again.bytes.contentEquals(bytes), name)
        }
    }

    @Test
    fun theFlagshipKotlinCaseIsPinnedAndVerifies() {
        val bytes = FlagshipBFixtures.bytes("resume-offset.driftcase")
        assertEquals(
            "d8cadbf2ef68af71205d43bc5d23369272d97bf2b2f10b65aa6b19b510ca28a3",
            Sha256.hex(bytes),
        )
        val report = Verify.open(bytes)
        assertTrue(report.ok, report.text())
        assertTrue(report.verdict!!.startsWith("CONFIRMED: this dimension causes the failure: "))
        assertTrue("runtime.platform" in report.verdict)
        val opened = assertIs<ArchiveOpen.Opened>(Archive.open(bytes))
        val again = assertIs<ArchivePack.Packed>(Archive.pack(opened.case))
        assertTrue(again.bytes.contentEquals(bytes))
        val source = FlagshipBFixtures.text("program/src/common/main/ResumeOffset.kt")
        assertTrue("indexOf(separator, offset)" in source)
    }

    @Test
    fun theFlagshipBlindCaseIsPinnedAndVerifies() {
        val bytes = FlagshipCFixtures.bytes("INC-13.driftcase")
        assertEquals(
            "b4b20bdcb4d3c5e44687564c570fc0be77de505e84607a936720ec18def65aeb",
            Sha256.hex(bytes),
        )
        val report = Verify.open(bytes)
        assertTrue(report.ok, report.text())
        assertTrue(report.verdict!!.startsWith("CONFIRMED EFFECT (bundle)"))
        val opened = assertIs<ArchiveOpen.Opened>(Archive.open(bytes))
        val again = assertIs<ArchivePack.Packed>(Archive.pack(opened.case))
        assertTrue(again.bytes.contentEquals(bytes))
    }

    @Test
    fun theFlagshipCiCaseIsPinnedAndChecksOut() {
        val files = FlagshipAFixtures.names
            .filter { it.startsWith("case/") }
            .associate { it.removePrefix("case/") to FlagshipAFixtures.text(it) }
        val case = CaseFile(files)
        assertEquals(emptyList(), case.check())
        assertEquals(
            // re-sealed after anonymizing the DBUS bus path uid in environments/passing.json
            "51ec12cddd3e8332805fa5d039ae4b2dd9fa8f71b9fbb2f6dfec263c3c001fde",
            Sha256.hex(files.getValue("manifest.json").encodeToByteArray()),
        )
        val header = case.json("case.json").fields
        assertEquals("drangler-same-commit", (header.getValue("name") as JsonString).value)
        val frame = (header.getValue("frame") as JsonObject).fields
        assertEquals("environment", (frame.getValue("kind") as JsonString).value)
        assertEquals("drangler-37195797966", (frame.getValue("failing") as JsonString).value)
        val ruledOut = FlagshipAFixtures.text("ruled-out.yml")
        for (id in listOf(
            "kernel-killed-a-process",
            "memory-exhaustion",
            "failing-tests-are-new",
        )) {
            assertTrue("- id: $id" in ruledOut, id)
        }
        val vacuous = FlagshipAFixtures.text("vacuous-green.txt").lines()
        assertTrue(vacuous.any { it.startsWith("to-worker") && "5 of 5 passed" in it })
        assertTrue(vacuous.any { it.startsWith("modify") && "0 of 5 passed" in it })
    }

    @Test
    fun theSealedBlindListIsPinnedAndOrderedByUrlHash() {
        val sealed = FlagshipCFixtures.bytes("sealed.json")
        assertEquals(
            "d92de5f5462780491b0e30cf4e6c5d0ae34e613c12e6565eb0b0543a4786f408",
            Sha256.hex(sealed),
        )
        val doc = parse(sealed.decodeToString())
        val candidates = (doc.fields.getValue("candidates") as JsonArray).items
            .map { (it as JsonObject).fields }
        fun text(fields: Map<String, JsonValue>, key: String) =
            (fields.getValue(key) as JsonString).value
        assertEquals(14, candidates.size)
        for (c in candidates) {
            assertEquals(Sha256.hex(text(c, "url").encodeToByteArray()), text(c, "urlSha256"))
        }
        val order = (doc.fields.getValue("order") as JsonArray).items
            .map { (it as JsonString).value }
        assertEquals(candidates.sortedBy { text(it, "urlSha256") }.map { text(it, "id") }, order)
        val urls = order.map { id -> text(candidates.first { text(it, "id") == id }, "url") }
        assertEquals(
            text(doc.fields, "orderSha256"),
            Sha256.hex((urls.joinToString("\n") + "\n").encodeToByteArray()),
        )
        val results = parse(FlagshipCFixtures.text("results.json"))
        val rows = (results.fields.getValue("rows") as JsonArray).items
            .map { (it as JsonObject).fields }
        assertEquals(order, rows.map { text(it, "id") })
        assertEquals(Sha256.hex(sealed), text(results.fields, "sealedSha256"))
        assertEquals("INC-13", order.first())
    }

    @Test
    fun caseDSelectionPredictionAndScoreAreSealedAndConsistent() {
        fun text(fields: Map<String, JsonValue>, key: String) =
            (fields.getValue(key) as JsonString).value
        fun items(fields: Map<String, JsonValue>, key: String) =
            (fields.getValue(key) as JsonArray).items.map { it as JsonObject }

        val selection = FlagshipDFixtures.bytes("selection.json")
        assertEquals(
            "24d17eff3383eb6c3ee5dada258620edefa909bd19a34866c8118602f097846d",
            Sha256.hex(selection),
        )
        val doc = parse(selection.decodeToString())
        val candidates = items(doc.fields, "candidates").map { it.fields }
        assertEquals(12, candidates.size)
        for (c in candidates) {
            assertEquals(Sha256.hex(text(c, "url").encodeToByteArray()), text(c, "urlSha256"))
        }
        val order = (doc.fields.getValue("order") as JsonArray).items
            .map { (it as JsonString).value }
        assertEquals(candidates.sortedBy { text(it, "urlSha256") }.map { text(it, "repo") }, order)

        val outcomes = items(parse(FlagshipDFixtures.text("outcomes.json")).fields, "rows")
            .map { it.fields }
        assertEquals(order, outcomes.map { text(it, "repo") })
        assertEquals("not-reconstructible", text(outcomes[0], "outcome"))
        assertEquals("reconstructible", text(outcomes[1], "outcome"))
        assertTrue(outcomes.drop(2).all { text(it, "outcome") == "not run" })

        val hashes = FlagshipDFixtures.text("prediction.sha256").lines().filter { it.isNotBlank() }
            .associate { it.substring(66) to it.substring(0, 64) }
        assertEquals(Sha256.hex(selection), hashes.getValue("selection.json"))
        for (name in listOf("prediction.json", "score-protocol.json")) {
            assertEquals(Sha256.hex(FlagshipDFixtures.bytes(name)), hashes.getValue(name), name)
        }

        val prediction = parse(FlagshipDFixtures.text("prediction.json")).fields
        val predicted = listOf("will-break", "may-break", "unknown")
            .flatMap { group -> items(prediction, group).map { text(it.fields, "rule") } }
        val protocol = parse(FlagshipDFixtures.text("score-protocol.json")).fields
        assertEquals(
            predicted.sorted(),
            (protocol.getValue("patterns") as JsonObject).fields.keys.sorted(),
        )

        val score = parse(FlagshipDFixtures.text("score.json")).fields
        assertEquals(
            Sha256.hex(FlagshipDFixtures.bytes("prediction.json")),
            text(score, "predictionSha256"),
        )
        val rows = items(score, "rows").map { it.fields }
        assertEquals(predicted.sorted(), rows.map { text(it, "rule") }.sorted())
        val level = (score.getValue("testLevel") as JsonObject).fields
        fun count(key: String) = (level.getValue(key) as JsonInt).value.toInt()
        assertEquals(rows.size, count("TP") + count("FP"))
        assertEquals(rows.count { text(it, "outcome") == "FP" }, count("FP"))
        assertEquals(rows.count { text(it, "outcome") == "TP" }, count("TP"))
        assertEquals(11, count("willBreakPredicted"))
        assertEquals(1, count("mayBreakPredicted"))
    }
}
