package dev.gmitch215.drift.cli.why

import dev.gmitch215.drift.triage.NotEnvSignal
import dev.gmitch215.drift.triage.NotEnvironment

/** The sentences for a failure that is not an environment difference. Each one names its signal. */
internal object NotEnvironmentWording {
    const val KIND = "not-environment"
    const val ISSUE_LEAD = "This is not an environment difference."
    const val CAPTURE_LEAD = "No environment difference explains this."

    fun verdict(n: NotEnvironment, lead: String, noun: String): String = when (n.signal) {
        NotEnvSignal.FRAME -> "$lead The $noun points at your own code (${n.frame}): start there."

        NotEnvSignal.MISMATCH ->
            "$lead The $noun shows an expected and an actual value that " +
                "differ, so start in the code that computes the actual value."

        NotEnvSignal.REGRESSION ->
            "$lead The $noun calls it a regression, so start with the " +
                "change that introduced it."

        NotEnvSignal.CRASH ->
            "$lead The $noun reports the program crashing with an exception, so start with " +
                "the stack trace."

        NotEnvSignal.LOCATED ->
            "$lead The $noun already points at the place in the project that is wrong: start " +
                "there."

        NotEnvSignal.CLEAN_RUN ->
            "It fails the same way on a clean machine, so your environment is not the cause; " +
                "look at the program or the input."

        else ->
            "$lead The $noun describes the program's own logic or output going wrong, so " +
                "start in the code that does it."
    }

    fun evidence(n: NotEnvironment, noun: String): String = when (n.signal) {
        NotEnvSignal.FRAME -> "The $noun has a stack frame in your code: ${n.frame}."

        NotEnvSignal.MISMATCH -> "The $noun has an expected value and an actual value that differ."

        NotEnvSignal.REGRESSION -> "The $noun uses the word regression."

        NotEnvSignal.CRASH -> "The $noun reports a crash and shows an exception or a stack trace."

        NotEnvSignal.LOCATED -> "The $noun names a typo, a wrong link or a line of the project."

        NotEnvSignal.CLEAN_RUN ->
            "The clean run printed the same failure as the $noun: ${n.text}"

        else -> "The $noun says the program itself misbehaves: ${n.signal.id}."
    }

    fun next(n: NotEnvironment): String = when (n.signal) {
        NotEnvSignal.FRAME -> "Open ${n.frame} and check the values the code gets there."

        NotEnvSignal.MISMATCH -> "Find where the actual value comes from; the fix is in that code."

        NotEnvSignal.REGRESSION ->
            "Find the change that introduced it, for example with git " +
                "bisect between a good and a bad version."

        NotEnvSignal.CRASH ->
            "Read the stack trace from the top and open the first frame in your own code."

        NotEnvSignal.LOCATED ->
            "Open the place the report names and compare it with what it should say."

        NotEnvSignal.CLEAN_RUN ->
            "Start from the input the command reads and the code that handles it; the failure " +
                "does not depend on this machine."

        else -> "Reproduce it with the input the report gives and follow it through the code."
    }
}
