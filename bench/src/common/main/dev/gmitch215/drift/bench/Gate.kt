package dev.gmitch215.drift.bench

/**
 * The per-trial fault gate. `harness.sh` computes the same integer function in shell
 * arithmetic, so the trials that fail are known before docker runs.
 */
object Gate {
	private const val MOD = 4294967296L

	fun fires(trial: Int, salt: Long, gate: Int): Boolean {
		var h = (trial * 2654435761L + salt) % MOD
		h = (((h shr 16) xor h) * 73244475L) % MOD
		h = (((h shr 16) xor h) * 73244475L) % MOD
		h = (h shr 16) xor h
		return h % 1000 < gate
	}

	fun failures(trials: Int, salt: Long, gate: Int): List<Int> =
		(0 until trials).filter { fires(it, salt, gate) }

	/**
	 * True when `count` failures in `trials` stay within 2.5 standard deviations of the
	 * declared permille `rate` (4 d^2 <= 25 n a (1000 - a), exact in integers).
	 */
	fun within(trials: Int, rate: Int, count: Int): Boolean {
		val d = 1000L * count - trials.toLong() * rate
		return 4 * d * d <= 25L * trials * rate * (1000 - rate)
	}
}
