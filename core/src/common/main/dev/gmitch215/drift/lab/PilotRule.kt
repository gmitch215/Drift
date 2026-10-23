package dev.gmitch215.drift.lab

/**
 * The stopping rule of the pilot, in integers. The pilot runs [SolveOptions.pilot] trials per arm
 * and then goes on until the failing baseline fails once or the cap is reached; the cap never takes
 * more than a quarter of the trial budget. With no failure in n trials the failure rate is below
 * 3/n at 95% (the rule of three), reported in whole percent rounded up.
 */
object PilotRule {
    fun max(min: Int, maxTrials: Int): Int =
        maxOf(min, minOf(SolveOptions.MAX_PILOT, maxTrials / 4))

    /** Trials per arm of a finished pilot, given the index of the first failing baseline trial. */
    fun trials(min: Int, max: Int, firstFailure: Int?): Int =
        if (firstFailure == null) max else maxOf(min, firstFailure + 1)

    fun upperPercent(trials: Int): Int = minOf(100, (300 + trials - 1) / trials)
}
