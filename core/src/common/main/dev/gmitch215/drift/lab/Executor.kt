package dev.gmitch215.drift.lab

import dev.gmitch215.drift.model.Capsule

/**
 * One finished trial as the executor saw it. [RawTrial.infra] names a problem that is not the
 * test's (a docker error, a timeout); such a trial counts as neither pass nor fail.
 */
data class RawTrial(
	val exit: Int,
	val output: String,
	val seconds: Long,
	val infra: String? = null,
)

/** Runs the arms of an experiment. The loop above it is deterministic given these outcomes. */
interface Executor {
	/** Builds what [arm] needs under the directory [dir]; a problem text, or null when ready. */
	fun prepare(arm: RunConfig, dir: String): String?

	/** Runs trial [index] (from 0) of [arm]. */
	fun trial(arm: RunConfig, index: Int): RawTrial

	/** The capsule captured inside the arm's container, probes included; null if it cannot. */
	fun capture(arm: RunConfig): Capsule?
}

/** What counts as a failing trial: a nonzero exit, or output that matches a pattern. */
sealed interface FailWhen {
	fun failed(trial: RawTrial): Boolean

	fun text(): String

	data object Exit : FailWhen {
		override fun failed(trial: RawTrial) = trial.exit != 0

		override fun text() = "exit"
	}

	data class Matches(val pattern: String) : FailWhen {
		private val regex = Regex(pattern)

		override fun failed(trial: RawTrial) = regex.containsMatchIn(trial.output)

		override fun text() = pattern
	}

	companion object {
		/** `exit`, or a regular expression; a pattern that does not compile is a typed error. */
		fun parse(text: String): FailWhen {
			require(text.isNotEmpty()) { "--fail-when needs exit or a pattern" }
			if (text == "exit") return Exit
			try {
				Regex(text)
			} catch (e: IllegalArgumentException) {
				throw IllegalArgumentException("--fail-when is not a valid pattern: ${e.message}")
			}
			return Matches(text)
		}
	}
}

/**
 * A scripted executor for tests and offline demos. `behave` decides each trial from
 * the arm and the trial index; `capsules` decides the capture (the arm's own capsule
 * by default).
 */
class FakeExecutor(
	private val capsules: (RunConfig) -> Capsule? = { it.capsule },
	private val problem: (RunConfig) -> String? = { null },
	private val behave: (RunConfig, Int) -> RawTrial,
) : Executor {
	val prepared = mutableListOf<String>()
	val ran = mutableListOf<Pair<String, Int>>()

	override fun prepare(arm: RunConfig, dir: String): String? {
		prepared += dir
		return problem(arm)
	}

	override fun trial(arm: RunConfig, index: Int): RawTrial {
		ran += arm.label to index
		return behave(arm, index)
	}

	override fun capture(arm: RunConfig): Capsule? = capsules(arm)
}
