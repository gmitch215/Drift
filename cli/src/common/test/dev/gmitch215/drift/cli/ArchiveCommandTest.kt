package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.testing.test
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.MemoryArchiveFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.case.Tar
import dev.gmitch215.drift.case.TarRead
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.command.SolveCommand
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.lab.Counts
import dev.gmitch215.drift.lab.DecisionRule
import dev.gmitch215.drift.lab.FakeExecutor
import dev.gmitch215.drift.lab.Ingest
import dev.gmitch215.drift.lab.RawTrial
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveCommandTest {
	private val green = LabCapsules.node
	private val red = Capsule(
		"lab-node-red",
		green.attributes.filter { it.path != "env.TZ" } +
			Attribute("env.TZ", "America/New_York", "test"),
	)
	private val disk = MemoryCaseFiles()
	private val archives = MemoryArchiveFiles()
	private val host = FakeHost(
		files = mapOf("green.json" to green.canonical(), "red.json" to red.canonical()),
	)
	private val executor = FakeExecutor(
		behave = { arm, _ ->
			val fails = arm.capsule.attributes.any {
				it.path == "env.TZ" && it.value == "America/New_York"
			}
			RawTrial(if (fails) 1 else 0, if (fails) "wrong day" else "ok", 1)
		},
	)

	private fun solve() = SolveCommand(host, disk, executor).test(
		"--run \"sh t.sh\" --fail-when \"wrong day\" --pilot 6 --case case green.json red.json",
	)

	private fun drift(args: String, h: FakeHost = host) = DriftCommand(h, disk, archives).test(args)

	private fun packed(): ByteArray {
		assertEquals(0, solve().statusCode)
		val result = drift("pack case --out out.driftcase")
		assertEquals(0, result.statusCode, result.stderr)
		return assertNotNull(archives.files["out.driftcase"])
	}

	@Test
	fun packWritesADeterministicArchiveAndPrintsItsHash() {
		val bytes = packed()
		val result = drift("pack case --out again.driftcase")
		assertTrue(bytes.contentEquals(archives.files.getValue("again.driftcase")))
		assertTrue("sha256 ${Sha256.hex(bytes)}" in result.stdout, result.stdout)
		assertTrue(result.stdout.startsWith("wrote again.driftcase: "))
		assertTrue(assertIs<TarRead.Entries>(Tar.read(bytes)).files.containsKey("report.html"))
	}

	@Test
	fun verifyPassesOnAnIntactArchiveAndSaysNothingWasRerun() {
		packed()
		val result = drift("verify out.driftcase")
		assertEquals(0, result.statusCode, result.stderr)
		assertTrue(result.stdout.trimEnd().endsWith("verify: ok"), result.stdout)
		assertTrue(
			"experiments are not re-run; the recorded results are taken as given" in result.stdout,
		)
		assertTrue(result.stdout.lines().first().startsWith("PASS tar:"))
		val json = drift("verify out.driftcase --json")
		assertEquals(0, json.statusCode)
		val parsed = CanonicalJson.parse(json.stdout.trim()) as JsonObject
		assertEquals(JsonBool(true), parsed["ok"])
	}

	@Test
	fun verifyNamesTheFirstFailingCheckAndExitsOneWithoutAStackTrace() {
		val bytes = packed()
		val files = assertIs<TarRead.Entries>(Tar.read(bytes)).files.toMutableMap()
		val path = files.keys.sorted().first {
			it.startsWith("results/0") && "\"status\":\"fail\"" in files.getValue(it)
		}
		files[path] = files.getValue(path).replace("\"status\":\"fail\"", "\"status\":\"pass\"")
		archives.files["bad.driftcase"] = Tar.write(files)
		val result = drift("verify bad.driftcase")
		assertEquals(1, result.statusCode)
		assertTrue("FAIL manifest: $path was changed; expected " in result.stdout, result.stdout)
		assertTrue("verification failed: FAIL manifest" in result.stderr, result.stderr)
		assertTrue("Exception" !in result.stderr && "\tat " !in result.stderr)
		val json = drift("verify bad.driftcase --json")
		assertEquals(1, json.statusCode)
		val parsed = CanonicalJson.parse(json.stdout.trim()) as JsonObject
		assertEquals(JsonBool(false), parsed["ok"])
	}

	@Test
	fun verifyRefusesAnythingThatIsNotAnArchive() {
		archives.files["junk.driftcase"] = ByteArray(2000) { (it * 31).toByte() }
		val junk = drift("verify junk.driftcase")
		assertEquals(1, junk.statusCode)
		assertTrue(junk.stdout.startsWith("FAIL tar:"), junk.stdout)
		archives.files["empty.driftcase"] = ByteArray(0)
		assertEquals(1, drift("verify empty.driftcase").statusCode)
		val missing = drift("verify nowhere.driftcase")
		assertEquals(1, missing.statusCode)
		assertTrue("cannot read archive: nowhere.driftcase" in missing.stderr)
	}

	@Test
	fun reportWritesTheStaticHtmlOfTheCertificate() {
		val bytes = packed()
		val result = drift("report out.driftcase --out report.html")
		assertEquals(0, result.statusCode, result.stderr)
		val html = assertNotNull(disk.files["report.html"])
		val inside = assertIs<TarRead.Entries>(Tar.read(bytes)).files.getValue("report.html")
		assertEquals(inside, html)
		assertTrue("<script" !in html)
		val missing = drift("report nowhere.driftcase --out r.html")
		assertTrue("cannot read archive: nowhere.driftcase" in missing.stderr)
	}

	@Test
	fun packRefusesACaseWithoutACertificateAndAMissingDirectory() {
		drift("diagnose green.json red.json --case plain")
		val plain = drift("pack plain --out plain.driftcase")
		assertEquals(1, plain.statusCode)
		assertTrue("not a solved case" in plain.stderr, plain.stderr)
		val none = drift("pack nowhere --out x.driftcase")
		assertTrue("cannot read case nowhere" in none.stderr, none.stderr)
		assertTrue(archives.files.isEmpty())
	}

	@Test
	fun anIngestedResultStillPacksAndVerifies() {
		assertEquals(0, solve().statusCode)
		val plan = CanonicalJson.parse(disk.files.getValue("case/experiments/plan.json"))
		val trials = (plan as JsonObject).fields.getValue("trials") as JsonObject
		fun n(key: String) = (trials.fields.getValue(key) as dev.gmitch215.drift.json.JsonInt).value
		val rule = DecisionRule(n("perArm").toInt(), n("threshold").toInt(), n("alpha"), n("rate"))
		val none = Counts(0, rule.perArm)
		val ingest = FakeHost(
			files = mapOf("results/r1.json" to Ingest.template("e1", rule.sha256(), none, none)),
			commands = mapOf(listOf("ls", "-1", "results") to CommandResult(0, "r1.json\n")),
		)
		assertEquals(0, drift("ingest case results", ingest).statusCode)
		assertEquals(0, drift("pack case --out ingested.driftcase").statusCode)
		val result = drift("verify ingested.driftcase")
		assertEquals(0, result.statusCode, result.stdout)
		assertTrue("SKIP posteriors" in result.stdout, result.stdout)
		assertTrue(CaseFile(emptyMap()).files.isEmpty())
	}

	@Test
	fun theThreeCommandsAreRegistered() {
		val help = drift("--help").stdout
		assertTrue("pack" in help && "verify" in help && "report" in help)
	}
}
