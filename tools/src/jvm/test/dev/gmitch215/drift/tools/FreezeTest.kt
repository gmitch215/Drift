package dev.gmitch215.drift.tools

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreezeTest {
	private val sha = "0123456789abcdef0123456789abcdef01234567"
	private val secret = "gh" + "p_" + "Zy9".repeat(12)
	private val internal = "vault.corp-internal.io"

	private fun log(job: String, tag: String): String {
		val body = listOf(
			"##[group]Runner Image Provisioner",
			"Hosted Compute Agent",
			"Version: 20260828.587",
			"Build Date: 2026-08-28T16:44:25Z",
			"Worker ID: {6f1d2c3b-4a59-4e8d-9c7b-0a1b2c3d4e5f}",
			"Azure Region: eastus",
			"##[endgroup]",
			"##[group]Operating System",
			"Ubuntu",
			"24.04.5",
			"LTS",
			"##[endgroup]",
			"##[group]Runner Image",
			"Image: ubuntu-24.04",
			"Version: 20260920.314.1",
			"##[endgroup]",
			"Current runner version: '2.337.0'",
			"##[group]Run actions/checkout@v7",
			"env:",
			"  REQUIRE_DOCKER: 1",
			"  CC_TOKEN: ***",
			"##[endgroup]",
			"git version 2.55.0",
			"##[group]Run bun install --frozen-lockfile",
			"bun install v1.4.2 (744846f84)",
			"+ wrangler@4.123.0",
			"+ @drupflare/burrow@1.1.1",
			"134 packages installed [1344.00ms]",
			"##[endgroup]",
			"leak $secret to $internal for $tag",
		)
		return body.mapIndexed { i, text ->
			val second = (10 + i).toString().padStart(2, '0')
			"$job\tUNKNOWN STEP\t2026-09-30T10:28:$second.0000000Z $text"
		}.joinToString("\n", postfix = "\n")
	}

	private fun step(n: Long, name: String, conclusion: String, start: String, end: String) = obj(
			"completedAt" to JsonString(end),
			"conclusion" to JsonString(conclusion),
			"name" to JsonString(name),
			"number" to JsonInt(n),
			"startedAt" to JsonString(start),
			"status" to JsonString("completed"),
		)

	private fun summary(conclusion: String): String {
		val steps = listOf(
			step(1, "Set up job", "success", "2026-09-30T10:28:59Z", "2026-09-30T10:29:00Z"),
			step(2, "Run $internal", conclusion, "2026-09-30T10:29:06Z", "2026-09-30T10:29:20Z"),
		)
		val job = obj(
			"completedAt" to JsonString("2026-09-30T10:29:23Z"),
			"conclusion" to JsonString(conclusion),
			"databaseId" to JsonInt(7),
			"name" to JsonString("Docker E2E"),
			"startedAt" to JsonString("2026-09-30T10:28:58Z"),
			"steps" to JsonArray(steps),
			"url" to JsonString("https://github.com/o/r/actions/runs/42/job/7"),
		)
		return CanonicalJson.encode(
			obj(
				"conclusion" to JsonString(conclusion),
				"databaseId" to JsonInt(42),
				"event" to JsonString("schedule"),
				"headSha" to JsonString(sha),
				"jobs" to JsonArray(listOf(job)),
			),
		)
	}

	private fun withRaw(block: (Path, Path) -> Unit) {
		val dir = createTempDirectory("drift-freeze")
		try {
			val raw = dir.resolve("raw").createDirectories()
			raw.resolve("run-42.log").writeText(log("Docker E2E", "a"))
			raw.resolve("run-42.json").writeText(summary("failure"))
			raw.resolve("diff-a1b2c3d-e4f5a6b.patch").writeText(
				"diff --git a/p.json b/p.json\nindex 1..2 100644\n--- a/p.json\n+++ b/p.json\n" +
					"@@ -1,2 +1,2 @@ ctx\n context\n-\"v\": \"1\"\n+\"v\": \"2\" $internal\n",
			)
			block(raw, dir.resolve("out"))
		} finally {
			dir.toFile().deleteRecursively()
		}
	}

	@Test
	fun freezeWritesSanitizedFilesAndAReviewWithoutTheSeededSecret() {
		withRaw { raw, out ->
			val reports = Freeze.run(raw, out)
			assertEquals(
				listOf("logs/42.log", "runs/42.json", "diffs/a1b2c3d-e4f5a6b.diff"),
				reports.map {
				it.name
			},
			)
			val files = out.walk().filter { it.isRegularFile() }.toList()
			assertEquals(4, files.size)
			for (file in files) {
				val text = file.readText()
				assertFalse(text.contains(secret), file.toString())
				assertFalse(text.contains("corp-internal"), file.toString())
			}
			val review = out.resolve("README.md").readText()
			assertFalse(review.contains(secret))
			assertTrue(review.startsWith("# Drangler Fixtures"))
			assertTrue(review.contains("| redactor |"))
			assertTrue(review.contains("| url-host |") || review.contains("| hostname |"))
		}
	}

	@Test
	fun runSummaryLosesJobUrlsAndIdsButKeepsStepsAndTheHeadSha() {
		withRaw { raw, out ->
			Freeze.run(raw, out)
			val json = CanonicalJson.parse(out.resolve("runs/42.json").readText()) as JsonObject
			val job = ((json.fields.getValue("jobs") as JsonArray).items.single() as JsonObject)
			assertFalse(job.fields.containsKey("url"))
			assertFalse(job.fields.containsKey("databaseId"))
			assertEquals(2, (job.fields.getValue("steps") as JsonArray).items.size)
			assertTrue(out.resolve("runs/42.json").readText().contains(sha))
		}
	}

	@Test
	fun capsulesFollowFromTheFrozenFiles() {
		withRaw { raw, out ->
			Freeze.run(raw, out)
			assertEquals(listOf("42"), Capsules.generate(out))
			val capsule = Capsule.parse(out.resolve("capsules/42.json").readText())
			val values = capsule.attributes.associate { it.path to it.value }
			assertEquals("20260920.314.1", values["ci.runner.image.version"])
			assertEquals("20260828.587", values["ci.provisioner.version"])
			assertEquals("eastus", values["ci.runner.region"])
			assertEquals("2.337.0", values["ci.runner.version"])
			assertEquals("24.04.5", values["os.version"])
			assertEquals("4.123.0", values["deps.wrangler.version"])
			assertEquals("1", values["env.REQUIRE_DOCKER"])
			assertEquals(null, values["env.CC_TOKEN"])
			assertEquals(null, values["deps.<private-pkg>.version"])
			val descriptor = CanonicalJson.parse(out.resolve("capsules/42.run.json").readText())
			val fields = (descriptor as JsonObject).fields
			assertEquals("failure", (fields.getValue("outcome") as JsonString).value)
			assertEquals(
				JsonInt(25),
				fields.getValue("durationSeconds"),
			)
		}
	}

	@Test
	fun aJobWithNoEnvironmentLinesYieldsAnEmptyCapsule() {
		val capsule = RunnerCapsule.build(log("Other Job", "b"), "x")
		assertEquals(emptyList(), capsule.attributes)
	}

	@Test
	fun aLogWithoutTheBlocksYieldsOnlyWhatItHas() {
		val line = "Docker E2E\tUNKNOWN STEP\t2026-09-30T10:28:59.0000000Z git version 2.55.0\n"
		val capsule = RunnerCapsule.build(line, "x")
		assertEquals(listOf("tool.git.version"), capsule.attributes.map { it.path })
	}
}
