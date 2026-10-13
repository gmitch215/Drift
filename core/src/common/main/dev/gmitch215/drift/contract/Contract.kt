package dev.gmitch215.drift.contract

import dev.gmitch215.drift.delta.Ddmin
import dev.gmitch215.drift.delta.Verdict
import dev.gmitch215.drift.diff.CiVolatile
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability

enum class AssertionKind(val id: String) { ATTRIBUTE("attribute"), PROBE("probe") }

/** [COINCIDENTAL] means constant in every green and not yet shown to matter by a red. */
enum class Role(val id: String) { DISCRIMINATING("discriminating"), COINCIDENTAL("coincidental") }

enum class Holds(val id: String) { HOLDS("holds"), BREAKS("breaks"), UNVERIFIABLE("unverifiable") }

/** [found] is the value the capsule has: an attribute value or a probe transcript hash. */
data class Finding(val status: Holds, val found: String?)

/**
 * One requirement on an environment. [expect] is an attribute value or a probe transcript hash;
 * [breaks] names the reds this assertion tells apart from every green.
 */
data class Assertion(
    val kind: AssertionKind,
    val id: String,
    val expect: String,
    val role: Role,
    val breaks: List<String> = emptyList(),
) {
    fun check(capsule: Capsule): Finding = when (kind) {
        AssertionKind.ATTRIBUTE -> {
            val found = capsule.attributes.firstOrNull { it.path == id }?.value
            Finding(
                when (found) {
                    null -> Holds.UNVERIFIABLE
                    expect -> Holds.HOLDS
                    else -> Holds.BREAKS
                },
                found,
            )
        }

        AssertionKind.PROBE -> {
            val probe = capsule.probes.firstOrNull { it.id == id }
            if (probe == null || probe.status != ProbeStatus.OK) {
                Finding(Holds.UNVERIFIABLE, null)
            } else {
                Finding(if (probe.hash == expect) Holds.HOLDS else Holds.BREAKS, probe.hash)
            }
        }
    }

    fun toJson(): JsonObject {
        val fields = mutableListOf<Pair<String, JsonValue>>(
            "kind" to JsonString(kind.id),
            "id" to JsonString(id),
            "expect" to JsonString(expect),
            "role" to JsonString(role.id),
        )
        if (breaks.isNotEmpty()) fields += "breaks" to JsonArray(breaks.map(::JsonString))
        return JsonObject(fields.toMap())
    }

    companion object {
        fun fromJson(json: JsonValue): Assertion {
            val o = json.obj()
            val kind = o.require("kind").string()
            val role = o.require("role").string()
            return Assertion(
                kind = AssertionKind.entries.firstOrNull { it.id == kind }
                    ?: throw JsonException("unknown assertion kind $kind"),
                id = o.require("id").string(),
                expect = o.require("expect").string(),
                role = Role.entries.firstOrNull { it.id == role }
                    ?: throw JsonException("unknown assertion role $role"),
                breaks = o["breaks"]?.array()?.map { it.string() } ?: emptyList(),
            )
        }
    }
}

/** The label and canonical hash of a capsule a contract was made from. */
data class CapsuleRef(val label: String, val hash: String) {
    fun toJson(): JsonObject = obj("label" to JsonString(label), "hash" to JsonString(hash))

    companion object {
        fun of(capsule: Capsule) = CapsuleRef(capsule.label, capsule.hash())

        fun fromJson(json: JsonValue): CapsuleRef {
            val o = json.obj()
            return CapsuleRef(o.require("label").string(), o.require("hash").string())
        }
    }
}

sealed interface ContractResult {
    /** [candidates] counts what every green agrees on; [calls] is the ddmin test count. */
    data class Created(val contract: Contract, val candidates: Int, val calls: Int) : ContractResult

    /** The reds that break nothing every green satisfies, so no assertion can tell them apart. */
    data class Inseparable(val reds: List<String>, val candidates: Int) : ContractResult
}

