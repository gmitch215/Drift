package dev.gmitch215.drift.tools

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.string
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileReport(
	val name: String,
	val rawBytes: Int,
	val outBytes: Int,
	val linesIn: Int,
	val linesOut: Int,
	val counts: Map<String, Int>,
	val examples: Map<String, String>,
)

class LeakException(message: String) : Exception(message)

/** Turns a directory of raw CI logs and `gh` JSON into the sanitized files under fixtures. */
object Freeze {
	fun run(rawDir: Path, outDir: Path): List<FileReport> {
		val reports = mutableListOf<FileReport>()
		val ids = rawDir.listDirectoryEntries("run-*.log").map { it.nameWithoutExtension }.sorted()
		for (name in ids) {
			val id = name.removePrefix("run-")
			val summary = CanonicalJson.parse(rawDir.resolve("run-$id.json").readText()).obj()
			val keep = setOf(summary.fields.getValue("headSha").string())
			val log = rawDir.resolve("run-$id.log").readText()
			val cleaned = LogSanitizer.sanitize(log, keep)
			write(outDir.resolve("logs/$id.log"), cleaned, keep)
			reports += report("logs/$id.log", log, cleaned)

			val json = summaryJson(summary, keep)
			write(outDir.resolve("runs/$id.json"), json, keep)
			reports += report("runs/$id.json", rawDir.resolve("run-$id.json").readText(), json)
		}
		val patches = rawDir.listDirectoryEntries("diff-*.patch").sortedBy { it.name }
		for (patch in patches) {
			val raw = patch.readText()
			val cleaned = LogSanitizer.sanitizeDiff(raw)
			val label = patch.nameWithoutExtension.removePrefix("diff-")
			write(outDir.resolve("diffs/$label.diff"), cleaned, emptySet())
			reports += report("diffs/$label.diff", raw, cleaned)
		}
		outDir.createDirectories()
		outDir.resolve("README.md").writeText(Review.render(reports))
		return reports
	}

	private fun write(path: Path, cleaned: Sanitized, keep: Set<String>) {
		val left = LogSanitizer.residue(cleaned.text, keep)
		if (left.isNotEmpty()) {
			throw LeakException("$path still holds ${left.size}: ${left.first()}")
		}
		path.parent.createDirectories()
		path.writeText(cleaned.text)
	}

	private fun report(name: String, raw: String, cleaned: Sanitized): FileReport = FileReport(
		name,
		raw.toByteArray().size,
		cleaned.text.toByteArray().size,
		raw.removeSuffix("\n").split('\n').size,
		cleaned.text.removeSuffix("\n").split('\n').size,
		cleaned.counts,
		cleaned.examples,
	)

	private fun summaryJson(summary: JsonObject, keep: Set<String>): Sanitized {
		val counts = sortedMapOf<String, Int>()
		val examples = sortedMapOf<String, String>()
		fun clean(value: JsonValue): JsonValue = when (value) {
			is JsonString -> {
				val s = LogSanitizer.sanitizeValue(value.value, keep)
				s.counts.forEach { (k, n) -> counts[k] = (counts[k] ?: 0) + n }
				s.examples.forEach { (k, v) -> examples.putIfAbsent(k, v) }
				JsonString(s.text)
			}

			is JsonArray -> JsonArray(value.items.map(::clean))

			is JsonObject -> JsonObject(value.fields.mapValues { clean(it.value) })

			else -> value
		}
		val jobs = (summary.fields.getValue("jobs") as JsonArray).items.map { job ->
			JsonObject(job.obj().fields.filterKeys { it != "url" && it != "databaseId" })
		}
		val trimmed = JsonObject(summary.fields + ("jobs" to JsonArray(jobs)))
		val text = CanonicalJson.encode(clean(trimmed)) + "\n"
		return Sanitized(text, counts, examples)
	}

	private fun JsonValue.obj(): JsonObject = this as JsonObject
}

/** Writes the capsule and the run descriptor of every sanitized log under a fixtures directory. */
object Capsules {
	fun generate(fixtures: Path): List<String> {
		val written = mutableListOf<String>()
		val ids = fixtures.resolve("logs").listDirectoryEntries("*.log")
			.map { it.nameWithoutExtension }.sorted()
		for (id in ids) {
			val log = fixtures.resolve("logs/$id.log").readText()
			val summary = CanonicalJson.parse(fixtures.resolve("runs/$id.json").readText())
			val capsule = RunnerCapsule.build(log, "drangler-$id")
			val dir = fixtures.resolve("capsules").createDirectories()
			dir.resolve("$id.json").writeText(capsule.canonical() + "\n")
			dir.resolve("$id.run.json")
				.writeText(CanonicalJson.encode(RunDescriptor.of(summary as JsonObject)) + "\n")
			written += id
		}
		return written
	}
}

