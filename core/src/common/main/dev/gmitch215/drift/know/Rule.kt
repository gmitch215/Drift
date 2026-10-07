package dev.gmitch215.drift.know

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

/** Where a rule decode or check failed: the rule id and the field path inside the rule. */
data class Violation(val ruleId: String, val path: String, val reason: String) {
	override fun toString() = "rule '$ruleId' at '$path': $reason"
}

class RuleException(val violation: Violation) : Exception(violation.toString())

internal fun fail(path: String, reason: String): Nothing =
	throw RuleException(Violation("", path, reason))

internal interface Coded {
	val id: String
}

/** `verified` means the source states the symptom; `inferred` means only the mechanism is cited. */
enum class Basis(override val id: String) : Coded {
	VERIFIED("verified"),
	INFERRED("inferred"),
}

enum class Severity(override val id: String) : Coded {
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
}

enum class SourceKind(override val id: String) : Coded {
	VENDOR_DOC("vendor-doc"),
	RELEASE_NOTE("release-note"),
	SPEC("spec"),
	MEASUREMENT("measurement"),
}

/** A `pending-scanner` rule names an attribute no scanner emits yet; the gate lists it. */
enum class Status(override val id: String) : Coded {
	ACTIVE("active"),
	PENDING_SCANNER("pending-scanner"),
}

data class Symptom(val text: String, val basis: Basis)

data class Source(val url: String, val kind: SourceKind, val verified: String, val section: String?)

data class Fixture(val name: String, val a: Env, val b: Env)

data class Fixtures(val positive: List<Fixture>, val negative: List<Fixture>)

/** [Rule.applies] holds dimension ids; [Rule.detect] reads `a` as passing and `b` as failing. */
data class Rule(
	val id: String,
	val title: String,
	val applies: List<String>,
	val difference: String,
	val symptoms: List<Symptom>,
	val mechanism: String,
	val probe: String?,
	val detect: Node,
	val severity: Severity,
	val provenance: List<Source>,
	val fixtures: Fixtures,
	val status: Status = Status.ACTIVE,
) {
	fun toJson(): JsonObject = JsonObject(
		buildMap {
			put("schema", JsonInt(SCHEMA))
			put("id", JsonString(id))
			put("title", JsonString(title))
			put("applies", JsonArray(applies.map(::JsonString)))
			put("difference", JsonString(difference))
			put(
				"symptoms",
				JsonArray(
					symptoms.map {
						obj("text" to JsonString(it.text), "basis" to JsonString(it.basis.id))
					},
				),
			)
			put("mechanism", JsonString(mechanism))
			if (probe != null) put("probe", JsonString(probe))
			put("detect", Detect.toJson(detect))
			put("severity", JsonString(severity.id))
			put("provenance", JsonArray(provenance.map(::sourceJson)))
			put(
				"fixtures",
				obj(
					"positive" to JsonArray(fixtures.positive.map(::fixtureJson)),
					"negative" to JsonArray(fixtures.negative.map(::fixtureJson)),
				),
			)
			put("status", JsonString(status.id))
		},
	)

	fun canonical(): String = CanonicalJson.encode(toJson())

	fun hash(): String = Sha256.hex(canonical())

	companion object {
		const val SCHEMA = 1L

		/** Strict decode: a missing required field or an unknown field throws [RuleException]. */
		fun fromJson(json: JsonValue): Rule {
			val f = Fields("", json)
			val id = (f.peek("id") as? JsonString)?.value.orEmpty()
			try {
				return decode(f)
			} catch (e: RuleException) {
				throw RuleException(e.violation.copy(ruleId = id))
			}
		}

		private fun decode(f: Fields): Rule {
			val schema = f.long("schema")
			if (schema != SCHEMA) fail("schema", "unsupported rule schema $schema")
			return Rule(
				id = f.str("id"),
				title = f.str("title"),
				applies = f.strings("applies"),
				difference = f.str("difference"),
				symptoms = f.objects("symptoms") {
					Symptom(it.str("text"), it.choice("basis", Basis.entries))
				},
				mechanism = f.str("mechanism"),
				probe = f.strOrNull("probe"),
				detect = Detect.parse(f.raw("detect"), "detect"),
				severity = f.choice("severity", Severity.entries),
				provenance = f.objects("provenance") {
					Source(
						it.str("url"),
						it.choice("kind", SourceKind.entries),
						it.str("verified"),
						it.strOrNull("section"),
					)
				},
				fixtures = f.obj("fixtures") {
					Fixtures(
						it.objects("positive", ::fixture),
						it.objects("negative", ::fixture),
					)
				},
				status = f.choiceOrNull("status", Status.entries) ?: Status.ACTIVE,
			).also { f.close() }
		}

		private fun fixture(f: Fields): Fixture =
			Fixture(f.str("name"), f.obj("a", Env::of), f.obj("b", Env::of))

		private fun sourceJson(s: Source) = JsonObject(
			buildMap {
				put("url", JsonString(s.url))
				put("kind", JsonString(s.kind.id))
				put("verified", JsonString(s.verified))
				if (s.section != null) put("section", JsonString(s.section))
			},
		)

		private fun fixtureJson(x: Fixture): JsonObject =
			obj("name" to JsonString(x.name), "a" to x.a.toJson(), "b" to x.b.toJson())
	}
}

