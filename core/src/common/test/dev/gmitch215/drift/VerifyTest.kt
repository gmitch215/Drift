package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.Chain
import dev.gmitch215.drift.case.Tar
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
	fun aStatementQuotingAnotherPValueIsCaught() {
		val files = archive(LabCases.confirmed).toMutableMap()
		val text = files.getValue(SolveCertificate.CERTIFICATE)
		assertTrue("exact one-sided p 0.003968" in text)
		files[SolveCertificate.CERTIFICATE] = text.replace(
			"exact one-sided p 0.003968",
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
		assertEquals("50000", failure.expected)
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
}
