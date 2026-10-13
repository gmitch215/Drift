package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.studio.StudioState

/** One-sentence definitions, shown where a term first matters on a screen. */
object Glossary {
    const val CAPSULE =
        "A capsule is a saved snapshot of one run: its settings, tool versions and check results."
    const val PROBE =
        "A probe is a small check that shows how a piece of Kotlin behaves on one target."
    const val RULE =
        "A rule is a documented pattern: a kind of change that is known to cause a kind of failure."
    const val TIER =
        "A tier says how strong the evidence for a difference is: Difference, Correlated, " +
            "Plausible or Known, weakest first."
}

/** A tier as a newcomer reads it: its place among the four and what it rests on. */
data class TierWords(val level: Int, val label: String, val phrase: String)

data class Reading(val headline: String, val evidence: String?, val caution: String)

object Plain {
    const val CAUTION = "This is a ranking, not a confirmed cause."
    const val NO_VALUE = "not set"
    const val TIER_COUNT = 4
    private const val MAX_VALUE = 48

    private val named = mapOf(
        "env.TZ" to "time zone (TZ)",
        "env.LANG" to "language setting (LANG)",
        "env.LC_ALL" to "locale override (LC_ALL)",
    )

    /** What a path names, to follow "the"; unknown families keep their path. */
    fun name(path: String): String {
        named[path]?.let { return it }
        return when {
            path.startsWith("env.") -> "environment variable ${path.removePrefix("env.")}"

            path.startsWith("deps.") && path.endsWith(".version") ->
                "${path.removePrefix("deps.").removeSuffix(".version")} package version"

            path.startsWith("tool.") && path.endsWith(".version") ->
                "${path.removePrefix("tool.").removeSuffix(".version")} version"

            path.startsWith("ci.") -> "CI setting ${path.removePrefix("ci.")}"

            path.startsWith("os.") -> "operating system setting ${path.removePrefix("os.")}"

            else -> "setting $path"
        }
    }

    fun title(path: String): String = name(path).replaceFirstChar { it.uppercase() }

    fun value(v: String?): String = when {
        v == null -> NO_VALUE
        v.length > MAX_VALUE -> v.take(MAX_VALUE) + "..."
        else -> v
    }

    fun change(d: AttributeDiff): String = "${value(d.before)} -> ${value(d.after)}"

    fun tier(tier: Tier): TierWords = when (tier) {
        Tier.DIFFERENCE -> TierWords(
            1,
            "Difference",
            "It differs between the runs, and no rule or check points at it.",
        )

        Tier.CORRELATED -> TierWords(
            2,
            "Correlated",
            "It differs, and a check in the same area also differs or it tracks failing runs " +
                "in earlier history.",
        )

        Tier.PLAUSIBLE -> TierWords(
            3,
            "Plausible",
            "It differs, and a rule or a check on that same setting points at it.",
        )

        Tier.KNOWN -> TierWords(
            4,
            "Known",
            "It differs, and a rule whose source names this symptom matches it.",
        )
    }

    fun level(tier: Tier): String {
        val words = tier(tier)
        return "Evidence level ${words.level} of $TIER_COUNT (${words.label})"
    }

    /** The top of the ranking in words; every phrase is fixed text or a value from [state]. */
    fun reading(state: StudioState): Reading {
        val top = state.relevant.firstOrNull() ?: return Reading(
            "No difference between these two runs was ranked, so Drift has no suspect.",
            null,
            "That does not mean the runs are identical; Drift ranks only the kinds of change it " +
                "recognizes.",
        )
        val diff = state.changed.changes.firstOrNull { it.path == top.path }
        val thing = "the ${name(top.path)}"
        val values = if (diff == null) {
            ""
        } else {
            ": it was ${setTo(diff.before)} on the passing run and ${setTo(diff.after)} " +
                "on the failing run"
        }
        val headline = if (state.competing.size > 1) {
            "Drift cannot name one cause: ${state.competing.size} differences tie for the top " +
                "place. The first in the list is $thing$values."
        } else {
            "The likeliest cause is $thing$values."
        }
        val words = tier(top.tier)
        return Reading(
            headline,
            "${level(top.tier)}. ${words.phrase}",
            CAUTION,
        )
    }

    private fun setTo(v: String?) = if (v == null) NO_VALUE else "set to ${value(v)}"
}
