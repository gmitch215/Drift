package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReproduceCommandTest {
	private val digest = "sha256:" + "ab".repeat(32)
	private val small = Capsule(
		"small",
		listOf(
			Attribute("tool.node.version", "22.12.0", "test"),
			Attribute("env.API_TOKEN", "plain-secret-value", "test"),
			Attribute("env.NODE_ENV", "test", "test"),
			Attribute("cgroup.memory.max", "536870912", "test"),
		),
	).canonical()

	private val host = FakeHost(
		canRun = false,
		files = mapOf(
			"red.json" to Fixtures.text("36995781138.json"),
			"small.json" to small,
			"bad.json" to "{not json",
			"digests.json" to "{\"node:22.12.0-bookworm-slim\":\"$digest\",\"x:1\":\"$digest\"}",
			"digests-bad.json" to "{\"node:22.12.0-bookworm-slim\":\"sha256:abc\"}",
			"digests-notjson.json" to "[1]",
		),
	)
	private val disk = MemoryCaseFiles()

	private fun drift(args: String, files: CaseFiles = disk) = DriftCommand(host, files).test(args)

	private fun tree(dir: String) =
		disk.files.filterKeys { it.startsWith("$dir/") }.mapKeys { it.key.removePrefix("$dir/") }

	@Test
	fun writesTheThreeFilesAndPrintsTheSummary() {
		val result = drift("reproduce small.json --run \"npm test\" --out out")
		assertEquals(0, result.statusCode, result.stderr)
		assertEquals("wrote 3 files to out\n", result.stderr)
		assertEquals(setOf("Dockerfile", "run.sh", "manifest.json"), tree("out").keys)
		assertTrue(result.stdout.startsWith("4 attributes: 2 mirrored, 0 partially mirrored,"))
		assertTrue("2 not mirrored" in result.stdout.replace("\n  ", " "), result.stdout)
		assertTrue("FROM node:22.12.0-bookworm-slim\n" in tree("out").getValue("Dockerfile"))
		assertTrue("sh -c 'npm test'\n" in tree("out").getValue("run.sh"))
		val manifest = CanonicalJson.parse(tree("out").getValue("manifest.json")).obj()
		assertEquals("npm test", manifest.require("command").string())
	}

	@Test
	fun aSecretValueIsNotWrittenAnywhere() {
		val result = drift("reproduce small.json --run \"npm test\" --out out --detail full")
		assertEquals(0, result.statusCode)
		val everything = result.stdout + result.stderr + disk.files.values.joinToString("\n")
		assertFalse("plain-secret-value" in everything)
		assertTrue("env.API_TOKEN" in result.stdout)
	}

	@Test
	fun envIsOptInByNameOrAll() {
		val off = drift("reproduce small.json --run x --out v0")
		assertFalse("NODE_ENV" in tree("v0").getValue("run.sh"))
		assertEquals("wrote 3 files to v0\n", off.stderr)
		val named = drift("reproduce small.json --run x --out v1 --env NODE_ENV")
		assertEquals(0, named.statusCode, named.stderr)
		assertTrue("--env 'NODE_ENV=test' \\\n" in tree("v1").getValue("run.sh"))
		assertEquals("wrote 3 files to v1\n", named.stderr)
		val all = drift("reproduce small.json --run x --out v2 --env-all")
		assertEquals(0, all.statusCode, all.stderr)
		assertTrue("warning: --env-all mirrors every env.* value" in all.stderr, all.stderr)
		assertTrue("--env 'NODE_ENV=test' \\\n" in tree("v2").getValue("run.sh"))
		val written = all.stdout + all.stderr + disk.files.values.joinToString("\n")
		assertFalse("plain-secret-value" in written)
		val unknown = drift("reproduce small.json --run x --out v3 --env NOPE")
		assertEquals(1, unknown.statusCode)
		assertTrue("--env NOPE does not name an env.* attribute" in unknown.stderr)
		assertTrue(tree("v3").isEmpty())
	}

	@Test
	fun theDetailLevelsGrow() {
		val summary = drift("reproduce small.json --run x --out a --detail summary").stdout
		val detail = drift("reproduce small.json --run x --out b --detail detail").stdout
		val full = drift("reproduce small.json --run x --out c --detail full").stdout
		assertTrue(summary.length < detail.length && detail.length < full.length)
		assertTrue("Not mirrored (2), by reason:" in detail)
		assertTrue("Mirrored (2):" in full && "docker run flags from run.sh:" in full)
		assertEquals(summary, drift("reproduce small.json --run x --out d").stdout)
		assertEquals(1, drift("reproduce small.json --run x --out e --detail loud").statusCode)
	}

	@Test
	fun theDranglerCapsuleSaysMostOfItCannotBeMirrored() {
		val result = drift("reproduce red.json --run \"npm test\" --out dr --detail detail")
		assertEquals(0, result.statusCode, result.stderr)
		val text = result.stdout.replace("\n  ", " ")
		assertTrue(
			"30 attributes: 2 mirrored, 0 partially mirrored, 28 not mirrored; base image " +
				"node:24.21.0-bookworm-slim" in text,
			text,
		)
		assertTrue("hosted runner attribute" in text && "ci.runner.image.version" in text, text)
	}

	@Test
	fun digestsPinTheBaseImage() {
		val result = drift("reproduce small.json --run x --out p --digests digests.json")
		assertEquals(0, result.statusCode, result.stderr)
		assertTrue("FROM node:22.12.0-bookworm-slim@$digest\n" in tree("p").getValue("Dockerfile"))
		val args = "reproduce small.json --run x --out q --digests digests.json --detail detail"
		val detail = drift(args)
		assertTrue("Digests not used: x:1" in detail.stdout.replace("\n  ", " "))
	}

	@Test
	fun anExistingReproductionIsNotOverwritten() {
		assertEquals(0, drift("reproduce small.json --run x --out out").statusCode)
		for (name in listOf("Dockerfile", "run.sh", "manifest.json")) {
			val files = MemoryCaseFiles(mutableMapOf("o2/$name" to "mine"))
			val again = drift("reproduce small.json --run x --out o2", files)
			assertEquals(1, again.statusCode, name)
			assertTrue("output directory already holds a reproduction: o2" in again.stderr)
			assertEquals(mapOf("o2/$name" to "mine"), files.files)
		}
		assertEquals(1, drift("reproduce small.json --run x --out out/").statusCode)
	}

	@Test
	fun forceReplacesOnlyFilesDriftWroteAndSaysSo() {
		assertEquals(0, drift("reproduce small.json --run x --out out").statusCode)
		val refused = drift("reproduce small.json --run x --out out")
		assertEquals(1, refused.statusCode)
		assertTrue("Dockerfile, run.sh, manifest.json" in refused.stderr, refused.stderr)
		assertTrue("pass --force to replace those files" in refused.stderr, refused.stderr)
		val forced = drift("reproduce small.json --run y --out out --force")
		assertEquals(0, forced.statusCode, forced.stderr)
		assertTrue("replacing Dockerfile, run.sh, manifest.json in out" in forced.stderr)
		assertTrue("y" in disk.files.getValue("out/run.sh"))
		disk.files["out/notes.txt"] = "keep"
		assertEquals(0, drift("reproduce small.json --run z --out out --force").statusCode)
		assertEquals("keep", disk.files.getValue("out/notes.txt"))
		val mine = MemoryCaseFiles(mutableMapOf("o3/Dockerfile" to "FROM scratch"))
		val foreign = drift("reproduce small.json --run x --out o3 --force", mine)
		assertEquals(1, foreign.statusCode)
		assertTrue("Dockerfile in o3 is not one" in foreign.stderr, foreign.stderr)
		assertEquals(mapOf("o3/Dockerfile" to "FROM scratch"), mine.files)
	}

	@Test
	fun helpMarksRunAndOutRequiredAndShowsDefaults() {
		val help = drift("reproduce --help").stdout
		assertTrue(
			Regex("--run=<text>\\s+test command[^\\n]*\\(required\\)").containsMatchIn(
			help.replace("\n                    ", " "),
		),
			help,
		)
		assertTrue("(default: summary)" in help.replace("\n                    ", " "), help)
	}

	@Test
	fun problemsAreReadableErrors() {
		val cases = mapOf(
			"reproduce nope.json --run x --out e1" to "cannot read capsule file: nope.json",
			"reproduce bad.json --run x --out e2" to "invalid capsule file bad.json",
			"reproduce small.json --run \"  \" --out e3" to "the test command is empty",
			"reproduce small.json --run x --out e4 --digests nope.json" to
				"cannot read digests file: nope.json",
			"reproduce small.json --run x --out e5 --digests digests-bad.json" to
				"digest for node:22.12.0-bookworm-slim is not sha256:<64 hex digits>",
			"reproduce small.json --run x --out e6 --digests digests-notjson.json" to
				"invalid digests file digests-notjson.json",
		)
		for ((args, message) in cases) {
			val result = drift(args)
			assertEquals(1, result.statusCode, args)
			assertTrue(message in result.stderr, "$args: ${result.stderr}")
			assertFalse("at dev.gmitch215" in result.stderr)
		}
		assertTrue(disk.files.isEmpty())
	}

	@Test
	fun missingOptionsAreUsageErrors() {
		assertEquals(1, drift("reproduce small.json --out x").statusCode)
		assertEquals(1, drift("reproduce small.json --run x").statusCode)
		assertEquals(1, drift("reproduce --run x --out y").statusCode)
	}

	@Test
	fun aWriterThatFailsNamesTheFile() {
		val failing = object : CaseFiles {
			override fun read(path: String): String? = null

			override fun write(path: String, text: String) = false
		}
		val result = drift("reproduce small.json --run x --out here", failing)
		assertEquals(1, result.statusCode)
		assertTrue("cannot write file: here/" in result.stderr, result.stderr)
	}

	@Test
	fun captureWithToolsRecordsTheToolchain() {
		val tools = FakeHost(
			commands = mapOf(listOf("node", "--version") to CommandResult(0, "v22.12.0\n")),
		)
		val plain = DriftCommand(tools).test("capture")
		val withTools = DriftCommand(tools).test("capture --tools")
		assertFalse("tool.node.version" in plain.stdout)
		assertTrue("\"path\":\"tool.node.version\"" in withTools.stdout, withTools.stdout)
	}
}
