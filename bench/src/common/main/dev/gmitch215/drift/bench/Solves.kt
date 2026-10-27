package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.string

/**
 * The label of a solved scenario: its class, the injected cause path (null for a control) and the
 * attributes the scenario declares as travelling with the cause.
 */
class SolveTruth(
    val id: String,
    val klass: String,
    val cause: String?,
    val bundle: List<String> = emptyList(),
)

/** One solved case directory: its id and the text of `certificate.json`, null when unreadable. */
class SolvedCase(val id: String, val certificate: String?)

/** 1-based rank of the cause in an order, null when the order does not hold it. */
class SolveRanks(val ranker: Int?, val prior: Int?)

enum class SolveOutcome(val id: String) {
    CONFIRMED_CORRECT("confirmed-correct"),
    CONFIRMED_WRONG("confirmed-wrong"),
    BUNDLE_HOLDS("bundle-holds"),
    BUNDLE_MISSES("bundle-misses"),
    NARROWED_HOLDS("narrowed-holds"),
    NARROWED_MISSES("narrowed-misses"),
    STUCK("stuck"),
    CONTROL_STUCK("control-stuck"),
    CONTROL_FALSE("control-false"),
    UNREADABLE("unreadable"),
}

internal class Reading(
    val kind: String,
    val members: List<String>,
    val minimal: List<String>,
    val candidates: List<String>,
    val necessary: List<String>,
    val sufficient: List<String>,
    val reason: String?,
    val head: String,
    val trials: Int,
) {
    val stuck: Boolean get() = kind == "stuck" || kind == SolveBench.NEEDS
}

class SolveCase internal constructor(
    val id: String,
    val klass: String,
    val cause: String?,
    val outcome: SolveOutcome,
    internal val reading: Reading?,
    val minimalHolds: Boolean,
    val declaredHeld: Boolean,
    val ranks: SolveRanks?,
) {
    val kind: String? get() = reading?.kind
    val trials: Int? get() = reading?.trials
    val stuck: Boolean get() = reading?.kind == "stuck" || reading?.kind == "needs"

    /** A verdict that is not stuck. */
    val verdict: Boolean get() = reading != null && !stuck
}

/**
 * The dev `drift solve` certificates scored against the injected causes. One class block per
 * scenario class and `all`, with Wilson intervals; see `bench/README.md`.
 */
class SolveScore(val cases: List<SolveCase>, val notSolved: List<String>) {
    fun toJson(): String = CanonicalJson.encode(
        obj(
            "schema" to JsonInt(1),
            "synthetic" to JsonBool(true),
            "scenarios" to JsonInt(cases.size.toLong()),
            "classes" to JsonObject(
                (SolveBench.CLASSES + ALL).associateWith { classJson(inClass(it)) },
            ),
            "trials" to trialsJson(),
            "stuck_by_reason" to counts(stuckCases().map { it.reading?.reason ?: "none" }),
            "stuck_by_statement" to counts(stuckCases().map { it.reading?.head ?: "none" }),
            "stuck_by_cause" to counts(stuckCases().mapNotNull { it.cause }),
            "ids" to JsonObject(
                SolveOutcome.entries.associate { o ->
                    o.id to JsonArray(
                        cases.filter { it.outcome == o }.map { JsonString(it.id) },
                    )
                },
            ),
            "not_solved" to JsonArray(notSolved.map { JsonString(it) }),
            "cases" to JsonArray(cases.map(::caseJson)),
        ),
    ) + "\n"

    fun report(name: String): String = buildString {
        appendLine("solves $name: ${cases.size} cases, ${notSolved.size} dev ids not solved")
        for (c in SolveBench.CLASSES + ALL) {
            val s = stats(inClass(c))
            appendLine("  $c (${s.n})")
            appendLine("    confirmed correct ${s.confirmedCorrect}, wrong ${s.confirmedWrong}")
            appendLine(
                "    bundle holds ${s.bundleHolds} (minimal set names it ${s.bundleMinimal}), " +
                    "misses ${s.bundleMisses} (${s.bundleMissDeclared} with a declared member)",
            )
            appendLine("    narrowed holds ${s.narrowedHolds}, misses ${s.narrowedMisses}")
            appendLine(
                "    stuck or needs ${s.stuck}, control names no cause ${s.controlStuck}, " +
                    "control false ${s.controlFalse}, unreadable ${s.unreadable}",
            )
            if (hasNeeds) {
                appendLine("    NEEDS ${s.needs}, genuine STUCK ${s.genuineStuck}")
            }
            appendLine("    CONFIRMED precision ${rate(s.precision)}")
            appendLine("    resolved ${rate(s.resolved)}")
            appendLine("    cause held ${rate(s.held)}")
        }
        val t = trialStats(cases.mapNotNull { it.trials })
        appendLine("  trials per case: median ${t.median}, range ${t.min} to ${t.max}")
    }

