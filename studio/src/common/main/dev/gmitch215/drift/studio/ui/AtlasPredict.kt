package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.scan.atlas.ColumnKey
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.ProbeEntry

const val DONT_KNOW = -1

data class Score(val correct: Int = 0, val wrong: Int = 0, val skipped: Int = 0)

/** Session state; [PredictState.guess] is a choice index or [DONT_KNOW], null until answered. */
data class PredictState(
	val dataset: Dataset,
	val version: String = dataset.versions.first(),
	val seed: Int = 1,
	val position: Int = 0,
	val guess: Int? = null,
	val score: Score = Score(),
) {
	fun guess(choice: Int): PredictState {
		val view = PredictView.of(this) ?: return this
		if (guess != null || choice !in DONT_KNOW until view.choices.size) return this
		val next = when {
			choice == DONT_KNOW -> score.copy(skipped = score.skipped + 1)
			view.target in view.choices[choice].targets -> score.copy(correct = score.correct + 1)
			else -> score.copy(wrong = score.wrong + 1)
		}
		return copy(guess = choice, score = next)
	}

	fun next(): PredictState = copy(position = position + 1, guess = null)

	fun withVersion(version: String): PredictState =
		copy(version = version, position = 0, guess = null)
}

data class ChoiceView(val label: String, val targets: List<String>, val lines: List<String>)

data class RevealView(
	val verdict: String,
	val results: List<String>,
	val badge: String,
	val notes: List<String>,
)

data class PredictView(
	val probeId: String,
	val index: Int,
	val count: Int,
	val question: String,
	val source: List<String>,
	val target: String,
	val choices: List<ChoiceView>,
	val reveal: RevealView?,
	val score: Score,
) {
	val prompt: String get() = "What does $target print?"

	fun toJson(): JsonObject = obj(
		"probe" to JsonString(probeId),
		"index" to JsonInt(index.toLong()),
		"count" to JsonInt(count.toLong()),
		"question" to JsonString(question),
		"target" to JsonString(target),
		"choices" to JsonArray(
			choices.map {
				obj(
					"label" to JsonString(it.label),
					"targets" to JsonArray(it.targets.map { t -> JsonString(t) }),
					"lines" to JsonArray(it.lines.map { l -> JsonString(l) }),
				)
			},
		),
		"reveal" to JsonString(reveal?.verdict.orEmpty()),
		"score" to obj(
			"correct" to JsonInt(score.correct.toLong()),
			"wrong" to JsonInt(score.wrong.toLong()),
			"skipped" to JsonInt(score.skipped.toLong()),
		),
	)

	fun encode(): String = CanonicalJson.encode(toJson())

	companion object {
		private const val MAX_LINES = 4

		/** Divergent probes of one version in an order fixed by the seed and the probe id only. */
		fun order(dataset: Dataset, version: String, seed: Int): List<ProbeEntry> =
			dataset.probes.filter { version in it.divergentVersions }
				.sortedBy { Sha256.hex("$seed:${it.id}") }

		fun of(state: PredictState): PredictView? {
			val order = order(state.dataset, state.version, state.seed)
			if (order.isEmpty()) return null
			val index = state.position % order.size
			val p = order[index]
			val v = state.version
			val targets = state.dataset.columns.filter { it.version == v }.map { it.target }
			val target = targets[state.position % targets.size]
			val groups = p.groups(v).map { g -> g to p.cell(ColumnKey(g.first(), v))!!.lines }
				.sortedBy { it.second.joinToString("\n") }
			val shown = differing(groups.map { it.second })
			val choices = groups.mapIndexed { i, (g, lines) ->
				val text = shown.take(MAX_LINES).map {
					"L${it + 1} ${AtlasView.ascii(lines.getOrNull(it) ?: "(no line)")}"
				}
				val more = shown.size - MAX_LINES
				ChoiceView(
					('A' + i).toString(),
					g.sorted(),
					if (more > 0) text + "(+$more more differing lines)" else text,
				)
			}
			val guess = state.guess
			return PredictView(
				probeId = p.id,
				index = index,
				count = order.size,
				question = p.question,
				source = p.source.lines(),
				target = target,
				choices = choices,
				reveal = guess?.let { reveal(p, choices, targets, it, target) },
				score = state.score,
			)
		}

		private fun reveal(
			p: ProbeEntry,
			choices: List<ChoiceView>,
			targets: List<String>,
			guess: Int,
			target: String,
		): RevealView {
			val verdict = when {
				guess == DONT_KNOW -> "skipped"
				target in choices[guess].targets -> "correct"
				else -> "wrong"
			}
			return RevealView(
				verdict = verdict,
				results = targets.map { t -> "$t: ${choices.first { t in it.targets }.label}" },
				badge = AtlasView.badge(p),
				notes = AtlasView.cited(p),
			)
		}

		private fun differing(variants: List<List<String>>): List<Int> {
			val size = variants.maxOfOrNull { it.size } ?: 0
			return (0 until size).filter { i ->
				variants.map { it.getOrNull(i) }.distinct().size > 1
			}
		}
	}
}
