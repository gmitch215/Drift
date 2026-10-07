package dev.gmitch215.drift.case

import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.rank.Ranking

/** How much of the structured result to show. The engine and the case files do not know it. */
enum class Detail {
	SUMMARY,
	DETAIL,
	FULL,
	;

	companion object {
		fun parse(text: String): Detail? = entries.firstOrNull { it.name.lowercase() == text }
	}
}

/**
 * Plain text for a ranking and a plan, at three levels from the same data. The inputs are the
 * canonical JSON of `Ranking` and `Plan`, which is what a case directory holds, so a live result
 * and a case read back render the same. Lines stay within [WIDTH] columns.
 */
object Render {
	const val WIDTH = 100

	fun render(ranking: Ranking, plan: Plan, detail: Detail): String =
		render(ranking.toJson(), plan.toJson(), detail)

	fun render(
		ranking: JsonObject,
		plan: JsonObject,
		detail: Detail,
		done: Set<String> = emptySet(),
	): String {
		val out = Out()
		out.text(summary(ranking), 0, 0)
		if (detail == Detail.SUMMARY) return out.finish()
		out.blank()
		candidates(ranking, detail, out)
		out.blank()
		nextInto(plan, detail, out, done)
		if (detail == Detail.FULL) {
			out.blank()
			planFull(plan, out)
		}
		return out.finish()
	}

	fun next(plan: Plan, detail: Detail): String = next(plan.toJson(), detail)

	/** [done] holds experiment ids that already have a recorded result; they are not proposed. */
	fun next(plan: JsonObject, detail: Detail, done: Set<String> = emptySet()): String =
		Out().also { nextInto(plan, detail, it, done) }.finish()

	fun summary(ranking: JsonObject): String {
		val candidates = ranking.list("candidates")
		val top = candidates.firstOrNull()
			?: return "The two captures show no difference that could explain the failure, " +
				"so the data names no cause."
		val path = top.str("path")
		val bundle = (top["bundle"] as? JsonString)?.value
		if (bundle != null) {
			val size = ranking.list("bundles").first { it.str("id") == bundle }
				.require("members").array().size
			return "$size changes differ with the same support, and $path ranks first only by " +
				"hand-set weights, so the data does not name one cause."
		}
		return "$path differs most (tier ${top.str("tier")}); this ranks differences and does " +
			"not confirm a cause."
	}

	private fun candidates(ranking: JsonObject, detail: Detail, out: Out) {
		val full = detail == Detail.FULL
		val weights = ranking.require("weights").obj()
		val label = if ((weights["calibrated"] as? JsonBool)?.value == true) {
			"calibrated"
		} else {
			"uncalibrated"
		}
		out.text(
			"Candidates, best first. Scores come from hand-set weights (${weights.str("id")}, " +
			"$label); they order candidates and are not probabilities.",
		)
		val list = ranking.list("candidates")
		if (list.isEmpty()) out.text("none", 2)
		for ((i, c) in list.withIndex()) {
			val bundle = (c["bundle"] as? JsonString)?.let { "  bundle ${it.value}" }.orEmpty()
			val head = "${i + 1}. ${c.str("path")}  tier ${c.str("tier")}  ${c.str("basis")}"
			out.text(head + bundle, 2)
			val spectrum = c["spectrum"] as? JsonObject
			val kinds = c.list("evidence").filter { it.str("kind") != "spectrum" }
				.map { "${it.str("kind")} ${it.str("ref")}" }
			val seen = if (spectrum == null) {
				"no run history behind it"
			} else {
				"seen in ${spectrum.num("ef")} failing and ${spectrum.num("ep")} passing runs; " +
					"${spectrum.num("nf")} failing runs lack it"
			}
			out.text("evidence: ${kinds.joinToString("; ")}; $seen", 6)
			for (r in c.list("rules")) {
				out.text(
					"rule ${r.str("rule")} (${r.str("title")}), ${r.str("basis")}: " +
					r.str("mechanism"),
						6,
				)
			}
			if (full) {
				for (e in c.list("evidence")) {
					out.text(
						"weight ${FixedPoint.format(e.num("weight"))} ${e.str("id")}: " +
						"${e.str("kind")} ${e.str("ref")}",
							8,
					)
				}
				out.text(
					"score ${FixedPoint.format(c.num("score"))}, coverage " +
					(c["coverage"] as? JsonInt)?.value?.let { FixedPoint.format(it) }.orEmpty()
						.ifEmpty { "none" },
							8,
				)
				val share = (c["probability"] as? JsonInt)?.value
				val fit = (weights["probability"] as? JsonObject)?.get("fit") as? JsonString
				if (share != null && fit != null) {
					out.text(
						"probability ${FixedPoint.format(share)}: its share against the other " +
						"candidates and none, calibrated on the ${fit.value}",
						8,
					)
				}
			}
		}
		for (b in ranking.list("bundles")) {
			val members = b.require("members").array().joinToString(", ") { it.string() }
			out.text(
				"${b.str("id")} holds candidates the data cannot separate (none is the " +
				"cause on its own): $members",
			)
		}
		for (u in ranking.list("unattached")) {
			out.text(
				"rule ${u.str("rule")} matched but touches no changed attribute: " +
				u.str("mechanism"),
			)
		}
		if (full) {
			for (x in ranking.list("excluded")) {
				out.text("run ${x.str("run")} left out of the spectrum: ${x.str("reason")}")
			}
			(ranking["transition"] as? JsonObject)?.let { t ->
				out.text("transition: ${t.str("kind")}")
				for (s in t.list("steps")) {
					out.text("step ${s.str("kind")}: ${s.str("name")}", 2)
				}
			}
		}
	}

