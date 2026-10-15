package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.set.SetCapsule
import dev.gmitch215.drift.set.SetDiagnosis
import dev.gmitch215.drift.set.SetResult
import dev.gmitch215.drift.set.Split

const val SET_ABOUT =
    "Capsules from several runs, some that passed and some that failed, can show what " +
        "separates the two groups. Drift lists the setting values that failing capsules hold " +
        "and passing capsules do not, strongest first. Studio only reads; it changes nothing."

const val SET_ABOUT_HOME =
    "Find the setting that separates several passing capsules from several failing ones."

/** A capsule added to the set, with whether its run failed. */
class SetEntry(val name: String, val failing: Boolean, val capsule: Capsule)

/** One setting value: what it is, where it is held and whether it alone splits the set. */
class SplitRow(val title: String, val value: String, val counts: String, val separates: Boolean)

class SetView(val result: SetResult, val headline: String, val rows: List<SplitRow>)

object SetStory {
    const val NEED_BOTH = "Add at least one passing and one failing capsule."

    const val NONE =
        "No setting value is held by a failing capsule and missing from some other capsule, so " +
            "nothing in this set separates the failing runs."

    const val CAVEAT =
        "A setting that separates the set is a suspect, not a confirmed cause: with few " +
            "capsules, several settings can change together."

    fun of(entries: List<SetEntry>): SetView? {
        if (entries.none { it.failing } || entries.all { it.failing }) return null
        val result = SetDiagnosis.of(entries.map { SetCapsule(it.name, it.failing, it.capsule) })
        return SetView(result, headline(result), result.splits.map { row(it, result) })
    }

    private fun row(split: Split, result: SetResult) = SplitRow(
        Plain.title(split.path),
        Plain.value(split.value),
        "Held by ${split.failing} of ${result.failing} failing and ${split.passing} of " +
            "${result.passing} passing capsules.",
        split.separates,
    )

    private fun headline(result: SetResult): String {
        val sets = result.separating
        val top = result.splits.firstOrNull() ?: return NONE
        val first = "the ${Plain.name(top.path)}, set to ${Plain.value(top.value)}"
        return when {
            sets.size == 1 ->
                "One setting separates the failing capsules from the passing ones: $first. All " +
                    "${result.failing} failing capsules hold it and none of the " +
                    "${result.passing} passing capsules do."

            sets.size > 1 ->
                "${sets.size} settings separate the failing capsules from the passing ones and " +
                    "this set cannot tell them apart. The first in the list is $first."

            else ->
                "No single setting separates the failing capsules from the passing ones. The " +
                    "closest is $first, held by ${top.failing} of ${result.failing} failing " +
                    "and ${top.passing} of ${result.passing} passing capsules."
        }
    }
}
