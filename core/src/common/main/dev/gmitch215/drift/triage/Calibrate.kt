package dev.gmitch215.drift.triage

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string

/** The starting confidence of each [Via] path: what `triage calibrate` writes. */
object ConfidenceTable {
    const val SCHEMA = 1L
    const val MIN_BASE = 100
    const val MAX_BASE = 990

    /** The `bases` of a table file; a path the file leaves out keeps its [Via.base]. */
    fun parse(text: String): Map<Via, Int> {
        val root = try {
            CanonicalJson.parse(text) as? JsonObject
        } catch (e: JsonException) {
            throw IllegalArgumentException("not JSON: ${e.message}")
        } ?: throw IllegalArgumentException("a table is a JSON object")
        require((root.fields["schema"] as? JsonInt)?.value == SCHEMA) { "expected schema $SCHEMA" }
        val bases = root.fields["bases"] as? JsonObject
            ?: throw IllegalArgumentException("a table needs a bases object")
        return bases.fields.entries.associate { (id, value) ->
            val via = Via.entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown path: $id")
            val n = (value as? JsonInt)?.value
            require(n != null && n in MIN_BASE..MAX_BASE) {
                "$id must be an integer from $MIN_BASE to $MAX_BASE"
            }
            via to n.toInt()
        }
    }
}

/** Squares and count of the facts scored in one slice; the Brier score is their ratio. */
class BrierScore(val facts: Long, private val squares: Long) {
    /** Four decimals, or `n/a` with no facts. */
    val text: String
        get() {
            if (facts == 0L) return "n/a"
            val scaled = (squares * SCALE + facts * HALF) / (facts * MILLION)
            return "${scaled / SCALE}." + (scaled % SCALE).toString().padStart(DIGITS, '0')
        }

    private companion object {
        const val SCALE = 10_000L
        const val HALF = 50L
        const val MILLION = 1_000_000L
        const val DIGITS = 4
    }
}

class Calibration(
    val bases: Map<Via, Int>,
    val seed: String,
    val issues: Int,
    val dev: Map<Via, Long>,
    val before: BrierScore,
    val after: BrierScore,
) {
    /** The table file: the bases, plus the split and the held-out scores they came with. */
    fun toJson(): String = CanonicalJson.encode(
        obj(
            "schema" to JsonInt(ConfidenceTable.SCHEMA),
            "bases" to JsonObject(
                bases.entries.associate { it.key.id to JsonInt(it.value.toLong()) },
            ),
            "seed" to JsonString(seed),
            "issues" to JsonInt(issues.toLong()),
            "heldOutFacts" to JsonInt(before.facts),
            "heldOutBrierBefore" to JsonString(before.text),
            "heldOutBrierAfter" to JsonString(after.text),
        ),
    ) + "\n"

    fun text(): String = buildString {
        append("$issues issues, split seed $seed\n")
        for (via in Via.entries) {
            val was = via.base
            val now = bases.getValue(via)
            val n = dev[via] ?: 0L
            append("  ${via.id.padEnd(12)} $was -> $now ($n dev facts)\n")
        }
        append("held-out facts ${before.facts}: Brier ${before.text} -> ${after.text}\n")
    }
}

/**
 * Recalibrates the starting confidence of each path on a team's own corpus. Issue bodies that
 * are issue-form output (`### Label` sections) state their environment under labelled headings;
 * those section facts are the truth, the sections are cut out, and the extractor reads what is
 * left. A path's base is scaled by its accuracy over its mean confidence on the dev side of a
 * seeded split, and the held-out side scores the result.
 */
object Calibrate {
    const val SEED = "drift-triage-split-v1"
    const val DEV_BELOW = 128
    const val MIN_FACTS = 100L
    private const val STEP = 10
    private val UNJUDGED = setOf("runtime.container.image")
    private val heading = Regex("^#{1,6}\\s")

    private class Issue(val repo: String, val number: Long, val body: String)

    private class Tally {
        var facts = 0L
        var correct = 0L
        var confidence = 0L
        var squares = 0L

