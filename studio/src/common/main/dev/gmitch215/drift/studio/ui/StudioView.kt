package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.diff.AttributeDiff
import dev.gmitch215.drift.studio.StudioState

enum class DetailLevel { SUMMARY, DETAIL, FULL }

data class Stage(val label: String, val count: Int)

data class CandidateRow(val path: String, val text: String)

data class StudioView(
	val funnel: List<Stage>,
	val verdict: String,
	val candidates: List<CandidateRow>,
	val hidden: Int,
	val extras: List<String>,
) {
	val maxCount: Int get() = funnel.maxOfOrNull { it.count } ?: 0

	companion object {
		private const val SUMMARY_ROWS = 3

		fun of(state: StudioState, level: DetailLevel = DetailLevel.DETAIL): StudioView {
			val diffs = state.changed.changes.associateBy { it.path }
			val rows = state.relevant.map { c ->
				val change = diffs[c.path]?.let { change(it) }.orEmpty()
				val text = when (level) {
					DetailLevel.SUMMARY -> c.path

					DetailLevel.DETAIL -> "${c.tier.name.lowercase()}  ${c.path}  $change"

					DetailLevel.FULL -> {
						val refs = c.evidence.joinToString { it.ref }
						"${c.tier.name.lowercase()}  ${c.path}  $change  [$refs]"
					}
				}
				CandidateRow(c.path, text)
			}
			val shown = if (level == DetailLevel.SUMMARY) rows.take(SUMMARY_ROWS) else rows
			return StudioView(
				funnel = listOf(
					Stage("facts", state.facts.attributes + state.facts.probes),
					Stage("changed", state.changed.changes.size),
					Stage("relevant", state.relevant.size),
					Stage("competing", state.competing.size),
				),
				verdict = verdict(state),
				candidates = shown,
				hidden = rows.size - shown.size,
				extras = if (level == DetailLevel.FULL) extras(state) else emptyList(),
			)
		}

		fun drill(state: StudioState, path: String): List<String> {
			val candidate = state.relevant.firstOrNull { it.path == path } ?: return emptyList()
			val diff = state.changed.changes.firstOrNull { it.path == path }
			return buildList {
				add(path)
				add("tier: ${candidate.tier.name.lowercase()}")
				diff?.dimension?.let { add("dimension: ${it.id}") }
				diff?.let { add("change: ${it.kind.name.lowercase()}  ${change(it)}") }
				diff?.order?.let { add("order: ${it.name.lowercase()}") }
				candidate.evidence.forEach { add("evidence: ${it.ref}") }
			}
		}

		private fun verdict(state: StudioState) = when {
			state.relevant.isEmpty() -> "no ranked change between these captures"

			state.competing.size > 1 ->
				"${state.competing.size} candidates share the top tier; the captures differ " +
					"in several ways at once and the ranking does not name one cause"

			else -> "one candidate leads the ranking; it is not a confirmed cause"
		}

		private fun extras(state: StudioState): List<String> {
			val ranked = state.relevant.map { it.path }.toSet()
			val diff = state.changed
			val unranked = diff.changes.filter { it.path !in ranked }
				.map { "not ranked: ${it.path}  ${change(it)}" }
			val volatile = diff.ignored.map { "volatile, ignored: ${it.path}  ${change(it)}" }
			val probes = diff.probes.map { "probe ${it.id}: ${it.status.name.lowercase()}" }
			return unranked + volatile + probes
		}

		private fun side(v: String?) = v ?: "(absent)"

		private fun change(d: AttributeDiff) = "${side(d.before)} -> ${side(d.after)}"
	}
}
