package dev.gmitch215.drift.history

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.jsonOrNull
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.model.RunOutcome

/** One workflow and, when the history is job scoped, one job inside it. */
data class HistoryKey(val workflow: String, val job: String?)

data class HistoryEntry(val key: HistoryKey, val run: Run)

/** How [History.of] orders the runs it is given. */
enum class RunOrder {
	/** Keep the caller's order, oldest first. */
	GIVEN,

	/** Sort by numeric run id, oldest first; every id must be a decimal string. */
	RUN_ID,
}

/**
 * What the shape of a history says, over its decisive runs (pass or fail; cancelled and unknown
 * runs say nothing). [FLAKY] is two or more outcome flips, so a red that recovers is flaky.
 */
enum class HistoryState {
	EMPTY,
	SINGLE,
	ALL_GREEN,
	NEVER_GREEN,
	REGRESSION,
	RECOVERED,
	FLAKY,
}

data class Transition(val green: Run, val red: Run)

/**
 * The runs of one [History.key], oldest first. A `Run` has no timestamp, so [RunOrder.GIVEN] trusts
 * the importer; [RunOrder.RUN_ID] is for GitHub, whose run ids only grow (a re-run keeps its id).
 */
data class History(val key: HistoryKey, val runs: List<Run>) {
	private val decisive: List<Run>
		get() = runs.filter { it.outcome == RunOutcome.PASS || it.outcome == RunOutcome.FAIL }

	/** Adjacent decisive runs with different outcomes. */
	val flips: Int
		get() = decisive.zipWithNext().count { (a, b) -> a.outcome != b.outcome }

	val lastGreen: Run?
		get() = decisive.lastOrNull { it.outcome == RunOutcome.PASS }

	val firstRed: Run?
		get() = decisive.firstOrNull { it.outcome == RunOutcome.FAIL }

	/** The first red and the last green before it; null when no green precedes the first red. */
	val transition: Transition?
		get() {
			val runs = decisive
			val at = runs.indexOfFirst { it.outcome == RunOutcome.FAIL }
			return if (at > 0) Transition(runs[at - 1], runs[at]) else null
		}

	val state: HistoryState
		get() {
			val runs = decisive
			val flips = flips
			return when {
				runs.isEmpty() -> HistoryState.EMPTY
				runs.size == 1 -> HistoryState.SINGLE
				flips >= 2 -> HistoryState.FLAKY
				flips == 0 && runs[0].outcome == RunOutcome.PASS -> HistoryState.ALL_GREEN
				flips == 0 -> HistoryState.NEVER_GREEN
				runs[0].outcome == RunOutcome.PASS -> HistoryState.REGRESSION
				else -> HistoryState.RECOVERED
			}
		}

	fun toJson(): JsonObject = obj(
		"workflow" to JsonString(key.workflow),
		"job" to jsonOrNull(key.job),
		"state" to JsonString(state.name.lowercase()),
		"flips" to JsonInt(flips.toLong()),
		"runs" to JsonArray(
			runs.map { obj("id" to JsonString(it.id), "outcome" to JsonString(it.outcome.name)) },
		),
		"lastGreen" to jsonOrNull(lastGreen?.id),
		"firstRed" to jsonOrNull(firstRed?.id),
		"green" to jsonOrNull(transition?.green?.id),
		"red" to jsonOrNull(transition?.red?.id),
	)

	companion object {
		fun of(key: HistoryKey, runs: List<Run>, order: RunOrder = RunOrder.GIVEN): History =
			History(key, if (order == RunOrder.RUN_ID) byRunId(runs) else runs)

		/** One history per key, keys sorted by workflow then job (no job first). */
		fun group(entries: List<HistoryEntry>, order: RunOrder = RunOrder.GIVEN): List<History> =
			entries.groupBy({ it.key }, { it.run }).entries
				.sortedWith(compareBy({ it.key.workflow }, { it.key.job ?: "" }))
				.map { of(it.key, it.value, order) }

		private fun byRunId(runs: List<Run>): List<Run> {
			runs.forEach {
				require(it.id.isNotEmpty() && it.id.all { c -> c in '0'..'9' }) {
					"run id is not decimal: ${it.id}"
				}
			}
			return runs.sortedWith(
				compareBy({ it.id.trimStart('0').length }, { it.id.trimStart('0') }),
			)
		}
	}
}
