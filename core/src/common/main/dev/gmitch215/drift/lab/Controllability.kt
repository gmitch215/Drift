package dev.gmitch215.drift.lab

import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.plan.Controls
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.InterventionClass

/**
 * Whether one attribute can be flipped, [ControlVerdict.via] the intervention type that does it
 * (null for a manual dimension), and one sentence of why.
 */
data class ControlVerdict(
	val path: String,
	val kind: InterventionClass,
	val reason: String,
	val via: String?,
) {
	val controllable: Boolean get() = kind != InterventionClass.MANUAL

	fun toJson(): JsonObject = obj(
		"path" to JsonString(path),
		"class" to JsonString(kind.id),
		"controllable" to JsonBool(controllable),
		"via" to (via?.let(::JsonString) ?: JsonNull),
		"reason" to JsonString(reason),
	)
}

/**
 * Sorts a candidate attribute into automatic-local, automatic-CI or manual. It starts from the
 * planner's [Controls] table, so a dimension the planner calls manual is never promoted here,
 * then uses the `reproduce` manifest to demote what a container cannot mirror, and treats the
 * members that only travel with a base image as manual because nothing sets them alone.
 */
object Controllability {
	private val TRAVELS = listOf("tool.libc.", "tool.coreutils.", "tool.git.")

	private val MANIFEST_FAMILIES = listOf("env.", "tool.", "cgroup.", "limits.")

	private const val NOT_ALLOWLISTED = "not allowlisted"

	fun classify(
		path: String,
		environment: Environment,
		manifest: Reproduction? = null,
		controls: Controls = Controls.standard,
	): ControlVerdict {
		if (path.startsWith("arm.")) {
			val kind = when (environment) {
				Environment.LOCAL -> InterventionClass.AUTOMATIC_LOCAL
				Environment.CI -> InterventionClass.AUTOMATIC_CI
			}
			val why = "the test command and the workspace are part of the run configuration"
			return ControlVerdict(path, kind, why, Interventions.via(path))
		}
		val base = controls.classify(listOf(path), environment)
		if (base.kind == InterventionClass.MANUAL) {
			return ControlVerdict(path, base.kind, "$path: ${base.reason}", null)
		}
		if (TRAVELS.any { path.startsWith(it) }) {
			val why = "$path comes with the base image or runner; only an image swap changes it, " +
				"and the swap changes the rest of the bundle too"
			return ControlVerdict(path, InterventionClass.MANUAL, why, null)
		}
		val via = Interventions.via(path)
		if (environment == Environment.LOCAL && manifest != null) {
			val entry = manifest.entries.firstOrNull { it.path == path }
			val blocked = entry != null && entry.status == Status.NOT_MIRRORED &&
				MANIFEST_FAMILIES.any { path.startsWith(it) } && entry.detail != NOT_ALLOWLISTED
			if (entry != null && blocked) {
				val why = "$path cannot be flipped in a container: ${entry.detail}"
				return ControlVerdict(path, InterventionClass.MANUAL, why, null)
			}
		}
		return ControlVerdict(path, base.kind, how(path, environment), via)
	}

	/** A hypothesis is controllable only when every member is; the first manual member says why. */
	fun hypothesis(
		members: List<String>,
		environment: Environment,
		manifest: Reproduction? = null,
		controls: Controls = Controls.standard,
	): ControlVerdict {
		val all = members.sorted().map { classify(it, environment, manifest, controls) }
		val first = all.firstOrNull { !it.controllable }
		if (first != null) return first
		val kind = when (environment) {
			Environment.LOCAL -> InterventionClass.AUTOMATIC_LOCAL
			Environment.CI -> InterventionClass.AUTOMATIC_CI
		}
		val reason = all.joinToString("; ") { it.reason }.ifEmpty { "no attribute to flip" }
		return ControlVerdict(members.sorted().firstOrNull().orEmpty(), kind, reason, null)
	}

	private fun how(path: String, environment: Environment): String {
		val ci = environment == Environment.CI
		return when {
			path.startsWith("step:") -> "a workflow variant adds or removes the step"

			path.startsWith("env.") ->
				if (ci) {
					"the workflow sets the variable"
				} else {
					"run.sh passes the variable with --env"
				}

			path == "cgroup.cpu.max" -> "run.sh passes --cpus"

			path == "cgroup.memory.max" -> "run.sh passes --memory"

			path.startsWith("limits.") -> "run.sh passes --ulimit"

			path.startsWith("tool.") ->
				if (ci) "a setup action pins the version" else "the base image tag pins the version"

			path.startsWith("os.release.") -> "the base image tag picks the distribution release"

			path.startsWith("deps.") -> "a pin in the workspace lockfile sets the version"

			else -> "the planner's control table lets ${environment.id} set it"
		}
	}
}
