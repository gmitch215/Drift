package dev.gmitch215.drift.plan

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj

enum class Environment(val id: String) { LOCAL("local"), CI("ci") }

/** How an experiment runs, by who controls the manipulated attributes. */
enum class InterventionClass(val id: String) {
	AUTOMATIC_LOCAL("automatic-local"),
	AUTOMATIC_CI("automatic-ci"),
	MANUAL("manual"),
}

/** Where an attribute whose path starts with [ControlRow.prefix] can be set, and why. */
data class ControlRow(val prefix: String, val environments: Set<Environment>, val reason: String)

data class Control(val kind: InterventionClass, val reason: String)

/**
 * The attributes Drift can set itself, per environment, by longest path prefix. A path no row
 * claims is never assumed controllable. The standard table is hand-set from the plan's
 * intervention classes.
 */
class Controls(private val rows: List<ControlRow>) {
	private fun row(member: String): ControlRow? =
		rows.filter { member.startsWith(it.prefix) }.maxByOrNull { it.prefix.length }

	/** Automatic in [environment] when every one of [members] can be set there, else manual. */
	fun classify(members: List<String>, environment: Environment): Control {
		for (member in members.sorted()) {
			val row = row(member)
			if (row == null) {
				return Control(InterventionClass.MANUAL, "$member has no known control")
			}
			if (environment !in row.environments) {
				val reason = "$member cannot be set in ${environment.id}: ${row.reason}"
				return Control(InterventionClass.MANUAL, reason)
			}
		}
		val kind = when (environment) {
			Environment.LOCAL -> InterventionClass.AUTOMATIC_LOCAL
			Environment.CI -> InterventionClass.AUTOMATIC_CI
		}
		return Control(kind, "every attribute can be set in ${environment.id}")
	}

	companion object {
		private val both = setOf(Environment.LOCAL, Environment.CI)
		private val local = setOf(Environment.LOCAL)
		private val ci = setOf(Environment.CI)

		val standard = Controls(
			listOf(
				ControlRow("ci.", emptySet(), "the runner provider owns the image and provisioner"),
				ControlRow("step:", ci, "a workflow variant can add, drop or move a step"),
				ControlRow("deps.", both, "a lockfile pin sets a dependency version"),
				ControlRow("tool.", both, "an image tag or a setup action pins a toolchain"),
				ControlRow("tool.libc.", local, "libc ships in the base image; hosted ones fix it"),
				ControlRow("runtime.", both, "a runtime flag or version pin is in the command"),
				ControlRow("env.", both, "a variable is part of the command"),
				ControlRow("browser.", both, "a browser version is pinned like a tool"),
				ControlRow("limits.", local, "ulimits are container flags; hosted ones fix them"),
				ControlRow("cgroup.", local, "cpu and memory limits are container flags"),
				ControlRow("memory.", local, "a memory ceiling is a container flag"),
				ControlRow("network.", local, "container networking is a container flag"),
				ControlRow("os.release.", local, "the userland comes with the base image"),
				ControlRow("os.", emptySet(), "the host operating system is the host's"),
				ControlRow("kernel.", emptySet(), "the kernel belongs to the host"),
				ControlRow("cpu.", emptySet(), "the processor belongs to the host"),
			),
		)
	}
}

/**
 * Minutes of waiting, runner minutes billed and trials run; whole units. The weights that turn
 * these into one cost are in [PlanWeights].
 */
data class Cost(val minutes: Long, val runnerMinutes: Long, val trials: Long) {
	fun toJson(): JsonObject = obj(
		"minutes" to JsonInt(minutes),
		"runnerMinutes" to JsonInt(runnerMinutes),
		"trials" to JsonInt(trials),
	)
}

data class Arm(val label: String, val flips: List<String>) {
	fun toJson(): JsonObject = obj(
		"label" to JsonString(label),
		"flips" to JsonArray(flips.map(::JsonString)),
	)
}

/**
 * Treatment arm: the passing side with [Experiment.flips] (hypothesis ids) set to the failing
 * side's values; control arm: the passing side unchanged. [Experiment.table] holds what each
 * hypothesis predicts.
 */
data class Experiment(
	val id: String,
	val kind: InterventionClass,
	val flips: List<String>,
	val dimensions: List<String>,
	val cost: Cost,
	val table: OutcomeTable,
	val arms: List<Arm>,
	val instructions: String,
) {
	val controllable: Boolean get() = kind != InterventionClass.MANUAL

	fun toJson(ids: List<String>): JsonObject = obj(
		"id" to JsonString(id),
		"class" to JsonString(kind.id),
		"controllable" to JsonBool(controllable),
		"flips" to JsonArray(flips.map(::JsonString)),
		"dimensions" to JsonArray(dimensions.map(::JsonString)),
		"cost" to cost.toJson(),
		"arms" to JsonArray(arms.map { it.toJson() }),
		"separates" to JsonArray(
			table.groups(ids).map { g -> JsonArray(g.map(::JsonString)) },
		),
		"predictions" to table.toJson(ids),
		"instructions" to JsonString(instructions),
	)
}
