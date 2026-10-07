package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.lab.Counts
import dev.gmitch215.drift.lab.DecisionRule
import dev.gmitch215.drift.lab.Ingest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaseCommandTest {
	private val host = FakeHost(
		canRun = false,
		files = mapOf(
			"green.json" to Fixtures.text("36702683742.json"),
			"red.json" to Fixtures.text("36995781138.json"),
		),
	)
	private val disk = MemoryCaseFiles()

	private fun drift(args: String, files: CaseFiles = disk) = DriftCommand(host, files).test(args)

	private fun tree(dir: String) =
		disk.files.filterKeys { it.startsWith("$dir/") }.mapKeys { it.key.removePrefix("$dir/") }

	private fun written(): MemoryCaseFiles {
		assertEquals(0, drift("diagnose green.json red.json --case out").statusCode)
		return disk
	}

	@Test
	fun theHostReadsCaseFilesAndAMissingOneIsNull() {
		val files = HostCaseFiles(host)
		assertEquals(host.readText("green.json"), files.read("green.json"))
		assertEquals(null, files.read("nope.json"))
	}

	@Test
	fun diagnoseWritesTheCaseAndKeepsTheJsonOnStdout() {
		val result = drift("diagnose green.json red.json --case out")
		assertEquals(0, result.statusCode)
		assertEquals(DIAGNOSE_SHA, Sha256.hex(result.stdout))
		assertEquals("wrote 11 files to out\n", result.stderr)
		assertEquals(CASE_SHAS, tree("out").mapValues { Sha256.hex(it.value) })
	}

	@Test
	fun theCaseNamesBothCapsulesAndTheDefaultAssumptions() {
		written()
		val head = disk.files.getValue("out/case.json")
		assertTrue("\"name\":\"drangler-36702683742 to drangler-36995781138\"" in head, head)
		assertTrue("\"kind\":\"sha\"" in head, head)
		val plan = disk.files.getValue("out/experiments/plan.json")
		assertTrue("\"trialMinutes\":5" in plan && "\"failures\":1" in plan, plan)
	}

	@Test
	fun theOptionsReachTheCase() {
		val args = "diagnose green.json red.json --case o2 --name mine --frame environment " +
			"--trial-minutes 7 --failures 2 --runs 3"
		assertEquals(0, drift(args).statusCode)
		val head = disk.files.getValue("o2/case.json")
		assertTrue("\"name\":\"mine\"" in head && "\"kind\":\"environment\"" in head, head)
		val plan = disk.files.getValue("o2/experiments/plan.json")
		assertTrue("\"trialMinutes\":7" in plan && "\"runs\":3" in plan, plan)
	}

	@Test
	fun diagnoseDetailPrintsTextAtThreeLevels() {
		val summary = drift("diagnose green.json red.json --detail summary")
		val detail = drift("diagnose green.json red.json --detail detail")
		val full = drift("diagnose green.json red.json --detail full")
		assertEquals(SUMMARY, summary.stdout)
		assertEquals(0, detail.statusCode + full.statusCode)
		assertEquals(DETAIL_SHA, Sha256.hex(detail.stdout))
		assertEquals(FULL_SHA, Sha256.hex(full.stdout))
		assertTrue(detail.stdout.startsWith(summary.stdout))
		assertTrue(full.stdout.length > detail.stdout.length)
		assertTrue("probability 0.1" in full.stdout && "probability" !in detail.stdout)
		assertTrue((summary.stdout + detail.stdout + full.stdout).lines().all { it.length <= 100 })
		assertTrue(disk.files.isEmpty())
	}

	@Test
	fun jsonIsTheDefaultAndCannotBeCombinedWithDetail() {
		val plain = drift("diagnose green.json red.json")
		assertEquals(plain.stdout, drift("diagnose green.json red.json --json").stdout)
		val both = drift("diagnose green.json red.json --json --detail full")
		assertEquals(1, both.statusCode)
		assertTrue("--json and --detail cannot be combined" in both.stderr, both.stderr)
	}

	@Test
	fun nextPrintsTheTopExperimentAndTheManualOnes() {
		written()
		val result = drift("next out")
		assertEquals(0, result.statusCode)
		assertTrue(result.stdout.startsWith("Next experiment (a proposal, nothing has been run):"))
		assertTrue("Manual experiments, which Drift cannot run" in result.stdout, result.stdout)
		assertEquals(NEXT_SHA, Sha256.hex(result.stdout))
		val summary = drift("next out --detail summary").stdout
		assertTrue("Write a workflow variant" !in summary && "m1:" in summary, summary)
	}

	@Test
	fun caseShowSummarisesAnIntactCase() {
		written()
		val result = drift("case show out")
		assertEquals(0, result.statusCode)
		val lines = result.stdout.lines()
		assertEquals("case: drangler-36702683742 to drangler-36995781138", lines[0])
		assertEquals(
			"frame: sha, passing drangler-36702683742, failing drangler-36995781138",
			lines[1],
		)
		assertTrue(lines[2].startsWith("observations: 2, chain head "), lines[2])
		assertEquals(
			"integrity: ok, 10 files match the manifest and the chain holds",
			lines[3],
		)
		assertEquals(SHOW_SHA, Sha256.hex(drift("case show out --detail full").stdout))
		assertTrue("hand-set" in result.stdout, result.stdout)
	}

	@Test
	fun anEditedObservationFailsShowAndNextWithTheEntry() {
		written()
		val path = "out/observations/0002.json"
		disk.files[path] = disk.files.getValue(path).replace("failing", "passing")
		val show = drift("case show out")
		assertEquals(1, show.statusCode)
		assertTrue("integrity: 2 problem(s)" in show.stdout, show.stdout)
		assertTrue(
			"observation chain broken at entry 1 (observations/0002.json)" in show.stderr,
			show.stderr,
		)
		val next = drift("next out")
		assertEquals(1, next.statusCode)
		assertTrue("failed its checks" in next.stderr, next.stderr)
		assertTrue("Traceback" !in next.stderr && "at dev.gmitch215" !in next.stderr)
	}

	@Test
	fun unreadableCasesGiveReadableErrors() {
		written()
		val missing = drift("next nowhere")
		assertEquals(1, missing.statusCode)
		assertTrue("cannot read case nowhere: missing case file: manifest.json" in missing.stderr)
		disk.files["out/case.json"] = disk.files.getValue("out/case.json").take(20)
		val cut = drift("case show out")
		assertEquals(1, cut.statusCode)
		assertTrue("cannot read case out: malformed case file case.json" in cut.stderr, cut.stderr)
		val newer = disk.files.getValue("out/manifest.json").replace("\"schema\":1", "\"schema\":2")
		disk.files["out/manifest.json"] = newer
		val schema = drift("next out")
		assertEquals(1, schema.statusCode)
		assertTrue("unsupported case schema 2 in manifest.json" in schema.stderr, schema.stderr)
	}

	@Test
	fun aCaseWithValidHashesButNoPlanIsRefused() {
		written()
		val files = disk.files.filterKeys { it.startsWith("out/") }
			.mapKeys { it.key.removePrefix("out/") }.toMutableMap()
		files["experiments/plan.json"] = "{}"
		files[CaseFile.MANIFEST] = CaseFile.manifest(files)
		for ((p, t) in files) disk.files["forged/$p"] = t
		val result = drift("next forged")
		assertEquals(1, result.statusCode)
		assertTrue(
			"case forged does not hold a ranking and plan: missing field experiments" in
				result.stderr,
			result.stderr,
		)
	}

	@Test
	fun anExistingCaseIsNotOverwritten() {
		written()
		val again = drift("diagnose green.json red.json --case out")
		assertEquals(1, again.statusCode)
		assertTrue("case directory already holds a case: out" in again.stderr, again.stderr)
	}

	@Test
	fun badOptionsAreReadableErrors() {
		val minutes = drift("diagnose green.json red.json --case x --trial-minutes 0")
		assertEquals(1, minutes.statusCode)
		assertTrue("--trial-minutes must be at least 1" in minutes.stderr, minutes.stderr)
		val failures = drift("diagnose green.json red.json --detail full --failures 5 --runs 2")
		assertEquals(1, failures.statusCode)
		assertTrue("--failures must be within 0..--runs" in failures.stderr, failures.stderr)
		assertEquals(1, drift("diagnose green.json red.json --detail loud").statusCode)
		assertTrue(disk.files.isEmpty())
	}

	@Test
	fun aWriterThatFailsNamesTheFile() {
		val failing = object : CaseFiles {
			override fun read(path: String): String? = null

			override fun write(path: String, text: String) = false
		}
		val result = drift("diagnose green.json red.json --case here", failing)
		assertEquals(1, result.statusCode)
		assertTrue("cannot write case file: here/case.json" in result.stderr, result.stderr)
	}

	private fun planRule(dir: String): DecisionRule {
		val plan = CanonicalJson.parse(disk.files.getValue("$dir/experiments/plan.json"))
		return Ingest.rule(plan as JsonObject)!!
	}

	private fun ingestHost(file: String, text: String) = FakeHost(
		files = mapOf("res/$file" to text),
		commands = mapOf(listOf("ls", "-1", "res") to CommandResult(0, "$file\n")),
	)

	@Test
	fun ruleHashIsPrintedByCaseShowAndNext() {
		written()
		val hash = planRule("out").sha256()
		assertTrue("rule sha256: $hash" in drift("case show out").stdout)
		assertTrue("Rule sha256 (ingest names it as ruleSha256): $hash" in drift("next out").stdout)
		assertTrue(hash in drift("next out --detail full").stdout)
		assertTrue(hash !in drift("next out --detail summary").stdout)
	}

	@Test
	fun nextSkipsAnExperimentThatAnIngestedResultAlreadyCovers() {
		written()
		val first = Regex("""Next experiment[^\n]*\n  (e\d+):""").find(drift("next out").stdout)!!
		val id = first.groupValues[1]
		val rule = planRule("out")
		val counts = Counts(0, rule.perArm)
		val text = Ingest.template(id, rule.sha256(), counts, counts)
		val ingested = DriftCommand(ingestHost("r.json", text), disk).test("ingest out res")
		assertEquals(0, ingested.statusCode, ingested.stderr)
		val after = drift("next out").stdout
		val flat = after.replace("\n", " ")
		assertTrue("Already has a recorded result, so not proposed: $id." in flat)
		val again = Regex("""Next experiment[^\n]*\n  (e\d+):""").find(after)
		assertTrue(again == null || again.groupValues[1] != id, after)
	}

	@Test
	fun ingestTemplateWritesOneSkeletonPerOpenExperimentAndRefusesToOverwrite() {
		written()
		val rule = planRule("out")
		val made = drift("ingest out res --template")
		assertEquals(0, made.statusCode, made.stderr)
		val names = made.stdout.lines().filter { it.isNotEmpty() }
		assertTrue(names.isNotEmpty() && names.first().startsWith("e"), made.stdout)
		val file = disk.files.getValue("res/${names.first()}")
		assertTrue("\"ruleSha256\":\"${rule.sha256()}\"" in file, file)
		assertTrue("\"failures\":null" in file && "\"trials\":${rule.perArm}" in file, file)
		val again = drift("ingest out res --template")
		assertEquals(1, again.statusCode)
		assertTrue("results directory already holds" in again.stderr, again.stderr)
		val untouched = DriftCommand(ingestHost(names.first(), file), disk).test("ingest out res")
		assertEquals(1, untouched.statusCode)
		assertTrue("nothing was ingested" in untouched.stderr, untouched.stderr)
	}

	@Test
	fun verifyAndReportNameACaseDirectoryInsteadOfATarError() {
		written()
		for (cmd in listOf("verify out", "verify out/", "report out --out r.html")) {
			val result = drift(cmd)
			assertEquals(1, result.statusCode, cmd)
			val err = result.stderr.replace("out/ is", "out is")
			assertTrue("out is a case directory; expected a .driftcase archive" in err, err)
			assertTrue("drift pack out --out file.driftcase" in result.stderr, result.stderr)
		}
	}

	private companion object {
		const val DIAGNOSE_SHA = "717191f28a8d593ecf8a5fcedee04b4471187cddfe29718dbd2e38e6676de52e"
		const val SUMMARY = "8 changes differ with the same support, and tool.node.version " +
			"ranks first " +
			"only by hand-set weights,\nso the data does not name one cause.\n"
		const val DETAIL_SHA = "9f27f7de1e40109a5e2a8109cb32986cdfa8498d5305a675ecce93f647d169b5"
		const val FULL_SHA = "a1523509c9b22e25b5f6bb984d7284c1a5f74549518f3791966c8a3d1e627a39"
		const val NEXT_SHA = "afd3f5c6604c69b0a8b820f1f3457c803afad42823ba50300058b43fb16f43be"
		const val SHOW_SHA = "8059e124bbf9187dcce77e1089c21be0a42f1303a4285508cf84d494f723a6b8"
		val CASE_SHAS = mapOf<String, String>(
			"case.json" to
				"155b274f7a63ffd8504b0926fc7c037a4bbb47eb7bee9d2b52aab263746866ad",
			"eliminations/excluded.json" to
				"27da8767ca6eba19995dee75c5326e45feb09a647eaec7a68a00247ea93d5bb8",
			"environments/failing.json" to
				"40380c709345110b5af7fd2c2af356b387b9143a25518407b6704b84091a4cc7",
			"environments/passing.json" to
				"f622259ce0290e47cf3c44a9689dbbddac72d7f6f2930623f27ffc725f49d2cf",
			"experiments/plan.json" to
				"215802d0eadb463d7373f3e037c6e7c9f28ce5d318c878533dd73310d697d375",
			"hypotheses/prior.json" to
				"e9ab55630b8fbecb9fd5ac7032226da6e1a5080e4908acb9b97a39d03501aa98",
			"hypotheses/ranking.json" to
				"e0f04d1dbd2e7f420b5ab9ab5a50fb732c8cb748668245226912cc01886c66e2",
			"manifest.json" to
				"8dc95d6bb1ffac4f90b4e665a66c51707805039947ae6543966bda346424cd9e",
			"observations/0001.json" to
				"fba59c9eb763701f21af088b3aa24736721e56792d1b4bf68d341a3b3abfdb4f",
			"observations/0002.json" to
				"06ad04b2cb47458e56184c95297ed83852eb07a6a61bdea1f3f56e64f4671768",
			"results/index.json" to
				"5021e624e752b001ce3e3846e8f158ed4aeb93a4c9a72fdb35a0c5b14a0eea84",
		)
	}
}
