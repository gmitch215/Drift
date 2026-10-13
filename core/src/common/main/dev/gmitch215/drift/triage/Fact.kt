package dev.gmitch215.drift.triage

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

/**
 * A confidence in thousandths, 0 to 1000, printed as `0.870`. Integers keep the arithmetic and
 * the text identical on every target.
 */
data class Confidence(val permille: Int) : Comparable<Confidence> {
    init {
        require(permille in 0..MAX) { "confidence out of range: $permille" }
    }

    /** `permille * factor / 1000`, rounded down. */
    fun scale(factor: Int) = Confidence(permille * factor / MAX)

    override fun compareTo(other: Confidence) = permille.compareTo(other.permille)

    override fun toString(): String {
        val frac = (permille % MAX).toString().padStart(3, '0')
        return "${permille / MAX}.$frac"
    }

    companion object {
        const val MAX = 1000

        /** The chance that at least one of independent sources is right, capped below 1. */
        fun any(items: List<Confidence>): Confidence {
            var miss = MAX.toLong() * MAX
            for (c in items) miss = miss * (MAX - c.permille) / MAX
            return Confidence(minOf(CAP, (MAX - miss / MAX).toInt()))
        }

        const val CAP = 990

        /** Reads `0.9`, `.95` or `1`; null for anything else. */
        fun parse(text: String): Confidence? {
            val whole = text.substringBefore('.')
            val frac = text.substringAfter('.', "")
            if (frac.length > 3 || (whole.isEmpty() && frac.isEmpty())) return null
            if (!(whole + frac).all { it in '0'..'9' }) return null
            val w = if (whole.isEmpty()) 0 else whole.toIntOrNull() ?: return null
            val f = frac.padEnd(3, '0').toInt()
            val total = w * MAX + f
            return if (total <= MAX) Confidence(total) else null
        }
    }
}

/** A place in the redacted issue text: 1-based line and column, and the length of the value. */
data class Span(val line: Int, val col: Int, val length: Int) {
    override fun toString() = "$line:$col"

    fun toJson(): JsonObject = obj(
        "line" to JsonInt(line.toLong()),
        "col" to JsonInt(col.toLong()),
        "length" to JsonInt(length.toLong()),
    )
}

/**
 * How a value was found. [base] is the confidence before agreement, conflict and hedging. The
 * table is documented in ADVANCED_USAGE.md; `tool-output`, `key-value` and `pattern` were set
 * from the dev split of the triage corpus (`evalTriage`), `section` is the starting value.
 */
enum class Via(val id: String, val base: Int) {
    /** The value under a heading or label that names the field, as an issue form renders it. */
    SECTION("section", 950),

    /** A verbatim tool line: `openjdk version "21"`, `Python 3.12.1`, a Dockerfile `FROM`. */
    TOOL("tool-output", 900),

    /** A `Key: value` line, a table row, an `ENV=value` line or a build-file coordinate. */
    KEY_VALUE("key-value", 490),

    /** A known name followed by a version in running text. */
    PATTERN("pattern", 700),
}

/** [PASSING] is the environment where the report says it worked, [FAILING] where it broke. */
enum class Side(val id: String) {
    FAILING("failing"),
    PASSING("passing"),
}

/**
 * One environment fact as a Drift attribute key and value (`tool.node.version`, `run:signature`).
 * [agreed] counts the other spans that gave the same value; [alternatives] holds other values
 * seen for the same key and side, which lower [confidence].
 */
data class Fact(
    val key: String,
    val value: String,
    val side: Side,
    val confidence: Confidence,
    val via: Via,
    val span: Span,
    val agreed: Int = 0,
    val alternatives: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = obj(
        "key" to JsonString(key),
        "value" to JsonString(value),
        "side" to JsonString(side.id),
        "confidence" to JsonInt(confidence.permille.toLong()),
        "via" to JsonString(via.id),
        "span" to span.toJson(),
        "agreed" to JsonInt(agreed.toLong()),
        "alternatives" to JsonArray(alternatives.map(::JsonString)),
    )
}
