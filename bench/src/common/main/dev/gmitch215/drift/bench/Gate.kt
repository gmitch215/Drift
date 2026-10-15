package dev.gmitch215.drift.bench

import dev.gmitch215.drift.lab.TrialGate

/**
 * The per-trial fault gate. `harness.sh` and `harness.ps1` compute the same integer function
 * (`--hash` and `-Hash` print it), so the trials that fail are known before anything runs.
 */
object Gate {
    fun hash(trial: Int, salt: Long): Long = TrialGate.hash(trial, salt)

    fun fires(trial: Int, salt: Long, gate: Int): Boolean = hash(trial, salt) % 1000 < gate

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