/** One side of a comparison: static attributes, usable probe transcripts and run symptom fields. */
data class Env(
	val attrs: Map<String, String> = emptyMap(),
	val probes: Map<String, String> = emptyMap(),
	val run: Map<String, String> = emptyMap(),
) {
	/** `probe:<id>` and `run:<field>` read their own maps; an absent fact is `null`. */
	fun lookup(path: String): String? = when {
		path.startsWith("probe:") -> probes[path.substring(6)]
		path.startsWith("run:") -> run[path.substring(4)]
		else -> attrs[path]
	}

	fun toJson(): JsonObject = obj(
		"attrs" to json(attrs),
		"probes" to json(probes),
		"run" to json(run),
	)

	companion object {
		internal fun of(f: Fields): Env =
			Env(f.stringMap("attrs"), f.stringMap("probes"), f.stringMap("run"))

		private fun json(m: Map<String, String>) = JsonObject(m.mapValues { JsonString(it.value) })
	}
}

/** Reads one JSON object field by field, with the failing path in every error. */
internal class Fields(private val path: String, json: JsonValue) {
	private val o: JsonObject = at(path) { json.obj() }
	private val seen = mutableSetOf<String>()

	val keys: Set<String> get() = o.fields.keys

	private fun join(key: String) = if (path.isEmpty()) key else "$path.$key"

	private fun <T> at(where: String, block: () -> T): T = try {
		block()
	} catch (e: JsonException) {
		fail(where, e.message.orEmpty())
	}

	fun peek(key: String): JsonValue? = o[key]

	fun raw(key: String): JsonValue {
		seen += key
		return at(join(key)) { o.require(key) }
	}

	private fun rawOrNull(key: String): JsonValue? {
		seen += key
		return o[key]
	}

	fun str(key: String): String = at(join(key)) { raw(key).string() }

	fun strOrNull(key: String): String? = rawOrNull(key)?.let { at(join(key)) { it.string() } }

	fun long(key: String): Long = at(join(key)) { raw(key).long() }

	fun bool(key: String): Boolean =
		(raw(key) as? JsonBool)?.value ?: fail(join(key), "expected boolean")

	fun strings(key: String): List<String> = at(join(key)) { raw(key).array().map { it.string() } }

	fun stringMap(key: String): Map<String, String> = rawOrNull(key)?.let { v ->
		at(join(key)) { v.obj().fields.mapValues { it.value.string() } }
	}.orEmpty()

	fun <T> obj(key: String, read: (Fields) -> T): T = child(join(key), raw(key), read)

	fun <T> objects(key: String, read: (Fields) -> T): List<T> =
		at(join(key)) { raw(key).array() }.mapIndexed { i, item ->
			child("${join(key)}[$i]", item, read)
		}

	private fun <T> child(where: String, json: JsonValue, read: (Fields) -> T): T {
		val f = Fields(where, json)
		return read(f).also { f.close() }
	}

	fun <E : Coded> choice(key: String, values: List<E>): E {
		val text = str(key)
		return values.firstOrNull { it.id == text }
			?: fail(join(key), "unknown value '$text', expected one of ${values.map { it.id }}")
	}

	fun <E : Coded> choiceOrNull(key: String, values: List<E>): E? =
		if (peek(key) == null) null else choice(key, values)

	fun close() {
		val unknown = o.fields.keys.filter { it !in seen }.minOrNull() ?: return
		fail(join(unknown), "unknown field")
	}
}
