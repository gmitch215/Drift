package dev.gmitch215.drift.rank

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Dimension

/**
 * A likelihood ratio in micro-units: 2_000_000 says the item makes a candidate twice as likely
 * to be the cause as it was before. [logOdds] is what the combiner adds up.
 */
data class Weight(val id: String, val ratio: Long, val reason: String) {
	val logOdds: Long get() = FixedPoint.ln(ratio)
}

/**
 * Every number the ranker uses to compare candidates, in one place. The evidence weights are
 * hand-set guesses and are NOT calibrated: they order candidates and a score is not a
 * probability. The fit on the DriftBench dev split (`bench/data/calibration/fit.json`) tried
 * fitted dimension priors, a fitted probe and rule ratio and isotonic maps, and none beat these
 * values out of fold, so they stay.
 *
 * What the fit did support is a probability for a ranking whose candidates rest on their
 * attribute change alone ([shares]): each candidate's share of the posterior against its rivals
 * and [NONE], the weight of "no listed candidate is the cause". A ranking with a probe, rule or
 * spectrum item gets no probability. Probes occurred in 13 of the 45 dev scenarios; on those
 * rankings the shares with the hand-set probe ratio scored worse out of fold than saying one in
 * n, and a fitted ratio scored no better. No dev scenario has a rule match or a history, so rule
 * and spectrum weights are untested.
 */
object Weights {
	const val ID = "hand-set-1"
	const val CALIBRATED = false

	/** Weight of "no listed candidate is the cause" against the evidence ratios, fit on [FIT]. */
	const val NONE = 250_000L

	const val FIT = "dev split of 45 synthetic scenarios, seed 20261006"

	/** Fewer included passing runs than this say nothing: one pass makes every change perfect. */
	const val MIN_PASSES = 2

	/** The most runs the Ochiai score takes; a longer history gets no spectrum. */
	const val MAX_RUNS = 3000

	/** A spectrum score below this (half of perfect) never makes a candidate `correlated`. */
	const val MIN_CORRELATED = 500_000L

	private fun dimension(d: Dimension, ratio: Long, reason: String) =
		d to Weight("dimension.${d.id}", ratio, reason)

	val dimensions: Map<Dimension, Weight> = mapOf(
		dimension(Dimension.RUNTIME, 3_000_000, "runtimes change how unchanged code behaves"),
		dimension(Dimension.COMPILER, 3_000_000, "a compiler change alters what code is accepted"),
		dimension(Dimension.BUILD, 2_500_000, "build tools and pins change what is built and run"),
		dimension(Dimension.USERLAND, 2_500_000, "shell tools differ in flags and defaults"),
		dimension(Dimension.OS, 2_000_000, "an OS release moves many libraries at once"),
		dimension(Dimension.KERNEL, 2_000_000, "kernel changes show through syscalls and cgroups"),
		dimension(Dimension.CPU_LIMIT, 2_000_000, "runtimes size thread pools from the CPU quota"),
		dimension(Dimension.MEMORY, 2_000_000, "a memory limit turns growth into kills"),
		dimension(Dimension.CPU, 1_500_000, "architecture matters for native code, rarely more"),
		dimension(Dimension.ENV, 1_500_000, "a variable matters only when something reads it"),
		dimension(Dimension.LOCALE, 1_500_000, "locale and timezone matter to formatting code"),
		dimension(Dimension.LIMITS, 1_500_000, "limits matter only near their threshold"),
		dimension(Dimension.NETWORK, 1_500_000, "proxies and addresses matter to networked code"),
		dimension(Dimension.BROWSER, 1_500_000, "engine differences matter to web code"),
	)

	val probe = Weight(
		"probe.difference",
		6_000_000,
		"a probe that reads this attribute's path behaved differently (see ProbeLinks)",
	)

	val probeDimension = Weight(
		"probe.dimension",
		FixedPoint.ONE,
		"a probe in the same dimension differed but does not read this path; no ratio beat 1",
	)

	val rules: Map<Severity, Weight> = mapOf(
		Severity.HIGH to Weight("rule.high", 24_000_000, "documented mechanism, severe symptom"),
		Severity.MEDIUM to Weight("rule.medium", 12_000_000, "documented mechanism, usual symptom"),
		Severity.LOW to Weight("rule.low", 6_000_000, "documented mechanism, mild symptom"),
	)

	/** Ratio of a perfect score at full coverage; lower scores and coverage scale it toward 1. */
	val spectrum = Weight(
		"spectrum.ochiai",
		4_000_000,
		"a perfect score over few runs is weak evidence, so it is capped low",
	)

	val all: List<Weight> =
		dimensions.values.toList() + probe + probeDimension + rules.values.toList() + spectrum

	/** [spectrum] scaled by an Ochiai [score] and a history [coverage], both in micro-units. */
	fun spectrumRatio(score: Long, coverage: Long): Long {
		val one = FixedPoint.ONE
		return one + (spectrum.ratio - one) * score / one * coverage / one
	}

	/**
	 * Each score's share of the posterior, in micro-units: exp(score) over [none] plus the sum of
	 * exp over every score. The shares of a ranking add up to less than one.
	 */
	fun shares(scores: List<Long>, none: Long = NONE): List<Long> {
		val bound = 10 * FixedPoint.ONE
		val e = scores.map { FixedPoint.exp(it.coerceIn(-bound, bound)) }
		val total = none + e.sum()
		return e.map { (2 * it * FixedPoint.ONE + total) / (2 * total) }
	}

	fun toJson(): JsonObject = obj(
		"id" to JsonString(ID),
		"calibrated" to JsonBool(CALIBRATED),
		"probability" to obj(
			"calibrated" to JsonBool(true),
			"covers" to JsonArray(listOf(JsonString("attribute"))),
			"fit" to JsonString(FIT),
			"none" to JsonInt(NONE),
			"uncovered" to JsonArray(
				listOf(JsonString("probe"), JsonString("rule"), JsonString("spectrum")),
			),
		),
	)
}
