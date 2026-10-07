package dev.gmitch215.drift.tools

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.DiffKind
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.model.Capsule
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureTest {
	private val root: Path = Path.of("..", "fixtures", "drangler")
	private val green = "36702683742"
	private val reds = listOf("36995781138", "37114464625", "37195797966")
	private val runs = listOf(
		"35585969640",
		"36232485230",
		"36311117223",
		"36412389380",
		green,
	) + reds

	private fun capsule(id: String) = Capsule.parse(root.resolve("capsules/$id.json").readText())

	private fun value(capsule: Capsule, path: String) =
		capsule.attributes.first { it.path == path }.value

	private fun heads(): Set<String> = runs.map {
		val summary = CanonicalJson.parse(root.resolve("runs/$it.json").readText()) as JsonObject
		(summary.fields.getValue("headSha") as JsonString).value
	}.toSet()

	private fun assertClean(files: List<Path>) {
		assertTrue(files.isNotEmpty())
		val heads = heads()
		for (file in files) {
			assertEquals(emptyList(), LogSanitizer.residue(file.readText(), heads), file.name)
		}
	}

	@Test
	fun greenLogsAreFreeOfResidue() = assertClean(runs.take(5).map { root.resolve("logs/$it.log") })

	@Test
	fun redLogsAreFreeOfResidue() = assertClean(reds.map { root.resolve("logs/$it.log") })

	@Test
	fun summariesCapsulesAndDiffsAreFreeOfResidue() {
		val files = root.walk().filter { it.isRegularFile() }.toList()
		val rest = files.filter { it.extension != "md" && it.extension != "log" }
		assertTrue(rest.size >= runs.size * 3)
		assertClean(rest)
	}

	@Test
	fun readmeProseMatchesTheGenerator() {
		val sample = listOf(FileReport("logs/1.log", 2, 1, 1, 1, mapOf("uuid" to 1), mapOf()))
		fun prose(text: String) = text.lines().filterNot { it.startsWith("|") }
		val committed = root.resolve("README.md").readText()
		assertEquals(prose(Review.render(sample)), prose(committed))
	}

	@Test
	fun readmeHasItsSectionsAndNoResidue() {
		val text = root.resolve("README.md").readText()
		val headings = text.lines().filter { it.startsWith("## ") }
		assertEquals(
			listOf("Layout", "Sanitization", "Removals", "Files", "Kept", "Not Included")
				.plus("Regenerating")
				.map { "## $it" },
			headings,
		)
		assertEquals(emptyList(), LogSanitizer.residue(text, heads()))
	}

	@Test
	fun readmeFileTableMatchesTheFixtures() {
		val rows = root.resolve("README.md").readText().lines()
			.filter { Regex("^\\| (logs|runs|diffs)/").containsMatchIn(it) }
			.map { line -> line.split('|').map { it.trim() } }
		assertEquals(runs.size * 2 + 1, rows.size)
		for (cells in rows) {
			val text = root.resolve(cells[1]).readText()
			assertEquals(text.toByteArray().size, cells[3].toInt(), cells[1])
			assertEquals(text.removeSuffix("\n").split('\n').size, cells[5].toInt(), cells[1])
		}
	}

	@Test
	fun capsulesRegenerateByteForByteFromTheSanitizedLogs() {
		for (id in runs) {
			val log = root.resolve("logs/$id.log").readText()
			val built = RunnerCapsule.build(log, "drangler-$id")
			assertEquals(root.resolve("capsules/$id.json").readText(), built.canonical() + "\n", id)
			val summary = CanonicalJson.parse(root.resolve("runs/$id.json").readText())
			assertEquals(
				root.resolve("capsules/$id.run.json").readText(),
				CanonicalJson.encode(RunDescriptor.of(summary as JsonObject)) + "\n",
				id,
			)
		}
	}

	@Test
	fun selfDiffIsEmptyAndTheHashRoundTrips() {
		for (id in runs) {
			val c = capsule(id)
			assertTrue(CapsuleDiff.diff(c, c).isEmpty, id)
			assertEquals(c.hash(), Capsule.parse(c.canonical()).hash(), id)
		}
	}

	@Test
	fun threeAttributesPerCapsuleMatchTheLogs() {
		val expected = mapOf(
			"35585969640" to Triple("20260907.300.1", "20260828.587", "centralus"),
			"36232485230" to Triple("20260920.314.1", "20260828.587", "eastus"),
			"36311117223" to Triple("20260920.314.1", "20260828.587", "westcentralus"),
			"36412389380" to Triple("20260920.314.1", "20260828.587", "westus2"),
			green to Triple("20260920.314.1", "20260828.587", "eastus"),
			"36995781138" to Triple("20260927.320.1", "20260901.588", "eastus"),
			"37114464625" to Triple("20260927.320.1", "20260901.588", "westcentralus"),
			"37195797966" to Triple("20260927.320.1", "20260901.588", "northcentralus"),
		)
		for ((id, triple) in expected) {
			val c = capsule(id)
			assertEquals(triple.first, value(c, "ci.runner.image.version"), id)
			assertEquals(triple.second, value(c, "ci.provisioner.version"), id)
			assertEquals(triple.third, value(c, "ci.runner.region"), id)
		}
	}

	@Test
	fun greenAgainstEachRedShowsTheImageProvisionerAndDependencyDelta() {
		val changed = listOf(
			"ci.provisioner.build-date" to DiffKind.CHANGED,
			"ci.provisioner.version" to DiffKind.CHANGED,
			"ci.runner.image.version" to DiffKind.CHANGED,
			"tool.node.version" to DiffKind.ADDED,
			"tool.npm.version" to DiffKind.ADDED,
			"deps.@napi-rs/keyring.version" to DiffKind.ADDED,
			"deps.prettier-plugin-sh.version" to DiffKind.CHANGED,
			"deps.wrangler.version" to DiffKind.CHANGED,
			"tool.yarn.version" to DiffKind.ADDED,
		)
		for (red in reds) {
			val diff = CapsuleDiff.diff(capsule(green), capsule(red))
			assertEquals(
				changed.sortedBy { it.first },
				diff.changes.map { it.path to it.kind }.sortedBy { it.first },
				red,
			)
			assertTrue(diff.probes.isEmpty())
		}
	}

	@Test
	fun theRegionIsTheOnlyIgnoredDifference() {
		val ignored = reds.associateWith {
			CapsuleDiff.diff(capsule(green), capsule(it)).ignored.map { d -> d.path }
		}
		assertEquals(emptyList(), ignored.getValue("36995781138"))
		assertEquals(listOf("ci.runner.region"), ignored.getValue("37114464625"))
		assertEquals(listOf("ci.runner.region"), ignored.getValue("37195797966"))
	}

	@Test
	fun greenNightliesDifferOnlyByHostFacts() {
		val older = CapsuleDiff.diff(capsule(green), capsule("35585969640"))
		assertEquals(
			listOf("ci.runner.image.version"),
			older.changes.map { it.path },
		)
		for (id in listOf("36232485230", "36311117223", "36412389380")) {
			assertTrue(CapsuleDiff.diff(capsule(green), capsule(id)).changes.isEmpty(), id)
		}
	}

	@Test
	fun runDescriptorsNameOutcomeFailingStepAndDuration() {
		fun descriptor(id: String) =
			CanonicalJson.parse(root.resolve("capsules/$id.run.json").readText()) as JsonObject

		val ok = descriptor(green)
		assertEquals("success", (ok.fields.getValue("outcome") as JsonString).value)
		assertEquals(JsonNull, ok.fields.getValue("failingStep"))
		val red = descriptor("36995781138")
		assertEquals(
			"Run Integration Suite with Coverage",
			(red.fields.getValue("failingStep") as JsonString).value,
		)
		assertEquals(
			JsonInt(239),
			red.fields.getValue("durationSeconds"),
		)
	}
}