        fun add(permille: Int, ok: Boolean) {
            val miss = if (ok) Confidence.MAX - permille else permille
            facts++
            if (ok) correct++
            confidence += permille
            squares += miss.toLong() * miss
        }
    }

    /** True on the dev side; the split depends only on [seed], [repo] and [number]. */
    fun isDev(seed: String, repo: String, number: Long): Boolean {
        val digest = Sha256.digest("$seed:$repo#$number".encodeToByteArray())
        return (digest[0].toInt() and 0xff) < DEV_BELOW
    }

    /**
     * [lines] is a corpus file as `buildCorpus` writes it: an opening line, one JSON issue per
     * line (after the first with a leading comma) and a closing `]`.
     */
    fun run(lines: List<String>, seed: String = SEED, minFacts: Long = MIN_FACTS): Calibration {
        val issues = lines.drop(1).takeWhile { !it.startsWith("]") }.map(::parse)
        val dev = mutableMapOf<Via, Tally>()
        for (issue in issues.filter { isDev(seed, it.repo, it.number) }) {
            for ((f, ok) in scored(issue, emptyMap()).orEmpty()) {
                dev.getOrPut(f.via) { Tally() }.add(f.confidence.permille, ok)
            }
        }
        val bases = Via.entries.associateWith { via ->
            dev[via]?.takeIf { it.facts >= minFacts && it.confidence > 0 }?.let { t ->
                val raw = (via.base.toLong() * t.correct * Confidence.MAX / t.confidence).toInt()
                (raw / STEP * STEP).coerceIn(ConfidenceTable.MIN_BASE, ConfidenceTable.MAX_BASE)
            } ?: via.base
        }
        val before = Tally()
        val after = Tally()
        for (issue in issues.filterNot { isDev(seed, it.repo, it.number) }) {
            for ((f, ok) in scored(issue, emptyMap()).orEmpty()) {
                before.add(f.confidence.permille, ok)
            }
            for ((f, ok) in scored(issue, bases).orEmpty()) {
                after.add(f.confidence.permille, ok)
            }
        }
        return Calibration(
            bases,
            seed,
            issues.size,
            dev.mapValues { it.value.facts },
            BrierScore(before.facts, before.squares),
            BrierScore(after.facts, after.squares),
        )
    }

    private fun parse(line: String): Issue {
        val o = CanonicalJson.parse(line.removePrefix(",")) as JsonObject
        return Issue(
            o.require("repo").string(),
            o.require("number").long(),
            o.require("body").string(),
        )
    }

    /** Each failing-side fact read from the form-less rest of [issue], and whether it matches. */
    private fun scored(issue: Issue, bases: Map<Via, Int>): List<Pair<Fact, Boolean>>? {
        if (!issue.body.trimStart().startsWith("### ")) return null
        val truth = Triage.run(issue.body, rules = emptyList()).facts
            .filter { it.via == Via.SECTION && it.side == Side.FAILING && it.key !in UNJUDGED }
        if (truth.isEmpty()) return null
        val values = truth.groupBy { it.key }.mapValues { e -> e.value.map { it.value }.toSet() }
        val text = withoutSections(issue.body, truth)
        val rest = Triage.run(text, TriageOptions(bases = bases), emptyList())
        return rest.facts.filter { it.side == Side.FAILING && it.key in values }
            .map { f -> f to values.getValue(f.key).any { agrees(f.value, it) } }
    }

    /** [body] without the heading blocks that hold a section fact in [truth]. */
    fun withoutSections(body: String, truth: List<Fact>): String {
        val lines = Triage.clean(body).split('\n')
        val drop = BooleanArray(lines.size)
        for (f in truth) {
            var start = f.span.line - 1
            while (start > 0 && !heading.containsMatchIn(lines[start])) start--
            var end = f.span.line
            while (end < lines.size && !heading.containsMatchIn(lines[end])) end++
            for (i in start until end) drop[i] = true
        }
        return lines.filterIndexed { i, _ -> !drop[i] }.joinToString("\n")
    }

    /** Equal values, or one cut at a dot from the other, as `Combine` does. */
    fun agrees(a: String, b: String) = a == b || b.startsWith("$a.") || a.startsWith("$b.")
}