    private fun inClass(c: String): List<SolveCase> =
        if (c == ALL) cases else cases.filter { it.klass == c }

    private fun stuckCases() = cases.filter { it.stuck }

    private val hasNeeds = cases.any { it.kind == "needs" }

    private class Stats(val cases: List<SolveCase>) {
        val n = cases.size
        private fun count(o: SolveOutcome) = cases.count { it.outcome == o }
        val confirmedCorrect = count(SolveOutcome.CONFIRMED_CORRECT)
        val confirmedWrong = count(SolveOutcome.CONFIRMED_WRONG)
        val bundleHolds = count(SolveOutcome.BUNDLE_HOLDS)
        val bundleMinimal =
            cases.count { it.outcome == SolveOutcome.BUNDLE_HOLDS && it.minimalHolds }
        val bundleMisses = count(SolveOutcome.BUNDLE_MISSES)
        val bundleMissDeclared = cases.count { it.declaredHeld }
        val narrowedHolds = count(SolveOutcome.NARROWED_HOLDS)
        val narrowedMisses = count(SolveOutcome.NARROWED_MISSES)
        val stuck = count(SolveOutcome.STUCK)
        val needs = cases.count { it.kind == "needs" }
        val genuineStuck = cases.count { it.kind == "stuck" }
        val controlStuck = count(SolveOutcome.CONTROL_STUCK)
        val controlFalse = count(SolveOutcome.CONTROL_FALSE)
        val unreadable = count(SolveOutcome.UNREADABLE)
        val withCause = cases.count { it.cause != null }
        val confirmedVerdicts = cases.count { it.kind == "confirmed" }
        val bundleVerdicts = cases.count { it.kind == "confirmed-bundle" && it.cause != null }
        val precision = Rate(confirmedCorrect, confirmedVerdicts)
        val bundleRate = Rate(bundleHolds, bundleVerdicts)
        val bundleMinimalRate = Rate(bundleMinimal, bundleVerdicts)
        val resolved = Rate(confirmedCorrect + bundleMinimal, withCause)
        val held = Rate(confirmedCorrect + bundleHolds + narrowedHolds, withCause)
        val controls = cases.count { it.cause == null }
        val controlRate = Rate(controlStuck, controls)
    }

    private fun stats(list: List<SolveCase>) = Stats(list)

    private fun classJson(list: List<SolveCase>): JsonObject {
        val s = Stats(list)
        val ranked = list.filter { it.cause != null && it.ranks != null }
        fun rank(pick: (SolveRanks) -> Int?, k: Int) =
            Rate(ranked.count { c -> pick(c.ranks!!)?.let { it <= k } == true }, ranked.size)
        val base = obj(
            "scenarios" to JsonInt(s.n.toLong()),
            "with_cause" to JsonInt(s.withCause.toLong()),
            "confirmed_correct" to JsonInt(s.confirmedCorrect.toLong()),
            "confirmed_wrong" to JsonInt(s.confirmedWrong.toLong()),
            "bundle_holds" to JsonInt(s.bundleHolds.toLong()),
            "bundle_minimal_names_cause" to JsonInt(s.bundleMinimal.toLong()),
            "bundle_misses" to JsonInt(s.bundleMisses.toLong()),
            "bundle_misses_with_declared_member" to JsonInt(s.bundleMissDeclared.toLong()),
            "narrowed_holds" to JsonInt(s.narrowedHolds.toLong()),
            "narrowed_misses" to JsonInt(s.narrowedMisses.toLong()),
            "stuck" to JsonInt(s.stuck.toLong()),
            "control_stuck" to JsonInt(s.controlStuck.toLong()),
            "control_false" to JsonInt(s.controlFalse.toLong()),
            "unreadable" to JsonInt(s.unreadable.toLong()),
            "confirmed_precision" to rateJson(s.precision),
            "bundle_holds_rate" to rateJson(s.bundleRate),
            "bundle_minimal_rate" to rateJson(s.bundleMinimalRate),
            "resolution" to rateJson(s.resolved),
            "cause_held" to rateJson(s.held),
            "control_correct" to rateJson(s.controlRate),
            "comparison" to obj(
                "scenarios" to JsonInt(ranked.size.toLong()),
                "solver_confirmed" to
                    rateJson(Rate(ranked.count { it.confirmedRight() }, ranked.size)),
                "solver_resolved" to rateJson(
                    Rate(ranked.count { it.confirmedRight() || it.minimalRight() }, ranked.size),
                ),
                "ranker_top1" to rateJson(rank({ it.ranker }, 1)),
                "ranker_top3" to rateJson(rank({ it.ranker }, 3)),
                "prior_top1" to rateJson(rank({ it.prior }, 1)),
                "prior_top3" to rateJson(rank({ it.prior }, 3)),
            ),
        )
        if (!hasNeeds) return base
        return JsonObject(
            base.fields + mapOf(
                "needs" to JsonInt(s.needs.toLong()),
                "genuine_stuck" to JsonInt(s.genuineStuck.toLong()),
            ),
        )
    }

