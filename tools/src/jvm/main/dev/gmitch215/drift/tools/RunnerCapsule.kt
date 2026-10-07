package dev.gmitch215.drift.tools

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import java.time.Duration
import java.time.Instant

/** A partial capsule read from the environment lines of one job in a sanitized CI log. */
object RunnerCapsule {
	const val JOB = "Docker E2E"
	private const val SOURCE = "log"
	private const val PRIVATE_PACKAGE = "<private-pkg>"

	fun build(log: String, label: String, job: String = JOB): Capsule {
		val lines = bodies(log, job)
		val attributes = linkedMapOf<String, Attribute>()

		fun add(path: String, value: String?, stability: Stability = Stability.STATIC) {
			if (!value.isNullOrEmpty()) attributes[path] = Attribute(path, value, SOURCE, stability)
		}

		val os = group(lines, "Operating System")
		add("os.release.name", os.getOrNull(0))
		add("os.version", os.getOrNull(1))
		add("os.release.channel", os.getOrNull(2))

		val image = group(lines, "Runner Image")
		add("ci.runner.image", field(image, "Image"))
		add("ci.runner.image.version", field(image, "Version"))

		val provisioner = group(lines, "Runner Image Provisioner")
		add("ci.provisioner.name", provisioner.getOrNull(0))
		add("ci.provisioner.version", field(provisioner, "Version"))
		add("ci.provisioner.build-date", field(provisioner, "Build Date"))
		add("ci.runner.region", field(provisioner, "Azure Region"), Stability.VOLATILE)
		add("ci.runner.version", match(lines, "Current runner version: '([^']+)'"))

		add("tool.git.version", match(lines, "git version (\\S+)"))
		add("tool.bun.version", match(lines, "bun install v(\\S+)"))
		val node = group(lines, "Environment details")
		add("tool.node.version", field(node, "node")?.removePrefix("v"))
		add("tool.npm.version", field(node, "npm"))
		add("tool.yarn.version", field(node, "yarn"))

		for ((name, version) in installed(lines)) add("deps.$name.version", version)
		for ((name, value) in jobEnvironment(lines)) add("env.$name", value)

		return Capsule(label, attributes.values.toList())
	}

	private fun bodies(log: String, job: String): List<String> =
		log.lineSequence().mapNotNull { line ->
		val parts = line.split('\t', limit = 3)
		if (parts.size < 3 || parts[0] != job) return@mapNotNull null
		val rest = parts[2]
		if (rest.firstOrNull()?.isDigit() == true) rest.substringAfter(' ', "") else rest
	}.toList()

	private fun group(lines: List<String>, title: String): List<String> {
		val start = lines.indexOf("##[group]$title")
		if (start < 0) return emptyList()
		return lines.drop(start + 1).takeWhile { it != "##[endgroup]" }
	}

	private fun field(lines: List<String>, name: String): String? =
		lines.firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ")

	private fun match(lines: List<String>, pattern: String): String? {
		val re = Regex(pattern)
		return lines.firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1) }
	}

	private fun installed(lines: List<String>): List<Pair<String, String>> {
		val start = lines.indexOf("##[group]Run bun install --frozen-lockfile")
		if (start < 0) return emptyList()
		return lines.drop(start + 1).dropWhile { !it.startsWith("bun install v") }
			.takeWhile { !it.contains(" packages installed") }
			.filter { it.startsWith("+ ") }
			.map { it.removePrefix("+ ") }
			.filter { it.indexOf('@', 1) > 0 && !it.startsWith(PRIVATE_PACKAGE) }
			.map { it.substringBeforeLast('@') to it.substringAfterLast('@') }
	}

	private fun jobEnvironment(lines: List<String>): List<Pair<String, String>> {
		val run = lines.indexOfFirst { it.startsWith("##[group]Run ") }
		if (run < 0) return emptyList()
		val after = lines.drop(run + 1)
		val env = after.takeWhile { it != "##[endgroup]" }.indexOf("env:")
		if (env < 0) return emptyList()
		return after.drop(env + 1).takeWhile { it.startsWith("  ") }.mapNotNull {
			val name = it.trim().substringBefore(':')
			val value = it.trim().substringAfter(':', "").trim()
			if (value.isEmpty() || value == "***") null else name to value
		}
	}
}

/** The outcome, failing step and duration of a run, read from its sanitized summary JSON. */
object RunDescriptor {
	fun of(summary: JsonObject): JsonObject {
		val jobs = summary.require("jobs").array().map { it.obj() }
		val failingJob = jobs.firstOrNull { it.text("conclusion") == "failure" }
		val failingStep = failingJob?.require("steps")?.array()?.map { it.obj() }
			?.firstOrNull { it.text("conclusion") == "failure" }
		val started = jobs.minOf { Instant.parse(it.text("startedAt")) }
		val completed = jobs.maxOf { Instant.parse(it.text("completedAt")) }
		return obj(
			"id" to JsonInt(summary.require("databaseId").long()),
			"outcome" to JsonString(summary.text("conclusion")),
			"event" to JsonString(summary.text("event")),
			"headSha" to JsonString(summary.text("headSha")),
			"failingJob" to jsonOrNull(failingJob?.text("name")),
			"failingStep" to jsonOrNull(failingStep?.text("name")),
			"failingStepSeconds" to (failingStep?.let { JsonInt(seconds(it)) } ?: JsonNull),
			"durationSeconds" to JsonInt(Duration.between(started, completed).seconds),
		)
	}

	private fun seconds(step: JsonObject): Long = Duration.between(
			Instant.parse(step.text("startedAt")),
			Instant.parse(step.text("completedAt")),
		).seconds

	private fun JsonObject.text(key: String): String = require(key).string()
}
