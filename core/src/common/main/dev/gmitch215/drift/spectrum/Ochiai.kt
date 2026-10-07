package dev.gmitch215.drift.spectrum

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.math.IntSqrt

/** An attribute path with one value, the unit the spectrum scores. */
data class Element(val path: String, val value: String)

/** One CI run: whether it failed and the elements its capsule contained. */
data class Run(val failing: Boolean, val elements: Set<Element>)

data class Scored(val element: Element, val ef: Int, val nf: Int, val ep: Int, val score: Long) {
	fun toJson(): JsonObject = obj(
		"path" to JsonString(element.path),
		"value" to JsonString(element.value),
		"ef" to JsonInt(ef.toLong()),
		"nf" to JsonInt(nf.toLong()),
		"ep" to JsonInt(ep.toLong()),
		"score" to JsonInt(score),
	)
}

object Ochiai {
	private const val MAX_RUNS = 3000

	/**
	 * `ef / sqrt((ef + nf) * (ef + ep))` in micro-units, floored; 0 when the denominator is 0.
	 * Computed as `isqrt(ef^2 * 10^12 / d)`, which equals `floor(ef * 10^6 / sqrt(d))` because
	 * the floor of a square root of a floored quotient is the floor of the exact root.
	 */
	fun score(ef: Int, nf: Int, ep: Int): Long {
		require(ef >= 0 && nf >= 0 && ep >= 0) { "counts must not be negative" }
		require(ef + nf + ep <= MAX_RUNS) { "at most $MAX_RUNS runs" }
		val d = (ef + nf).toLong() * (ef + ep)
		if (d == 0L) return 0
		val one = FixedPoint.ONE
		return IntSqrt.floor(ef.toLong() * ef * one * one / d)
	}

	/** Every element seen in any run, highest score first, ties by path then value. */
	fun rank(runs: List<Run>): List<Scored> {
		val failing = runs.count { it.failing }
		val ef = HashMap<Element, Int>()
		val ep = HashMap<Element, Int>()
		for (run in runs) {
			for (e in run.elements) {
				ef.getOrPut(e) { 0 }
				ep.getOrPut(e) { 0 }
			}
			val counts = if (run.failing) ef else ep
			for (e in run.elements) counts[e] = counts.getValue(e) + 1
		}
		return ef.keys.map {
			val f = ef.getValue(it)
			val p = ep.getValue(it)
			Scored(it, f, failing - f, p, score(f, failing - f, p))
		}.sortedWith(
			compareByDescending<Scored> { it.score }
				.thenBy { it.element.path }
				.thenBy { it.element.value },
		)
	}
}