	private fun nextInto(plan: JsonObject, detail: Detail, out: Out, done: Set<String>) {
		fun JsonObject.id() = require("experiment").obj().str("id")
		val all = plan.list("experiments")
		val skipped = all.filter { it.id() in done && it.str("decision") != "rejected" }
		val experiments = all.filter { it.id() !in done }
		val next = experiments.firstOrNull { it.str("decision") in setOf("next", "alternative") }
		if (skipped.isNotEmpty()) {
			out.text(
				"Already has a recorded result, so not proposed: " +
					skipped.joinToString(", ") { it.id() } +
					". The order below is the plan made before those results.",
			)
			out.blank()
		}
		if (next == null) {
			out.text(
				if (skipped.isEmpty()) {
					"No experiment can run from here."
				} else {
					"No other experiment can run from here."
				},
			)
			for (s in plan.list("stuck")) out.text("${s.str("code")}: ${s.str("detail")}", 2)
		} else {
			out.text("Next experiment (a proposal, nothing has been run):")
			val promoted = if (next.str("decision") == "alternative") {
				"best information gain per cost among the experiments with no result yet"
			} else {
				null
			}
			experimentInto(next, detail, out, promoted)
		}
		if (detail == Detail.FULL) {
			for (e in experiments) {
				if (e !== next && e.str("decision") != "manual") {
					out.blank()
					out.text("${e.str("decision").replaceFirstChar { it.uppercase() }} experiment:")
					experimentInto(e, detail, out)
				}
			}
		}
		val manual = experiments.filter { it.str("decision") == "manual" }
		if (manual.isNotEmpty()) {
			out.blank()
			out.text("Manual experiments, which Drift cannot run (proposals, not results):")
			for (m in manual) experimentInto(m, Detail.DETAIL, out)
		}
	}