object Review {
	private val meaning = mapOf(
		"diff-context" to "unchanged context lines of a diff",
		"email" to "email addresses",
		"hex-id" to "hex strings of 32 or more digits (hashes, ids)",
		"high-entropy" to "mixed-case tokens of 32 or more characters",
		"hostname" to "domain names that are not on the public allowlist",
		"integrity" to "package integrity hashes",
		"ipv4" to "IPv4 addresses other than loopback",
		"ipv4-loopback" to "loopback IPv4 addresses, kept as a marker",
		"ipv6" to "IPv6 addresses",
		"jwt" to "JSON web tokens",
		"long-line" to "lines over ${LogSanitizer.MAX_BODY} characters (bundled source, telemetry)",
		"mac" to "MAC addresses",
		"object-dump" to "lines of the wrangler object dump (bundled worker source, config)",
		"organization" to "the organization names",
		"person-name" to "personal names",
		"private-key-block" to "private key blocks",
		"private-package" to "private package names",
		"redactor" to "secrets matched by the core Redactor",
		"secret-name" to "values after a key, token, secret, password or auth name",
		"server-uid" to "database server ids",
		"token-prefix" to "provider tokens by prefix",
		"url-host" to "URL hosts that are not on the public allowlist",
		"url-query" to "URL query strings and fragments",
		"url-userinfo" to "credentials inside URLs",
		"username" to "account names",
		"uuid" to "UUIDs",
	)

	private const val INTRO = """
# Drangler Fixtures

Sanitized CI data from runs of the drangler Docker E2E workflow: runs that passed (one green run
and nightlies) and runs that failed on a later commit. The tests, the capsule diff and the lab
use them as a real example of a pipeline that went from green to red.

## Layout

- `logs/<run id>.log`: job logs from `gh run view --log`, sanitized.
- `runs/<run id>.json`: run summary from `gh run view --json` (jobs, steps, conclusions,
  durations, head commit), without job URLs and database ids.
- `diffs/<base>-<head>.diff`: the change between the last green and the first failing commit,
  with context lines removed.
- `capsules/<run id>.json`: the runner environment of the Docker E2E job (OS image, runner and
  tool versions, package versions), read from the log. `capsules/<run id>.run.json` is the
  matching descriptor: outcome, event, head commit, failing step and durations.

## Sanitization

`LogSanitizer` in the `tools` module applies deterministic rules before any file is written.
A second pass, written separately from the rules, scans the output. The run stops without
writing the file if that pass finds something that looks like a secret, an address or an
identity. GitHub had already replaced registered secrets with `***` in the logs.

Each removal is replaced by a fixed placeholder such as `<uuid>` or `<org>`, so the original
value cannot be recovered from these files. The table counts removals across all files; the
example is the sanitized line.

## Removals
"""

	private const val OUTRO = """
## Not Included

The raw logs are not part of this repository. They are kept outside it, and the files here are
all that is needed to run the tests.

## Regenerating

The raw directory holds `run-<id>.log` and `run-<id>.json` for each run and
`diff-<base>-<head>.patch` for each diff:

```sh
./gradlew :tools:jvmRun --args="freeze <raw-dir> fixtures/drangler"
./gradlew :tools:jvmRun --args="capsules fixtures/drangler"
```

`freeze` rewrites `logs`, `runs`, `diffs` and this file. Only the counts and byte totals in the
two tables depend on the raw input; the rest of the file is fixed text.
"""

	private val unlisted = setOf("email")

	fun render(reports: List<FileReport>): String {
		val totals = sortedMapOf<String, Int>()
		val examples = sortedMapOf<String, String>()
		for (r in reports) {
			r.counts.forEach { (k, n) -> totals[k] = (totals[k] ?: 0) + n }
			r.examples.forEach { (k, v) -> examples.putIfAbsent(k, v) }
		}
		val out = StringBuilder(INTRO.trimIndent()).append("\n\n")
		out.append("| Kind | Removes | Count | Redacted example |\n")
		out.append("| --- | --- | ---: | --- |\n")
		for ((kind, n) in totals - unlisted) {
			out.append("| ").append(kind).append(" | ").append(meaning[kind].orEmpty())
				.append(" | ").append(n).append(" | `").append(cell(examples[kind].orEmpty()))
				.append("` |\n")
		}
		out.append("\n## Files\n\n| File | Raw bytes | Kept bytes | Lines in | Lines out |\n")
		out.append("| --- | ---: | ---: | ---: | ---: |\n")
		for (r in reports) {
			out.append("| ").append(r.name).append(" | ").append(r.rawBytes).append(" | ")
				.append(r.outBytes).append(" | ").append(r.linesIn).append(" | ")
				.append(r.linesOut).append(" |\n")
		}
		out.append("\n## Kept\n\n")
		for (line in kept) out.append("- ").append(line).append('\n')
		return out.append(OUTRO.trimIndent()).append('\n').toString()
	}

	private val kept = listOf(
		"step names, conclusions, timestamps and durations from the run JSON",
		"the runner image block: OS, image and provisioner versions, runner version, Azure region",
		"tool versions printed by setup steps and package installs (private packages are masked)",
		"error lines, stack frames, exit codes, signals and transport errors (EPIPE, network lost)",
		"loopback addresses as `<loopback>` and temp directory names with their random suffix",
		"the head commit hash of each run, taken from the run JSON",
		"the literal `^[[` color markers, which are text in the saved logs, not escape bytes",
	)

	private fun cell(text: String): String = text.replace("`", "'").replace("|", "\\|")
}