    private fun SolveCase.confirmedRight() = outcome == SolveOutcome.CONFIRMED_CORRECT

    private fun SolveCase.minimalRight() = outcome == SolveOutcome.BUNDLE_HOLDS && minimalHolds

    private fun rateJson(r: Rate): JsonObject = Evaluate.count(r.count, r.total)

    private fun rate(r: Rate): String {
        if (r.total == 0) return "n/a (0 of 0)"
        val w = r.wilson()
        return "${pct(r.micro)} (${r.count} of ${r.total}, Wilson ${pct(w.low)} to ${pct(w.high)})"
    }

    private fun pct(micro: Long): String {
        val tenth = Metrics.divRound(micro, 1000)
        return "${tenth / 10}.${tenth % 10}%"
    }

    private class Trials(val n: Int, val median: Int, val min: Int, val max: Int)

    private fun trialStats(values: List<Int>): Trials {
        if (values.isEmpty()) return Trials(0, 0, 0, 0)
        val v = values.sorted()
        val mid = v.size / 2
        val median = if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid] + 1) / 2
        return Trials(v.size, median, v.first(), v.last())
    }

    private fun trialsJson(): JsonObject {
        fun block(list: List<SolveCase>): JsonObject {
            val t = trialStats(list.mapNotNull { it.trials })
            return obj(
                "cases" to JsonInt(t.n.toLong()),
                "median" to JsonInt(t.median.toLong()),
                "min" to JsonInt(t.min.toLong()),
                "max" to JsonInt(t.max.toLong()),
            )
        }
        return JsonObject(
            mapOf("all" to block(cases)) +
                (SolveBench.KINDS + if (hasNeeds) listOf(SolveBench.NEEDS) else emptyList())
                    .associateWith { k -> block(cases.filter { it.kind == k }) },
        )
    }

    private fun counts(values: List<String>): JsonObject = JsonObject(
        values.groupingBy { it }.eachCount().mapValues { JsonInt(it.value.toLong()) },
    )

    private fun caseJson(c: SolveCase): JsonObject = necessaryJson(c, caseFields(c))

    private fun necessaryJson(c: SolveCase, base: JsonObject): JsonObject {
        val extra = buildMap {
            val needed = c.reading?.necessary.orEmpty()
            val enough = c.reading?.sufficient.orEmpty()
            if (needed.isNotEmpty()) put("necessary", JsonArray(needed.map { JsonString(it) }))
            if (enough.isNotEmpty()) put("sufficient", JsonArray(enough.map { JsonString(it) }))
        }
        return if (extra.isEmpty()) base else JsonObject(base.fields + extra)
    }

    private fun caseFields(c: SolveCase): JsonObject = obj(
        "id" to JsonString(c.id),
        "class" to JsonString(c.klass),
        "cause" to jsonOrNull(c.cause),
        "kind" to jsonOrNull(c.kind),
        "outcome" to JsonString(c.outcome.id),
        "reason" to jsonOrNull(c.reading?.reason),
        "statement_head" to jsonOrNull(c.reading?.head),
        "minimal_set" to JsonArray(c.reading?.minimal.orEmpty().map { JsonString(it) }),
        "minimal_names_cause" to JsonBool(c.minimalHolds),
        "declared_member_held" to JsonBool(c.declaredHeld),
        "members" to JsonInt(c.reading?.members?.size?.toLong() ?: 0),
        "trials" to (c.trials?.let { JsonInt(it.toLong()) } ?: JsonNull),
        "ranker_rank" to (c.ranks?.ranker?.let { JsonInt(it.toLong()) } ?: JsonNull),
        "prior_rank" to (c.ranks?.prior?.let { JsonInt(it.toLong()) } ?: JsonNull),
    )

    private companion object {
        const val ALL = "all"
    }
}

object SolveBench {
    val CLASSES = listOf("single", "bundle", "decoy", "intermittent", "control")
    val KINDS = listOf("confirmed", "confirmed-bundle", "narrowed", "stuck")
    const val NEEDS = "needs"

