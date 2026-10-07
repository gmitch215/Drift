package dev.gmitch215.drift.bench

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** Exact binomial tails with a permille success rate, in integers. */
object Binomial {
	class Tails(val lower: BigInteger, val upper: BigInteger, val denominator: BigInteger) {
		/** Each one-sided tail at least alpha / 2 with alpha = 0.001. */
		fun accepts(): Boolean {
			val twoThousand = BigInteger.valueOf(2000)
			return lower * twoThousand >= denominator && upper * twoThousand >= denominator
		}

		fun smallest(): String {
			val tail = lower.min(upper)
			return BigDecimal(tail).divide(BigDecimal(denominator), MathContext(4)).toString()
		}
	}

	fun tails(n: Int, ratePermille: Int, k: Int): Tails {
		val a = BigInteger.valueOf(ratePermille.toLong())
		val b = BigInteger.valueOf((1000 - ratePermille).toLong())
		var choose = BigInteger.ONE
		var lower = BigInteger.ZERO
		var upper = BigInteger.ZERO
		for (j in 0..n) {
			val mass = choose * a.pow(j) * b.pow(n - j)
			if (j <= k) lower += mass
			if (j >= k) upper += mass
			choose = choose * BigInteger.valueOf((n - j).toLong())
			choose /= BigInteger.valueOf((j + 1).toLong())
		}
		return Tails(lower, upper, BigInteger.valueOf(1000).pow(n))
	}
}
