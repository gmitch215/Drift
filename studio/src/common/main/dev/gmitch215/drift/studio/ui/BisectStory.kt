package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.delta.BisectPlan
import dev.gmitch215.drift.delta.BisectState
import dev.gmitch215.drift.delta.Flip
import dev.gmitch215.drift.model.Capsule

const val BISECT_ABOUT =
    "A passing and a failing environment can differ in many settings. A bisection finds the " +
        "smallest group of them that still makes the job fail, by testing the passing " +
        "environment with some settings changed to their failing values. Studio shows the plan " +
        "and runs nothing."

/** One setting that differs: what it is, and its value in the passing and the failing capsule. */
class SettingRow(val title: String, val change: String)

/** The first test of the plan: what to change and the commands that record how it went. */
class TestWords(val sentence: String, val settings: List<SettingRow>, val commands: List<String>)

class BisectView(
    val plan: BisectPlan,
    val headline: String,
    val settings: List<SettingRow>,
    val first: TestWords?,
)

object BisectStory {
    const val NONE =
        "These two capsules have no static setting that differs, so there is nothing to bisect."

    const val ORDER = "The settings Drift thinks likelier to matter come first."

    const val NOT_RUN = "Studio does not run tests. To carry the plan out, use the command line:"

    fun of(green: Capsule, red: Capsule): BisectView? {
        val plan = BisectPlan.of(green, red) ?: return null
        val rows = plan.flips.map(::row)
        return BisectView(plan, headline(plan, rows), rows, first(plan, rows))
    }

    private fun row(flip: Flip) = SettingRow(
        Plain.title(flip.path),
        "${Plain.value(flip.green)} -> ${Plain.value(flip.red)}",
    )

    private fun headline(plan: BisectPlan, rows: List<SettingRow>): String {
        val n = plan.flips.size
        return if (n == 1) {
            "One setting differs between the passing and the failing capsule: " +
                "${rows.first().title.replaceFirstChar { it.lowercase() }}. No test is needed " +
                "to find the cause."
        } else {
            "$n settings differ between the passing and the failing capsule. The first test " +
                "changes ${plan.firstSetSize()} of them."
        }
    }

    private fun BisectPlan.firstSetSize(): Int = when (val s = state()) {
        is BisectState.Next -> s.set.size
        is BisectState.Done -> s.minimal.size
    }

    private fun first(plan: BisectPlan, rows: List<SettingRow>): TestWords? {
        val next = plan.state() as? BisectState.Next ?: return null
        val n = next.set.size
        val noun = if (n == 1) "setting" else "settings"
        return TestWords(
            "Test ${next.step}: start from the passing environment, change these $n $noun to " +
                "their failing values, keep everything else as it was, and run the failing job.",
            next.set.map { rows[it] },
            listOf(
                "drift bisect plan <passing capsule> <failing capsule> --out plan.json",
                "drift bisect apply plan.json --step ${next.step} --red",
                "drift bisect apply plan.json --step ${next.step} --green",
            ),
        )
    }

    const val OUTCOME =
        "Run the first command once to write the plan. After the test, run the second command " +
            "if the job failed or the third if it passed."
}