/**
 * What a program needs from an environment, as a set of assertions that every known green
 * satisfies. With reds it is a 1-minimal set that breaks every red; with greens alone it is every
 * value the greens share, each [Role.COINCIDENTAL].
 */
data class Contract(
    val greens: List<CapsuleRef>,
    val reds: List<CapsuleRef>,
    val assertions: List<Assertion>,
) {
    val mode: String get() = if (reds.isEmpty()) "greens" else "separating"

    fun verify(capsule: Capsule): ContractReport =
        ContractReport(assertions.map { AssertionResult(it, it.check(capsule)) })

    fun toJson(): JsonObject = obj(
        "schema" to JsonInt(SCHEMA),
        "format" to JsonString(FORMAT),
        "mode" to JsonString(mode),
        "greens" to JsonArray(greens.map { it.toJson() }),
        "reds" to JsonArray(reds.map { it.toJson() }),
        "assertions" to JsonArray(assertions.map { it.toJson() }),
    )

    /** The same JSON as [toJson] with one assertion or capsule per line, for people to edit. */
    fun render(): String = buildString {
        append("{\n")
        append(block("assertions", assertions.map { CanonicalJson.encode(it.toJson()) }))
        append(",\n  \"format\": \"$FORMAT\",\n")
        append(block("greens", greens.map { CanonicalJson.encode(it.toJson()) }))
        append(",\n  \"mode\": \"$mode\",\n")
        append(block("reds", reds.map { CanonicalJson.encode(it.toJson()) }))
        append(",\n  \"schema\": $SCHEMA\n}")
    }

    private fun block(name: String, lines: List<String>): String = if (lines.isEmpty()) {
        "  \"$name\": []"
    } else {
        lines.joinToString(",\n", "  \"$name\": [\n", "\n  ]") { "    $it" }
    }

    companion object {
        const val SCHEMA = 1L
        const val FORMAT = "drift-contract-1"

        fun fromJson(json: JsonValue): Contract {
            val o = json.obj()
            val schema = o.require("schema").long()
            if (schema != SCHEMA) throw JsonException("unsupported contract schema $schema")
            val format = o.require("format").string()
            if (format != FORMAT) throw JsonException("unknown contract format $format")
            return Contract(
                greens = o.require("greens").array().map(CapsuleRef::fromJson),
                reds = o.require("reds").array().map(CapsuleRef::fromJson),
                assertions = o.require("assertions").array().map(Assertion::fromJson),
            )
        }

        fun parse(text: String): Contract = fromJson(CanonicalJson.parse(text))

        /** [greens] must not be empty; [reds] may be. Capsule order does not change the result. */
        fun create(greens: List<Capsule>, reds: List<Capsule> = emptyList()): ContractResult {
            require(greens.isNotEmpty()) { "a contract needs at least one green capsule" }
            val candidates = constants(greens)
            val greenRefs = refs(greens)
            if (reds.isEmpty()) {
                return ContractResult.Created(
                    Contract(greenRefs, emptyList(), candidates),
                    candidates.size,
                    0,
                )
            }
            val broken = candidates.map { c ->
                reds.indices.filter { c.check(reds[it]).status == Holds.BREAKS }.toSet()
            }
            val loose = reds.indices.filter { r -> broken.none { r in it } }
            if (loose.isNotEmpty()) {
                return ContractResult.Inseparable(
                    loose.map { reds[it].label }.sorted(),
                    candidates.size,
                )
            }
            val picked = Ddmin.run(candidates.indices.toList()) { subset ->
                if (reds.indices.all { r -> subset.any { r in broken[it] } }) {
                    Verdict.FAIL
                } else {
                    Verdict.PASS
                }
            }
            val assertions = picked.minimal.map { i ->
                candidates[i].copy(
                    role = Role.DISCRIMINATING,
                    breaks = broken[i].map { reds[it].label }.sorted(),
                )
            }
            return ContractResult.Created(
                Contract(greenRefs, refs(reds), assertions),
                candidates.size,
                picked.calls,
            )
        }

        private fun refs(capsules: List<Capsule>) =
            capsules.map(CapsuleRef::of).sortedWith(compareBy({ it.label }, { it.hash }))

        private fun constants(greens: List<Capsule>): List<Assertion> {
            val attributes = greens.first().attributes
                .filter { it.stability == Stability.STATIC && !CiVolatile.covers(it.path) }
                .distinctBy { it.path }
                .filter { a ->
                    greens.all { g ->
                        val same = g.attributes.firstOrNull { it.path == a.path }
                        same != null && same.stability == Stability.STATIC && same.value == a.value
                    }
                }
                .sortedBy { it.path }
                .map { Assertion(AssertionKind.ATTRIBUTE, it.path, it.value, Role.COINCIDENTAL) }
            val probes = greens.first().probes
                .filter { it.status == ProbeStatus.OK }
                .distinctBy { it.id }
                .filter { p ->
                    greens.all { g ->
                        g.probes.firstOrNull { it.id == p.id }
                            ?.let { it.status == ProbeStatus.OK && it.hash == p.hash } == true
                    }
                }
                .sortedBy { it.id }
                .map { Assertion(AssertionKind.PROBE, it.id, it.hash, Role.COINCIDENTAL) }
            return attributes + probes
        }
    }
}

