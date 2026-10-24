package dev.gmitch215.drift.history

import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome
import dev.gmitch215.drift.model.Sample
import dev.gmitch215.drift.symptom.Spread

/**
 * The repeated executions of one command read as a [History]: the samples in the order they ran,
 * one run each. [History.state] then says whether the outcomes were mixed and how, with the same
 * flip rule as for CI runs. Nothing here classifies on its own.
 */
object Repeat {
    fun history(label: String, samples: List<Sample>): History = History.of(
        HistoryKey(label, null),
        samples.mapIndexed { i, s ->
            Run("${i + 1}", if (s.passed) RunOutcome.PASS else RunOutcome.FAIL)
        },
    )

    /** Whole percent of [part] in [total], rounded half up. */
    fun percent(part: Int, total: Int): Int = (part * 200 + total) / (2 * total)

    /** The lines `drift run --repeat` prints: pass rate, the history state and the spreads. */
    fun summary(label: String, samples: List<Sample>): List<String> {
        if (samples.isEmpty()) return emptyList()
        val passed = samples.count { it.passed }
        val failed = samples.size - passed
        val state = history(label, samples).state
        val out = ArrayList<String>()
        var rate = "${samples.size} runs, $passed passed (${percent(passed, samples.size)}%)"
        if (failed > 0) rate += ", $failed failed"
        if (passed > 0 && failed > 0) rate += "; history state ${state.name}${stateNote(state)}"
        out += rate
        spread("wall time", "ms", samples.map { it.wallMs })?.let { out += it }
        cpu(samples)?.let { out += it }
        spread("peak memory", "KiB", samples.mapNotNull { it.peakKb }, samples.size)
            ?.let { out += it }
        return out
    }

    /** A sentence for `drift diagnose` when [samples] mix passes and failures; null otherwise. */
    fun intermittent(who: String, label: String, samples: List<Sample>): String? {
        val failed = samples.count { !it.passed }
        if (failed == 0 || failed == samples.size) return null
        val state = history(label, samples).state
        return "$who is intermittent: $failed of ${samples.size} samples failed " +
            "(history state ${state.name}${stateNote(state)}). A difference between two " +
            "captures can explain a failure rate, and it does not say which runs fail."
    }

    private fun stateNote(state: HistoryState): String = when (state) {
        HistoryState.REGRESSION, HistoryState.RECOVERED ->
            ", one change of outcome, so not flaky by the two flip rule"

        else -> ""
    }

    private fun spread(
        name: String,
        unit: String,
        values: List<Long>,
        of: Int = values.size,
    ): String? {
        val s = Spread.of(values) ?: return null
        val partial = if (values.size < of) " (${values.size} of $of runs measured)" else ""
        return "$name median ${s.median} $unit, min ${s.min} $unit, max ${s.max} $unit$partial"
    }

    private fun cpu(samples: List<Sample>): String? {
        val user = Spread.of(samples.mapNotNull { it.userMs }) ?: return null
        val system = Spread.of(samples.mapNotNull { it.systemMs }) ?: return null
        return "cpu time median ${user.median} ms user, ${system.median} ms system"
    }
}
