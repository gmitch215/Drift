package dev.gmitch215.drift.delta

import dev.gmitch215.drift.diff.CapsuleDiff
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
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.rank.Ranker

/** One static attribute that differs; [green] or [red] is null when the side lacks it. */
data class Flip(val path: String, val green: String?, val red: String?) {
    fun toJson(): JsonObject = obj(
        "path" to JsonString(path),
        "green" to jsonOrNull(green),
        "red" to jsonOrNull(red),
    )
}

enum class TrialOutcome { GREEN, RED }

/** A tested set of [Flip] indices (ascending) and what the test did with it. */
data class Trial(val set: List<Int>, val outcome: TrialOutcome)

sealed interface BisectState {
    /** [step] is the number to pass to `apply`; [set] holds the flips to set on the green side. */
    data class Next(val step: Int, val set: List<Int>) : BisectState

    /** [minimal] is a set of flips that still fails and loses that when any one is removed. */
    data class Done(val minimal: List<Int>) : BisectState
}

sealed interface BisectApply {
    data class Updated(val plan: BisectPlan) : BisectApply

    data class Refused(val reason: String) : BisectApply
}

/**
 * A bisection over the static attribute differences of a green and a red capsule, run by hand
 * or by a script: each pending set is a test of the green environment with those attributes
 * set to their red values. The plan is the whole state; [state] replays [Ddmin] over the
 * recorded [trials] and stops at the first set that has no recorded outcome. The full set is
 * the red environment and counts as failing; the empty set is the green one and passes.
 */
data class BisectPlan(
    val green: String,
    val red: String,
    val greenSha256: String,
    val redSha256: String,
    val flips: List<Flip>,
    val trials: List<Trial> = emptyList(),
) {
    private class Pending(val set: List<Int>) : RuntimeException()

    fun state(): BisectState {
        val known = trials.associate { it.set to it.outcome }
        return try {
            val result = Ddmin.run(flips.indices.toList()) { subset ->
                when {
                    subset.size == flips.size -> Verdict.FAIL

                    else -> when (known[subset] ?: throw Pending(subset)) {
                        TrialOutcome.RED -> Verdict.FAIL
                        TrialOutcome.GREEN -> Verdict.PASS
                    }
                }
            }
            BisectState.Done(result.minimal)
        } catch (p: Pending) {
            BisectState.Next(trials.size + 1, p.set)
        }
    }

    /**
     * Records [outcome] for [step], which must be the pending step; repeating the last recorded
     * outcome is a no-op and a different one is refused.
     */
    fun apply(step: Int, outcome: TrialOutcome): BisectApply {
        trials.getOrNull(step - 1)?.let {
            return if (it.outcome == outcome) {
                BisectApply.Updated(this)
            } else {
                BisectApply.Refused("step $step already holds ${it.outcome.label()}")
            }
        }
        return when (val now = state()) {
            is BisectState.Done -> BisectApply.Refused("the bisection is finished")

            is BisectState.Next ->
                if (now.step != step) {
                    BisectApply.Refused("the pending step is ${now.step}, not $step")
                } else {
                    BisectApply.Updated(copy(trials = trials + Trial(now.set, outcome)))
                }
        }
    }

    fun toJson(): JsonObject {
        val now = state()
        return obj(
            "schema" to JsonInt(SCHEMA),
            "green" to JsonString(green),
            "red" to JsonString(red),
            "greenSha256" to JsonString(greenSha256),
            "redSha256" to JsonString(redSha256),
            "flips" to JsonArray(flips.map { it.toJson() }),
            "trials" to JsonArray(
                trials.mapIndexed { i, t ->
                    obj(
                        "step" to JsonInt(i + 1L),
                        "set" to JsonArray(t.set.map { JsonInt(it.toLong()) }),
                        "outcome" to JsonString(t.outcome.label()),
                    )
                },
            ),
            "status" to JsonString(if (now is BisectState.Done) "done" else "open"),
            "next" to when (now) {
                is BisectState.Next -> obj(
                    "step" to JsonInt(now.step.toLong()),
                    "set" to JsonArray(now.set.map { JsonInt(it.toLong()) }),
                )

                is BisectState.Done -> JsonNull
            },
            "minimal" to when (now) {
                is BisectState.Done -> JsonArray(now.minimal.map { JsonInt(it.toLong()) })
                is BisectState.Next -> JsonNull
            },
        )
    }

    fun canonical(): String = CanonicalJson.encode(toJson())

    companion object {
        const val SCHEMA = 1L

        /** Null when no static attribute differs. Likelier culprits come first. */
        fun of(green: Capsule, red: Capsule): BisectPlan? {
            val changes = CapsuleDiff.diff(green, red).changes.associateBy { it.path }
            if (changes.isEmpty()) return null
            val ranked = Ranker.rank(green, red).candidates.map { it.path }.filter { it in changes }
            val order = ranked + changes.keys.filter { it !in ranked }.sorted()
            return BisectPlan(
                green.label,
                red.label,
                green.hash(),
                red.hash(),
                order.map { Flip(it, changes.getValue(it).before, changes.getValue(it).after) },
            )
        }

        fun parse(text: String): BisectPlan = fromJson(CanonicalJson.parse(text))

        fun fromJson(json: JsonValue): BisectPlan {
            val o = json.obj()
            val schema = o.require("schema").long()
            if (schema != SCHEMA) throw JsonException("unsupported bisect plan schema $schema")
            fun optional(v: JsonValue) = if (v == JsonNull) null else v.string()
            val flips = o.require("flips").array().map {
                val f = it.obj()
                Flip(
                    f.require("path").string(),
                    optional(f.require("green")),
                    optional(f.require("red")),
                )
            }
            val trials = o.require("trials").array().map {
                val t = it.obj()
                val label = t.require("outcome").string()
                Trial(
                    t.require("set").array().map { i -> i.long().toInt() },
                    TrialOutcome.entries.firstOrNull { e -> e.label() == label }
                        ?: throw JsonException("unknown trial outcome $label"),
                )
            }
            val sets = trials.flatMap { it.set }
            if (sets.any { it !in flips.indices }) throw JsonException("trial set out of range")
            return BisectPlan(
                o.require("green").string(),
                o.require("red").string(),
                o.require("greenSha256").string(),
                o.require("redSha256").string(),
                flips,
                trials,
            )
        }
    }
}

fun TrialOutcome.label(): String = name.lowercase()