    /**
     * Scores [cases] against [truths]. A case outside [dev] is refused, as is an id that appears
     * twice; a case whose certificate cannot be read counts as `unreadable`.
     */
    fun score(
        dev: Set<String>,
        truths: List<SolveTruth>,
        cases: List<SolvedCase>,
        ranks: Map<String, SolveRanks> = emptyMap(),
    ): SolveScore {
        cases.firstOrNull { it.id !in dev }?.let { throw SealedException(it.id) }
        val seen = cases.map { it.id }
        require(seen.size == seen.toSet().size) { "a case id appears in two solved directories" }
        val byId = truths.associateBy { it.id }
        val scored = cases.sortedBy { it.id }.map { c ->
            val t = byId[c.id] ?: throw IllegalArgumentException("no scenario ${c.id}")
            judge(t, read(c.certificate), ranks[c.id])
        }
        return SolveScore(scored, (dev - seen.toSet()).sorted())
    }

    private fun judge(t: SolveTruth, r: Reading?, ranks: SolveRanks?): SolveCase {
        val cause = t.cause
        val minimalHolds = cause != null && r != null &&
            (cause in r.minimal || cause in r.necessary || cause in r.sufficient)
        val outcome = when {
            r == null -> SolveOutcome.UNREADABLE

            cause == null ->
                if (r.stuck) SolveOutcome.CONTROL_STUCK else SolveOutcome.CONTROL_FALSE

            r.stuck -> SolveOutcome.STUCK

            r.kind == "confirmed" ->
                if (r.minimal == listOf(cause)) {
                    SolveOutcome.CONFIRMED_CORRECT
                } else {
                    SolveOutcome.CONFIRMED_WRONG
                }

            r.kind == "confirmed-bundle" ->
                if (cause in r.members) SolveOutcome.BUNDLE_HOLDS else SolveOutcome.BUNDLE_MISSES

            else ->
                if (cause in r.candidates || minimalHolds) {
                    SolveOutcome.NARROWED_HOLDS
                } else {
                    SolveOutcome.NARROWED_MISSES
                }
        }
        val declaredHeld = outcome == SolveOutcome.BUNDLE_MISSES &&
            r != null && r.members.any { it in t.bundle }
        return SolveCase(t.id, t.klass, cause, outcome, r, minimalHolds, declaredHeld, ranks)
    }

    private fun strings(o: JsonObject, key: String): List<String> =
        (o.fields[key] ?: JsonArray(emptyList())).array().map { it.string() }

    private fun read(text: String?): Reading? {
        if (text == null) return null
        return try {
            val root = CanonicalJson.parse(text) as? JsonObject ?: return null
            val v = root.fields["verdict"] as? JsonObject ?: return null
            val kind = v.fields["kind"]?.string() ?: return null
            if (kind !in KINDS && kind != NEEDS) return null
            val minimal = strings(v, "minimalSet")
            if (kind == "confirmed" && minimal.size != 1) return null
            val experiments = (root.fields["experiments"] ?: JsonArray(emptyList())).array()
            val trials = experiments.sumOf { e ->
                val f = (e as JsonObject).fields
                arm(f, "control") + arm(f, "treatment")
            }
            Reading(
                kind,
                strings(v, "members"),
                minimal,
                strings(v, "candidates"),
                strings(v, "necessary"),
                strings(v, "sufficient"),
                (v.fields["reason"] as? JsonString)?.value,
                head((v.fields["statement"] as? JsonString)?.value.orEmpty()),
                trials,
            )
        } catch (e: JsonException) {
            null
        } catch (e: ClassCastException) {
            null
        }
    }

    private fun arm(f: Map<String, JsonValue>, key: String): Int =
        ((f.getValue(key) as JsonObject).fields.getValue("trials") as JsonInt).value.toInt()

    /** The first clause of a statement, before its numbers, so equal causes count together. */
    private fun head(statement: String): String {
        val cut = listOf(":", " (", " at alpha").map { statement.indexOf(it) }
            .filter { it >= 0 }.minOrNull() ?: statement.length
        return statement.substring(0, cut)
    }

    /**
     * The rank of each cause in the ranker's order and in the dimension-prior order, read from the
     * `probed` section of `evaluation.json`.
     */
    fun ranks(evaluation: String): Map<String, SolveRanks> {
        val probed = (CanonicalJson.parse(evaluation) as JsonObject).fields.getValue("probed")
        return (probed as JsonObject).fields.getValue("scenarios").array().associate { s ->
            val f = (s as JsonObject).fields
            val cause = (f["cause"] as? JsonString)?.value
            val ranked = f.getValue("ranked").array()
            val ranker = ranked.indexOfFirst {
                (it as JsonObject).fields["cause"] == JsonBool(true)
            }
            val prior = Models.priorOrder(f.getValue("changed").array().map { it.string() })
            f.getValue("id").string() to SolveRanks(
                if (ranker < 0) null else ranker + 1,
                cause?.let { prior.indexOf(it).takeIf { i -> i >= 0 }?.plus(1) },
            )
        }
    }
}
