package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.CaseStore
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.command.SolveCommand
import dev.gmitch215.drift.cli.command.verdictExitCode
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.lab.Counts
import dev.gmitch215.drift.lab.DecisionRule
import dev.gmitch215.drift.lab.FakeExecutor
import dev.gmitch215.drift.lab.Ingest
import dev.gmitch215.drift.lab.RawTrial
import dev.gmitch215.drift.lab.SolveCertificate
import dev.gmitch215.drift.lab.VerdictKind
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SolveCommandTest {
	private val green = LabCapsules.node
	private val red = Capsule(
		"lab-node-red",
		green.attributes.filter { it.path != "env.TZ" && it.path != "env.LANG" } +
			listOf(
				Attribute("env.TZ", "America/New_York", "test"),
				Attribute("env.LANG", "en_US.UTF-8", "test"),
			),
	)
	private val disk = MemoryCaseFiles()
	private val executor = FakeExecutor(
		behave = { arm, _ ->
			val fails = arm.capsule.attributes.any {
				it.path == "env.TZ" && it.value == "America/New_York"
			}
			RawTrial(if (fails) 1 else 0, if (fails) "wrong day" else "ok", 1)
		},
	)
	private val results = mutableMapOf<String, String>()
	private val host = FakeHost(
		files = mapOf(
			"green.json" to green.canonical(),
			"red.json" to red.canonical(),
			"bad.json" to "{nope",
		),
		commands = mapOf(listOf("ls", "-1", "results") to CommandResult(0, "r1.json\nnotes.txt\n")),
	)

	private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

	private fun solve(extra: String = "", exec: FakeExecutor = executor) = SolveCommand(
		host,
		disk,
		exec,
	).test(
		"--run \"sh t.sh\" --fail-when \"wrong day\" --pilot 6 --case case $extra " +
			"green.json red.json",
	)

	@Test
	fun solvePrintsTheVerdictAndWritesACaseThatChecksOut() {
		val result = solve()
		assertEquals(0, result.statusCode, result.stderr)
		assertTrue(
			result.stdout.startsWith("CONFIRMED: this dimension causes the failure: env.TZ."),
			result.stdout,
		)
		assertTrue("in containers" in flat(result.stdout))
		assertTrue("not on the original host" in flat(result.stdout))
		assertTrue(result.stderr.startsWith("wrote "), result.stderr)
		val case = (CaseStore.read(disk, "case") as dev.gmitch215.drift.case.CaseRead.Loaded).case
		assertEquals(emptyList(), case.check())
		assertEquals(emptyList(), SolveCertificate.check(case))
		assertNotNull(case.text("certificate.json"))
	}

	@Test
	fun theDetailLevelsAddTheExperimentsAndTheRules() {
		val summary = solve().stdout
		val detail = SolveCommand(host, MemoryCaseFiles(), executor).test(
			"--run \"sh t.sh\" --fail-when \"wrong day\" --pilot 6 --case c2 --detail detail " +
				"green.json red.json",
		).stdout
		val full = SolveCommand(host, MemoryCaseFiles(), executor).test(
			"--run \"sh t.sh\" --fail-when \"wrong day\" --pilot 6 --case c3 --detail full " +
				"green.json red.json",
		).stdout
		assertTrue(detail.startsWith(summary))
		assertTrue("Experiments (failures of trials, raw counts):" in detail)
		assertTrue("Mechanism: unexplained" in detail)
		assertTrue("Preregistered rules" in full && "Preregistered rules" !in detail)
		assertTrue(detail.lines().all { it.length <= 100 })
	}

	@Test
	fun optionsAreCheckedBeforeAnythingRuns() {
		val ran = FakeExecutor { _, _ -> RawTrial(0, "", 1) }
		assertTrue(
			solve("--budget trials=x", ran).stderr.contains("bad --budget item: trials=x"),
		)
		assertTrue(solve("--budget runs=5", ran).stderr.contains("--budget takes trials=N"))
		val badPattern = SolveCommand(host, disk, ran).test(
			"--run t --fail-when \"(\" --case other green.json red.json",
		)
		assertEquals(1, badPattern.statusCode)
		assertTrue("not a valid pattern" in badPattern.stderr)
		assertEquals(1, solve("--pilot 2", ran).statusCode)
		val missing = SolveCommand(host, disk, ran).test(
			"--run t --fail-when exit --case other green.json nope.json",
		)
		assertTrue("cannot read capsule file: nope.json" in missing.stderr)
		val broken = SolveCommand(host, disk, ran).test(
			"--run t --fail-when exit --case other bad.json red.json",
		)
		assertTrue("invalid capsule file bad.json" in broken.stderr)
		assertTrue(ran.ran.isEmpty(), "no trial ran for a bad option")
	}

	@Test
	fun aCaseDirectoryThatHoldsACaseIsNotOverwritten() {
		solve()
		val again = solve()
		assertEquals(1, again.statusCode)
		assertTrue("case directory already holds a case: case" in again.stderr)
	}

	@Test
	fun anExecutorProblemIsAStuckVerdictNotAStackTrace() {
		val result = solve(
			exec = FakeExecutor(problem = { "docker is not installed" }) { _, _ ->
			RawTrial(0, "", 1)
		},
		)
		assertEquals(0, result.statusCode)
		assertTrue(result.stdout.startsWith("STUCK:"), result.stdout)
		assertTrue("docker is not installed" in flat(result.stdout))
	}

	@Test
	fun exitStatusIsOptInAndMapsTheVerdict() {
		assertEquals(
			listOf(0, 10, 11, 12),
			listOf(
				VerdictKind.CONFIRMED,
				VerdictKind.BUNDLE,
				VerdictKind.NARROWED,
				VerdictKind.STUCK,
			).map(::verdictExitCode),
		)
		assertEquals(0, solve("--exit-status").statusCode)
		val stuck = FakeExecutor(problem = { "docker is not installed" }) { _, _ ->
			RawTrial(0, "", 1)
		}
		assertEquals(0, solve("--case c8", stuck).statusCode)
		val gated = solve("--exit-status --case c9", stuck)
		assertEquals(12, gated.statusCode)
		assertTrue(gated.stdout.startsWith("STUCK:"), gated.stdout)
	}

	@Test
	fun solveAndIngestAreRegisteredOnTheDriftCommand() {
		val help = DriftCommand(host, disk).test("--help").stdout
		assertTrue("solve" in help && "ingest" in help)
	}

	@Test
	fun ingestAddsAnExternalResultToTheSolvedCaseAndRefusesABadOne() {
		solve()
		val text = disk.files.getValue("case/experiments/plan.json")
		val plan = CanonicalJson.parse(text) as JsonObject
		val trials = plan.fields.getValue("trials") as JsonObject
		fun n(key: String) = (trials.fields.getValue(key) as dev.gmitch215.drift.json.JsonInt).value
		val rule = DecisionRule(n("perArm").toInt(), n("threshold").toInt(), n("alpha"), n("rate"))
		val none = Counts(0, rule.perArm)
		val good = Ingest.template("e1", rule.sha256(), none, none)
		val withResults = FakeHost(
			files = mapOf("results/r1.json" to good, "results/notes.txt" to "x"),
			commands = mapOf(
				listOf("ls", "-1", "results") to CommandResult(0, "r1.json\nnotes.txt\n"),
			),
		)
		val ok = DriftCommand(withResults, disk).test("ingest case results")
		assertEquals(0, ok.statusCode, ok.stderr)
		assertTrue(ok.stdout.startsWith("e1: refuted; control 0 of ${rule.perArm}"), ok.stdout)
		assertTrue("CONFIRMED:" in ok.stdout, "a confirmed verdict stays confirmed")
		val again = DriftCommand(withResults, disk).test("ingest case results")
		assertEquals(1, again.statusCode)
		assertTrue("a result for e1 is already in the case" in again.stderr, again.stderr)
		val tamper = disk.files.getValue("case/results/0001.json")
		disk.files["case/results/0001.json"] = tamper.replace("\"fail", "\"pass")
		val refused = DriftCommand(withResults, disk).test("ingest case results")
		assertEquals(1, refused.statusCode)
		assertTrue("failed its checks" in refused.stderr, refused.stderr)
		assertFalse(
			CaseFile(
				disk.files.filterKeys { it.startsWith("case/") }
			.mapKeys { it.key.removePrefix("case/") },
			).check().isEmpty(),
		)
	}

	@Test
	fun ingestReportsAMissingDirectoryAndAnEmptyOne() {
		solve()
		val none = DriftCommand(
			FakeHost(commands = mapOf(listOf("ls", "-1", "results") to CommandResult(2, ""))),
			disk,
		).test("ingest case results")
		assertTrue("cannot list results directory: results" in none.stderr)
		val empty = DriftCommand(
			FakeHost(
				commands = mapOf(listOf("ls", "-1", "results") to CommandResult(0, "a.txt\n")),
			),
			disk,
		).test("ingest case results")
		assertTrue("no *.json result files in results" in empty.stderr)
		val gone = DriftCommand(host, MemoryCaseFiles()).test("ingest nowhere results")
		assertTrue("cannot read case nowhere" in gone.stderr)
		assertTrue(JsonString("x").value == "x")
	}
}
