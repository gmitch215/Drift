package dev.gmitch215.drift.triage

import dev.gmitch215.drift.diff.Version
import dev.gmitch215.drift.diff.VersionScheme
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Constraints
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Fixture
import dev.gmitch215.drift.know.Fixtures
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleCompiler
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.know.Source
import dev.gmitch215.drift.know.SourceKind
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.know.Symptom
import dev.gmitch215.drift.know.paths
import dev.gmitch215.drift.model.Dimension

/** [DRAFT] goes to a person; [PUBLISH] met the configured threshold and passed every rule check. */
enum class Disposition(val id: String) {
    DRAFT("draft"),
    PUBLISH("publish"),
}

/**
 * A rule suggested by the report. [yaml] is the rule file text in the schema of `rules/`; a
 * [Disposition.DRAFT] carries `status: draft`, which is not a rule status, so it cannot load
 * into the catalog by accident. [confidence] is the weakest of the two version facts and the
 * symptom signature the rule is built from. [notes] says why a draft was not published, and
 * [rule] is the rule to store when it was.
 */
data class DraftRule(
    val id: String,
    val disposition: Disposition,
    val confidence: Confidence,
    val key: String,
    val yaml: String,
    val notes: List<String>,
    val rule: Rule? = null,
) {
    fun toJson(): JsonObject = obj(
        "id" to JsonString(id),
        "disposition" to JsonString(disposition.id),
        "confidence" to JsonInt(confidence.permille.toLong()),
        "key" to JsonString(key),
        "notes" to JsonArray(notes.map(::JsonString)),
        "yaml" to JsonString(yaml),
    )
}

internal object Drafts {
    private const val TITLE_SIGNATURE = 60
    private const val PLACEHOLDER_DATE = "unverified"

    /**
     * A rule needs a symptom signature and one version key stated on both sides with the passing
     * version below the failing one, and no rule that already holds on that key.
     */
    fun suggest(
        facts: List<Fact>,
        hits: List<RuleHit>,
        body: String,
        options: TriageOptions,
        rules: List<Rule>,
    ): DraftRule? {
        val sig = facts.firstOrNull { it.key == "run:signature" && it.side == Side.FAILING }
            ?: return null
        val covered = hits.filter { it.grade != Grade.SYMPTOM }.flatMap { it.facts }.toSet()
        val pair = facts.filter { it.side == Side.FAILING && it.key.endsWith(".version") }
            .sortedBy { it.key }
            .firstNotNullOfOrNull { fv ->
                val pv = facts.firstOrNull { it.side == Side.PASSING && it.key == fv.key }
                    ?: return@firstNotNullOfOrNull null
                if (fv.key in covered || !rises(fv.key, pv.value, fv.value)) {
                    return@firstNotNullOfOrNull null
                }
                if (Dimension.of(fv.key) == null) return@firstNotNullOfOrNull null
                pv to fv
            } ?: return null
        val (pv, fv) = pair
        val id = "triage-" + slug(fv.key) + "-" + body.take(ID_HASH)
        val weakest = minOf(pv.confidence.permille, fv.confidence.permille, sig.confidence.permille)
        val confidence = Confidence(weakest)
        val date = options.date ?: PLACEHOLDER_DATE
        val rule = build(id, fv.key, pv.value, fv.value, sig, date, Status.ACTIVE, body)
        val threshold = options.publishThreshold
        val problems = mutableListOf<String>()
        if (threshold == null) {
            problems += "drafts only: no publish threshold is set"
        } else if (confidence < threshold) {
            problems += "confidence $confidence is below the publish threshold $threshold"
        }
        if (options.date == null) problems += "no --date: the provenance date is not known"
        if (options.date != null) problems += RuleCompiler.validate(rule).map { it.toString() }
        problems += Constraints.fixtures(rule).map { it.toString() }
        val publish = problems.isEmpty()
        val status = when {
            !publish -> null
            emitted(fv.key, rules) -> Status.ACTIVE
            else -> Status.PENDING_SCANNER
        }
        val final = if (status == null) rule else rule.copy(status = status)
        val json = final.toJson()
        val doc = if (publish) json else JsonObject(json.fields + ("status" to JsonString("draft")))
        return DraftRule(
            id,
            if (publish) Disposition.PUBLISH else Disposition.DRAFT,
            confidence,
            fv.key,
            Yaml.emit(doc),
            if (publish) emptyList() else problems,
            if (publish) final else null,
        )
    }

