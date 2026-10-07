package dev.gmitch215.drift

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.fixtures.LabFixtures
import dev.gmitch215.drift.lab.Budget
import dev.gmitch215.drift.lab.FailWhen
import dev.gmitch215.drift.lab.FakeExecutor
import dev.gmitch215.drift.lab.RawTrial
import dev.gmitch215.drift.lab.RunConfig
import dev.gmitch215.drift.lab.SolveOptions
import dev.gmitch215.drift.lab.Solver
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule

/** Solved cases of every verdict class, produced by the loop against a fake executor. */
object LabCases {
	private val green = Capsule.parse(LabFixtures.text("node.json"))

	private fun with(c: Capsule, label: String, vararg set: Pair<String, String>): Capsule {
		val map = set.toMap()
		val kept = c.attributes.filter { it.path !in map }
		val added = map.map { (k, v) -> Attribute(k, v, "test") }
		return Capsule(label, (kept + added).sortedBy { it.path }, c.probes)
	}

	private fun red() = with(
		green,
		"lab-node-red",
		"env.TZ" to "America/New_York",
		"env.LANG" to "en_US.UTF-8",
		"cgroup.memory.max" to "268435456",
		"limits.nofile" to "1024:1024",
	)

	private fun has(arm: RunConfig, path: String, value: String) =
		arm.capsule.attributes.any { it.path == path && it.value == value }

	private fun trial(fail: Boolean) =
		RawTrial(if (fail) 1 else 0, if (fail) "boom: wrong day" else "ok", 2)

	private val options = SolveOptions(
		command = "sh run-test.sh",
		failWhen = FailWhen.parse("wrong day"),
		budget = Budget(400, 60),
		name = "fake",
		pilot = 6,
	)

	private fun solve(red: Capsule, executor: FakeExecutor): CaseFile =
		Solver(executor, MemoryCaseFiles(), "case", options).solve(green, red).case

	val confirmed: CaseFile by lazy {
		solve(
			red(),
			FakeExecutor(
				behave = { arm, _ -> trial(has(arm, "env.TZ", "America/New_York")) },
			),
		)
	}

	val bundle: CaseFile by lazy {
		val cause = { arm: RunConfig -> has(arm, "tool.node.version", "24.1.0") }
		val capture = { arm: RunConfig ->
			if (cause(arm)) {
				with(
					arm.capsule,
					arm.label,
					"os.release.PRETTY_NAME" to "Debian GNU/Linux 13 (trixie)",
					"tool.libc.version" to "2.41",
				)
			} else {
				arm.capsule
			}
		}
		solve(
			with(green, "lab-node-red", "tool.node.version" to "24.1.0"),
			FakeExecutor(capsules = capture, behave = { arm, _ -> trial(cause(arm)) }),
		)
	}

	val narrowed: CaseFile by lazy {
		solve(
			red(),
			FakeExecutor(
				behave = { arm, _ ->
					trial(
						has(arm, "env.TZ", "America/New_York") || arm.interventions.isNotEmpty(),
					)
				},
			),
		)
	}

	val stuckAfterPilot: CaseFile by lazy {
		solve(red(), FakeExecutor(behave = { _, _ -> trial(true) }))
	}

	val stuckRefuted: CaseFile by lazy {
		solve(red(), FakeExecutor(behave = { arm, _ -> trial(arm.label == "failing baseline") }))
	}

	val all: Map<String, CaseFile> by lazy {
		mapOf(
			"confirmed" to confirmed,
			"bundle" to bundle,
			"narrowed" to narrowed,
			"stuck at the pilot" to stuckAfterPilot,
			"stuck with refuted candidates" to stuckRefuted,
		)
	}
}