	private fun experimentInto(p: JsonObject, detail: Detail, out: Out, why: String? = null) {
		val e = p.require("experiment").obj()
		val cost = e.require("cost").obj()
		out.text("${e.str("id")}: ${e.require("arms").array()[1].obj().str("label")}", 2)
		out.text(
			"${e.str("class")}; ${cost.num("minutes")} min, ${cost.num("runnerMinutes")} " +
			"runner min, ${cost.num("trials")} trials",
				4,
		)
		out.text("why: ${why ?: p.str("reason")}", 4)
		if (detail == Detail.SUMMARY) return
		out.text(
			"expected information gain ${bits(p.num("gain"))} from the hand-set prior " +
			"(a prediction, not a measurement)",
				4,
		)
		out.text(e.str("instructions"), 4)
		val outcomes = p.list("outcomes")
		out.text(
			"outcomes: " + outcomes.joinToString("; ") {
			"${it.str("outcome")} ${FixedPoint.format(it.num("probability"))}"
		},
			4,
		)
		if (detail == Detail.FULL) {
			out.text(
				"cost points ${FixedPoint.format(p.num("points"))}, information per point " +
				"${p.num("perCost")} micro-bits",
					4,
			)
			for (o in outcomes) {
				val masses = o.require("posterior").obj().list("masses")
					.sortedWith(compareBy({ -it.num("micro") }, { it.str("id") }))
				out.text(
					"if ${o.str("outcome")}: " + masses.joinToString(", ") {
					"${it.str("id")} ${FixedPoint.format(it.num("micro"))}"
				},
					6,
				)
			}
		}
	}

	private fun planFull(plan: JsonObject, out: Out) {
		val weights = plan.require("weights").obj()
		val frame = plan.require("frame").obj()
		val trials = plan.require("trials").obj()
		out.text(
			"Plan inputs: ${frame.str("kind")} frame, passing ${frame.str("passing")}, " +
			"failing ${frame.str("failing")}, arms run in ${frame.str("environment")}.",
		)
		val calibrated = (weights["calibrated"] as? JsonBool)?.value == true
		out.text(
			"Cost and prior weights ${weights.str("id")} are hand-set, " +
			(if (calibrated) "calibrated" else "uncalibrated") + ".",
				2,
		)
		val perArm = trials["perArm"].orNone()
		val threshold = trials["threshold"].orNone()
		out.text(
			"Trials: $perArm per arm, reject at $threshold failures, " +
				"power ${FixedPoint.format(trials.num("power"))}.",
			2,
		)
		val prior = plan.require("prior").obj().list("masses")
			.sortedWith(compareBy({ -it.num("micro") }, { it.str("id") }))
		out.text(
			"Prior: " + prior.joinToString(", ") {
			"${it.str("id")} ${FixedPoint.format(it.num("micro"))}"
		},
			2,
		)
		for (h in plan.list("hypotheses")) {
			val members = h.require("members").array().joinToString(", ") { it.string() }
			out.text("hypothesis ${h.str("id")}: $members", 2)
		}
		for (u in plan.list("unseparated")) {
			val ids = u.require("ids").array().joinToString(", ") { it.string() }
			out.text("not separated by any proposed experiment: $ids (${u.str("reason")})")
		}
		for (x in plan.list("excluded")) {
			out.text("excluded ${x.str("id")}: ${x.str("reason")}")
		}
	}

	private fun bits(micro: Long): String =
		FixedPoint.format((micro + 500) / 1000 * 1000).dropLast(3) + " bits"

	private fun JsonValue?.orNone(): String = when (this) {
		is JsonInt -> value.toString()
		JsonNull, null -> "none"
		else -> "?"
	}

	private fun JsonObject.str(key: String): String = require(key).string()

	private fun JsonObject.num(key: String): Long =
		(require(key) as? JsonInt)?.value ?: throw JsonException("expected integer: $key")

	private fun JsonObject.list(key: String) = require(key).array().map { it.obj() }

	private class Out {
		private val lines = mutableListOf<String>()

		fun blank() {
			lines += ""
		}

		fun text(text: String, indent: Int = 0, hang: Int = HANG) {
			var line = " ".repeat(indent)
			var empty = true
			for (word in text.split(' ', '\n', '\t').filter { it.isNotEmpty() }) {
				if (!empty && line.length + 1 + word.length > WIDTH) {
					lines += line
					line = " ".repeat(indent + hang)
					empty = true
				}
				line += (if (empty) "" else " ") + word
				empty = false
			}
			lines += line
		}

		fun finish() = lines.joinToString("\n") + "\n"

		private companion object {
			const val HANG = 2
		}
	}
}