    private fun rises(key: String, from: String, to: String): Boolean {
        val scheme = VersionScheme.of(key)
        val a = Version.parse(from, scheme) ?: return false
        val b = Version.parse(to, scheme) ?: return false
        return a.compareCore(b) < 0 && !from.startsWith("$to.") && !to.startsWith("$from.")
    }

    private fun slug(key: String): String {
        val sb = StringBuilder()
        for (c in key) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c + 32)
                sb.isNotEmpty() && sb.last() != '-' -> sb.append('-')
            }
        }
        return sb.toString().trim('-')
    }

    /** True when an active rule already reads [key], so a scanner emits it. */
    private fun emitted(key: String, rules: List<Rule>) =
        rules.any { it.status == Status.ACTIVE && key in it.detect.paths() }

    internal fun build(
        id: String,
        key: String,
        from: String,
        to: String,
        sig: Fact,
        date: String,
        status: Status,
        body: String,
    ): Rule {
        val detect = Detect.parse(
            obj(
                "all" to JsonArray(
                    listOf(
                        obj("a" to JsonString(key), "ver" to JsonString("<=$from")),
                        obj("b" to JsonString(key), "ver" to JsonString(">=$to")),
                        obj("b" to JsonString("run:signature"), "has" to JsonString(sig.value)),
                    ),
                ),
            ),
            "detect",
        )
        val head = sig.value.take(TITLE_SIGNATURE)
        return Rule(
            id = id,
            title = "Reported: $head after $key $from to $to",
            applies = listOf(Dimension.of(key)?.id ?: "runtime"),
            difference = "the failing side has $key $to or newer and the passing side has $from " +
                "or older",
            symptoms = listOf(Symptom(sig.value, Basis.INFERRED)),
            mechanism = "Not established: drafted from one issue report that nobody has " +
                "reproduced.",
            probe = null,
            detect = detect,
            severity = Severity.LOW,
            provenance = listOf(
                Source("fixture:triage/${body.take(ID_HASH)}", SourceKind.REPORT, date, null),
            ),
            fixtures = Fixtures(
                listOf(
                    Fixture(
                        "reported",
                        Env(attrs = mapOf(key to from)),
                        Env(attrs = mapOf(key to to), run = mapOf("signature" to sig.value)),
                    ),
                ),
                listOf(
                    Fixture(
                        "same version on both sides",
                        Env(attrs = mapOf(key to to)),
                        Env(attrs = mapOf(key to to), run = mapOf("signature" to sig.value)),
                    ),
                ),
            ),
            status = status,
        )
    }

    private const val ID_HASH = 8
}

/** Writes a rule's JSON as the YAML of `rules/`: two-space indent, plain scalars where safe. */
internal object Yaml {
    private val reserved = setOf("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~")

    fun emit(root: JsonObject): String = StringBuilder().also { map(root, 0, false, it) }.toString()

    private fun map(o: JsonObject, indent: Int, inline: Boolean, sb: StringBuilder) {
        var first = inline
        for ((k, v) in o.fields) {
            if (v is JsonObject && v.fields.isEmpty()) continue
            if (!first) sb.append(" ".repeat(indent))
            first = false
            sb.append(k).append(':')
            value(v, indent, sb)
        }
    }

    private fun value(v: JsonValue, indent: Int, sb: StringBuilder) {
        when (v) {
            is JsonString -> sb.append(' ').append(scalar(v.value)).append('\n')

            is JsonInt -> sb.append(' ').append(v.value).append('\n')

            is JsonBool -> sb.append(' ').append(v.value).append('\n')

            is JsonObject -> {
                sb.append('\n')
                map(v, indent + 2, false, sb)
            }

            is JsonArray -> {
                sb.append('\n')
                for (item in v.items) {
                    sb.append(" ".repeat(indent + 2)).append("- ")
                    if (item is JsonObject) {
                        map(item, indent + 4, true, sb)
                    } else {
                        sb.append(scalar((item as JsonString).value)).append('\n')
                    }
                }
            }

            else -> sb.append(" null\n")
        }
    }

    private fun scalar(s: String): String {
        val plain = s.isNotEmpty() && (s[0] in 'a'..'z' || s[0] in 'A'..'Z') &&
            s.all { Tokens.isAlnum(it) || it in "_.:/-" } && !s.endsWith(":") &&
            Tokens.lower(s) !in reserved
        return if (plain) s else "'" + s.replace("'", "''") + "'"
    }
}
