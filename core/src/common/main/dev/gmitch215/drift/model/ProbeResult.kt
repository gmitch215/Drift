package dev.gmitch215.drift.model

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

enum class ProbeStatus { OK, UNAVAILABLE }

data class ProbeResult(val id: String, val status: ProbeStatus, val transcript: String) {
	val hash: String get() = Sha256.hex(transcript)

	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"status" to JsonString(status.name.lowercase()),
		"transcript" to JsonString(transcript),
		"hash" to JsonString(hash),
	)

	companion object {
		fun fromJson(json: JsonValue): ProbeResult {
			val o = json.obj()
			val status = o.require("status").string()
			return ProbeResult(
				id = o.require("id").string(),
				status = ProbeStatus.entries.firstOrNull { it.name.lowercase() == status }
					?: throw JsonException("unknown probe status $status"),
				transcript = o.require("transcript").string(),
			)
		}
	}
}
