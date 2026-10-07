package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.string

class TrialOutcome(
	val index: Int,
	val exit: Int,
	val flake: Boolean,
	val signature: Boolean,
	val tail: String,
)

class ArmRun(val capsule: String?, val trials: List<TrialOutcome>, val done: Boolean) {
	companion object {
		fun parse(text: String): ArmRun {
			var capsule: StringBuilder? = null
			var finished: String? = null
			val trials = mutableListOf<TrialOutcome>()
			var done = false
			for (line in text.lines()) {
				when {
					line == "CAPSULE-BEGIN" -> capsule = StringBuilder()

					line == "CAPSULE-END" -> {
						finished = capsule?.toString()?.trim()
						capsule = null
					}

					capsule != null -> capsule.append(line)

					line == "DONE" -> done = true

					line.startsWith("TRIAL\t") -> {
						val f = line.split('\t', limit = 6)
						if (f.size >= 5) {
							trials +=
								TrialOutcome(
									f[1].toInt(),
									f[2].toInt(),
									f[3] == "1",
									f[4] == "1",
									f.getOrElse(5) { "" },
								)
						}
					}
				}
			}
			return ArmRun(finished, trials, done)
		}
	}
}

class Verdict(
	val valid: Boolean,
	val reasons: List<String>,
	val result: JsonObject,
	val capsules: Map<String, String>,
)

/** Turns the raw output of both arms into a validation verdict and the capsules to keep. */
object Ingest {
	fun scenario(
		s: Scenario,
		green: ArmRun,
		red: ArmRun,
		driftSha: String?,
		requireCapsule: Boolean,
	): Verdict {
		val reasons = mutableListOf<String>()
		val arms = mapOf("green" to (s.green to green), "red" to (s.red to red))
		val armJson = linkedMapOf<String, JsonValue>()
		for ((name, pair) in arms) {
			val (arm, run) = pair
			if (!run.done ||
				run.trials.size != s.trials
			) {
					reasons +=
					"$name arm incomplete: ${run.trials.size}/${s.trials} trials"
				}
			val failed = run.trials.filter { it.exit != 0 }
			val tails = Binomial.tails(s.trials, arm.rate, failed.size)
			if (!tails.accepts()) {
				reasons +=
					"$name arm failed ${failed.size}/${s.trials}, declared ${arm.rate} permille, " +
						"tail ${tails.smallest()}"
			}
			if (failed.any { !it.signature }) {
				reasons += "$name arm failed without the declared signature"
			}
			val exact = failed.map { it.index } == s.predicted(arm)
			if (!exact) reasons += "$name arm failures differ from the trial-index prediction"
			armJson[name] = obj(
				"image" to JsonString(arm.image),
				"trials" to JsonInt(s.trials.toLong()),
				"failures" to JsonInt(failed.size.toLong()),
				"declared_permille" to JsonInt(arm.rate.toLong()),
				"outcomes" to JsonString(outcomes(run)),
				"exit_codes" to JsonArray(failed.map { JsonInt(it.exit.toLong()) }.distinct()),
				"binomial_tail" to JsonString(tails.smallest()),
				"trial_exact" to JsonBool(exact),
				"last_failure" to JsonString(failed.lastOrNull()?.tail ?: ""),
			)
		}
		val capsules = linkedMapOf<String, String>()
		var diff = emptyList<String>()
		var seen = false
		val rawHashes = linkedMapOf<String, JsonValue>()
		val greenCapsule = green.capsule
		val redCapsule = red.capsule
		if (greenCapsule != null && redCapsule != null) {
			val g = Capsules.apply(greenCapsule, s.overrides.associate { it.path to it.green })
			val r = Capsules.apply(redCapsule, s.overrides.associate { it.path to it.red })
			capsules["green"] = g
			capsules["red"] = r
			rawHashes["green"] = JsonString(Sha256.hex(greenCapsule))
			rawHashes["red"] = JsonString(Sha256.hex(redCapsule))
			diff = Capsules.diff(g, r)
			seen = s.cause != null && s.cause.path in diff
			if (s.cause != null && seen != s.visible) {
				reasons += "cause ${s.cause.path} declared visible=${s.visible} " +
					"but capsule diff says $seen"
			}
		} else if (requireCapsule) {
			reasons += "no capsule captured"
		}
		val declared = listOfNotNull(s.cause?.path) + s.bundle.map { it.path } +
			s.decoys.map { it.path }
		val result = obj(
			"id" to JsonString(s.id),
			"valid" to JsonBool(reasons.isEmpty()),
			"reasons" to JsonArray(reasons.map { JsonString(it) }),
			"class" to JsonString(s.klass),
			"dimension" to JsonString(s.dimension),
			"controllable" to JsonBool(s.controllable),
			"arms" to JsonObject(armJson),
			"capsule" to obj(
				"raw_sha256" to JsonObject(rawHashes),
				"diff" to JsonArray(diff.map { JsonString(it) }),
				"cause_seen" to JsonBool(seen),
				"declared_unseen" to strings(declared.filter { it !in diff }),
				"incidental" to JsonArray(diff.filter { it !in declared }.map { JsonString(it) }),
				"overridden" to JsonArray(s.overrides.map { JsonString(it.path) }),
			),
			"drift_sha256" to (driftSha?.let { JsonString(it) } ?: JsonNull),
			"synthetic" to JsonBool(true),
		)
		return Verdict(reasons.isEmpty(), reasons, result, capsules)
	}
}

private fun outcomes(run: ArmRun): String =
	run.trials.joinToString("") { if (it.exit == 0) "0" else "1" }

private fun strings(items: List<String>): JsonArray = JsonArray(items.map { JsonString(it) })

object Capsules {
	private fun attributes(capsule: String): List<JsonObject> =
		(CanonicalJson.parse(capsule) as JsonObject).fields.getValue("attributes").array().map {
			it as JsonObject
		}

	/** Sets or adds attribute values; returns canonical JSON with the list sorted by path. */
	fun apply(capsule: String, overrides: Map<String, String>): String {
		if (overrides.isEmpty()) return capsule
		val root = CanonicalJson.parse(capsule) as JsonObject
		val byPath = attributes(capsule).associateBy {
			it.fields.getValue("path").string()
		}.toMutableMap()
		for ((path, value) in overrides) {
			val old = byPath[path]
			byPath[path] = obj(
				"path" to JsonString(path),
				"source" to JsonString("bench-synthetic"),
				"stability" to (old?.fields?.get("stability") ?: JsonString("static")),
				"value" to JsonString(value),
			)
		}
		val sorted = byPath.toSortedMap().values.toList()
		return CanonicalJson.encode(JsonObject(root.fields + ("attributes" to JsonArray(sorted))))
	}

	/** Paths whose value differs or that exist on one side, leaving out volatile attributes. */
	fun diff(green: String, red: String): List<String> {
		fun map(c: String) = attributes(c).associate {
			it.fields.getValue("path").string() to
				(it.fields["stability"]?.string() to it.fields["value"]?.string())
		}
		val g = map(green)
		val r = map(red)
		return (g.keys + r.keys).sorted().filter {
			val a = g[it]
			val b = r[it]
			a?.first != "volatile" && b?.first != "volatile" && a?.second != b?.second
		}
	}
}
