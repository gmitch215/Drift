package dev.gmitch215.drift.model

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
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
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.symptom.SymptomKind

enum class RunOutcome {
	PASS,
	FAIL,
	CANCELLED,
	UNKNOWN,
	;

	companion object {
		/** Maps a GitHub Actions conclusion; anything unlisted is `UNKNOWN`. */
		fun ofConclusion(conclusion: String?): RunOutcome = when (conclusion) {
			"success" -> PASS
			"failure", "timed_out" -> FAIL
			"cancelled" -> CANCELLED
			else -> UNKNOWN
		}
	}
}

enum class StepConclusion {
	SUCCESS,
	FAILURE,
	SKIPPED,
	CANCELLED,
	UNKNOWN,
	;

	companion object {
		fun ofConclusion(conclusion: String?): StepConclusion = when (conclusion) {
			"success" -> SUCCESS
			"failure", "timed_out" -> FAILURE
			"skipped" -> SKIPPED
			"cancelled" -> CANCELLED
			else -> UNKNOWN
		}
	}
}

data class Step(
	val job: String?,
	val name: String,
	val conclusion: StepConclusion,
	val durationMs: Long?,
) {
	fun toJson(): JsonObject = obj(
		"job" to jsonOrNull(job),
		"name" to JsonString(name),
		"conclusion" to JsonString(conclusion.name.lowercase()),
		"durationMs" to (durationMs?.let { JsonInt(it) } ?: JsonNull),
	)

	companion object {
		fun fromJson(json: JsonValue): Step {
			val o = json.obj()
			val conclusion = o.require("conclusion").string()
			return Step(
				job = o.require("job").let { if (it == JsonNull) null else it.string() },
				name = o.require("name").string(),
				conclusion = StepConclusion.entries.firstOrNull {
					it.name.lowercase() == conclusion
				} ?: throw JsonException("unknown step conclusion $conclusion"),
				durationMs = o.require("durationMs").let {
					if (it == JsonNull) null else it.long()
				},
			)
		}
	}
}

/**
 * One CI run: where it ended, the steps in log order and the symptoms read from its log.
 * `capsule` holds whatever the log revealed about the environment, so it is usually partial.
 */
data class Run(
	val id: String,
	val outcome: RunOutcome,
	val steps: List<Step> = emptyList(),
	val symptoms: List<Symptom> = emptyList(),
	val capsule: Capsule? = null,
) {
	/**
	 * The `run:<field>` facts a rule can read, as text. A field is present only when the run has
	 * evidence for it: `signature` (first 16 hex digits of the SHA-256 of the sorted distinct
	 * failure signatures), `exit` and `signal` (last one seen), `step` and `duration-ms` (the first
	 * failed step, from `steps` when one failed, else from the log), `transport` (distinct values,
	 * sorted, one per line).
	 */
	fun facts(): Map<String, String> {
		val out = LinkedHashMap<String, String>()
		fun values(kind: SymptomKind) = symptoms.filter { it.kind == kind }.map { it.value }
		val signatures = values(SymptomKind.SIGNATURE).distinct().sorted()
		if (signatures.isNotEmpty()) {
			out["signature"] = Sha256.hex(signatures.joinToString("\n")).substring(0, 16)
		}
		values(SymptomKind.EXIT).lastOrNull()?.let { out["exit"] = it }
		values(SymptomKind.SIGNAL).lastOrNull()?.let { out["signal"] = it }
		val failed = steps.firstOrNull { it.conclusion == StepConclusion.FAILURE }
		(failed?.name ?: values(SymptomKind.STEP).firstOrNull())?.let { out["step"] = it }
		(failed?.durationMs?.toString() ?: values(SymptomKind.DURATION).firstOrNull())?.let {
			out["duration-ms"] = it
		}
		val transport = values(SymptomKind.TRANSPORT).distinct().sorted()
		if (transport.isNotEmpty()) out["transport"] = transport.joinToString("\n")
		return out
	}

	fun toJson(): JsonObject = obj(
		"schema" to JsonInt(SCHEMA),
		"id" to JsonString(id),
		"outcome" to JsonString(outcome.name.lowercase()),
		"capsule" to (capsule?.toJson() ?: JsonNull),
		"steps" to JsonArray(steps.map { it.toJson() }),
		"symptoms" to JsonArray(symptoms.map { it.toJson() }),
	)

	fun canonical(): String = CanonicalJson.encode(toJson())

	fun hash(): String = Sha256.hex(canonical())

	companion object {
		const val SCHEMA = 1L

		fun fromJson(json: JsonValue): Run {
			val o = json.obj()
			val schema = o.require("schema").long()
			if (schema != SCHEMA) throw JsonException("unsupported run schema $schema")
			val outcome = o.require("outcome").string()
			return Run(
				id = o.require("id").string(),
				outcome = RunOutcome.entries.firstOrNull { it.name.lowercase() == outcome }
					?: throw JsonException("unknown run outcome $outcome"),
				steps = o.require("steps").array().map(Step::fromJson),
				symptoms = o.require("symptoms").array().map(Symptom::fromJson),
				capsule = o.require("capsule").let {
					if (it == JsonNull) null else Capsule.fromJson(it)
				},
			)
		}

		fun parse(text: String): Run = fromJson(CanonicalJson.parse(text))
	}
}
