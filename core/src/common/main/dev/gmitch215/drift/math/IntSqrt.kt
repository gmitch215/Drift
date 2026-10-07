package dev.gmitch215.drift.math

object IntSqrt {
	/** Largest `r` with `r * r <= n`, exact for every non-negative Long (the result is floored). */
	fun floor(n: Long): Long {
		require(n >= 0) { "square root of a negative number" }
		var rest = n
		var root = 0L
		var bit = 1L shl 62
		while (bit > rest) bit = bit shr 2
		while (bit != 0L) {
			if (rest >= root + bit) {
				rest -= root + bit
				root = (root shr 1) + bit
			} else {
				root = root shr 1
			}
			bit = bit shr 2
		}
		return root
	}
}
