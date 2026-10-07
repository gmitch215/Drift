package dev.gmitch215.drift.math

object FixedPoint {
	const val ONE = 1_000_000L

	private const val NANO = 1_000_000_000L
	private const val PICO = 1_000_000_000_000L
	private const val LN2_PICO = 693_147_180_560L
	private const val EXP_TERMS = 24
	private const val LN_TERMS = 20

	fun exp(micro: Long): Long {
		require(micro in -20 * ONE..20 * ONE) { "exp argument out of range" }
		val x = micro * 1_000_000
		val k = floorDiv(x, LN2_PICO)
		val r = x - k * LN2_PICO
		var term = PICO
		var sum = PICO
		for (i in 1..EXP_TERMS) {
			term = mulPico(term, r) / i
			sum += term
		}
		return if (k >= 0) {
			roundDiv(roundDiv(sum, 1000) shl k.toInt(), 1000)
		} else {
			roundDiv(sum shr (-k).toInt(), 1_000_000)
		}
	}

	fun ln(micro: Long): Long {
		require(micro > 0) { "ln argument must be positive" }
		val v = micro * 1000
		var k = 0
		var m = v
		while (m >= 2 * NANO) {
			m = m shr 1
			k++
		}
		while (m < NANO) {
			m = m shl 1
			k--
		}
		val z = (m - NANO) * NANO / (m + NANO)
		val z2 = z * z / NANO
		var power = z
		var sum = 0L
		for (i in 0 until LN_TERMS) {
			sum += power / (2 * i + 1)
			power = power * z2 / NANO
		}
		return roundDiv(2 * sum * 1000 + k * LN2_PICO, 1_000_000)
	}

	fun format(micro: Long): String {
		val negative = micro < 0
		val abs = if (negative) -micro else micro
		val frac = (abs % ONE).toString().padStart(6, '0')
		return (if (negative) "-" else "") + (abs / ONE) + "." + frac
	}

	private fun mulPico(a: Long, b: Long): Long =
	    a * (b / 1_000_000) / 1_000_000 + a * (b % 1_000_000) / PICO

	private fun floorDiv(a: Long, b: Long): Long = a / b - if (a % b < 0) 1 else 0

	private fun roundDiv(a: Long, b: Long) = if (a >= 0) (a + b / 2) / b else -(-a + b / 2) / b
}