data class AssertionResult(val assertion: Assertion, val finding: Finding)

/** One [AssertionResult] per assertion, in contract order. */
class ContractReport(val results: List<AssertionResult>) {
    private fun count(status: Holds) = results.count { it.finding.status == status }

    val holds: Int get() = count(Holds.HOLDS)
    val breaks: Int get() = count(Holds.BREAKS)
    val unverifiable: Int get() = count(Holds.UNVERIFIABLE)
    val ok: Boolean get() = breaks == 0
    val first: AssertionResult? get() = results.firstOrNull { it.finding.status == Holds.BREAKS }

    fun text(): String {
        val lines = results.map { line(it) }.toMutableList()
        lines += "contract: $holds hold, $breaks break, $unverifiable unverifiable"
        return lines.joinToString("\n")
    }

    fun toJson(): JsonObject = obj(
        "schema" to JsonInt(1),
        "format" to JsonString("drift-contract-verify-1"),
        "ok" to JsonBool(ok),
        "holds" to JsonInt(holds.toLong()),
        "breaks" to JsonInt(breaks.toLong()),
        "unverifiable" to JsonInt(unverifiable.toLong()),
        "results" to JsonArray(
            results.map {
                obj(
                    "kind" to JsonString(it.assertion.kind.id),
                    "id" to JsonString(it.assertion.id),
                    "role" to JsonString(it.assertion.role.id),
                    "status" to JsonString(it.finding.status.id),
                    "expect" to JsonString(it.assertion.expect),
                    "found" to jsonOrNull(it.finding.found),
                )
            },
        ),
    )

    fun json(): String = CanonicalJson.encode(toJson())

    private fun line(r: AssertionResult): String {
        val a = r.assertion
        val head = "${r.finding.status.id.uppercase()} ${a.kind.id} ${a.id} (${a.role.id})"
        return when (r.finding.status) {
            Holds.HOLDS -> "$head: ${show(a.expect)}"

            Holds.BREAKS -> "$head: expected ${show(a.expect)}, found ${show(r.finding.found!!)}"

            Holds.UNVERIFIABLE -> {
                val why = if (a.kind == AssertionKind.ATTRIBUTE) ABSENT else NO_PROBE
                "$head: $why"
            }
        }
    }

    private fun show(value: String): String {
        val clipped = if (value.length > CLIP) value.take(CLIP) + "..." else value
        return CanonicalJson.encode(JsonString(clipped))
    }

    private companion object {
        const val CLIP = 72
        const val ABSENT = "not in the capsule"
        const val NO_PROBE = "no usable transcript"
    }
}
