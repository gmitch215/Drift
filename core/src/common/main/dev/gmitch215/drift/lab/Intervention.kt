package dev.gmitch215.drift.lab

import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.plan.Environment

sealed interface InterventionProblem {
	fun message(): String

	data class Invalid(val intervention: String, val why: String) : InterventionProblem {
		override fun message() = "$intervention is invalid: $why"
	}

	data class NotApplicable(
		val intervention: String,
		val environment: Environment,
		val why: String,
	) : InterventionProblem {
		override fun message() = "$intervention cannot run in ${environment.id}: $why"
	}

	data class NoEffect(val intervention: String, val why: String) : InterventionProblem {
		override fun message() = "$intervention changes nothing: $why"
	}

	data class NotMirrored(val intervention: String, val path: String, val detail: String) :
		InterventionProblem {
		override fun message() = "$intervention does not reach the container: $path is not " +
			"mirrored ($detail)"
	}
}

/**
 * The editable form of one run configuration while interventions are applied: the capsule's
 * attributes by path (the first of a duplicated path wins, as in `reproduce`), the env names to
 * mirror, the test command, and what has no capsule path (workspace files, workflow steps).
 */
internal class Draft(
	private val capsule: Capsule,
	var command: String,
	names: Set<String> = emptySet(),
) {
	val attrs = LinkedHashMap<String, Attribute>()
	val envNames = LinkedHashSet<String>()
	val extras = LinkedHashMap<String, String>()
	val files = LinkedHashMap<String, String?>()
	var touched = mutableListOf<String>()

	init {
		for (a in capsule.attributes) attrs.getOrPut(a.path) { a }
		envNames += names
	}

	fun value(path: String): String? = attrs[path]?.value

	fun set(path: String, value: String) {
		val old = attrs[path]
		if (old?.value == value) return
		attrs[path] = Attribute(path, value, "intervention", old?.stability ?: Stability.STATIC)
		touched += path
	}

	fun unset(path: String) {
		if (attrs.remove(path) != null) touched += path
		envNames -= path.removePrefix("env.")
	}

	fun extra(path: String, value: String) {
		if (extras.put(path, value) != value) touched += path
	}

	fun capsule(): Capsule = Capsule(capsule.label, attrs.values.toList(), capsule.probes)
}

/**
 * A typed change to one run configuration. Applying it edits the capsule the container is
 * synthesized from, so the Dockerfile, the run.sh flags and the manifest all follow from the same
 * code as `reproduce`. The attributes it changes are recorded by the edit, not declared by hand.
 * Every value is the capsule's own spelling (`120000 100000` for a cpu limit).
 */
sealed class Intervention {
	abstract val type: String
	abstract val params: List<Pair<String, String>>

	abstract fun describe(): String

	internal abstract fun edit(d: Draft): InterventionProblem?

	/** Whether the Dockerfile or run.sh of a container must differ once this is applied. */
	internal open val observable: Boolean get() = true

	/** Why this cannot run in [environment], or null. */
	open fun unsupported(environment: Environment): String? = null

	val id: String get() = "$type:" + params.joinToString(",") { "${it.first}=${it.second}" }

	open fun toJson(): JsonObject = obj(
		"type" to JsonString(type),
		"id" to JsonString(id),
		"description" to JsonString(describe()),
		"params" to JsonObject(params.associate { it.first to JsonString(it.second) }),
	)

	protected fun invalid(why: String) = InterventionProblem.Invalid(id, why)

	data class SetEnv(val name: String, val value: String) : Intervention() {
		override val type get() = "set-env"
		override val params get() = listOf("name" to name, "value" to value)

		override fun describe() = "set environment variable $name to $value"

		override fun edit(d: Draft): InterventionProblem? {
			if (!ENV_NAME.matches(name)) return invalid("not a valid shell variable name")
			d.envNames += name
			d.set("env.$name", value)
			return null
		}
	}

	data class UnsetEnv(val name: String) : Intervention() {
		override val type get() = "unset-env"
		override val params get() = listOf("name" to name)

		override fun describe() = "unset environment variable $name"

		override fun edit(d: Draft): InterventionProblem? {
			if (!ENV_NAME.matches(name)) return invalid("not a valid shell variable name")
			d.unset("env.$name")
			return null
		}
	}

	data class SetLocale(val name: String, val value: String) : Intervention() {
		override val type get() = "set-locale"
		override val params get() = listOf("name" to name, "value" to value)

		override fun describe() = "set locale variable $name to $value"

		override fun edit(d: Draft): InterventionProblem? {
			if (name != "LANG" && !name.startsWith("LC_")) return invalid("not a locale variable")
			if (!ENV_NAME.matches(name)) return invalid("not a valid shell variable name")
			d.envNames += name
			d.set("env.$name", value)
			return null
		}
	}

	data class SetTimezone(val zone: String) : Intervention() {
		override val type get() = "set-timezone"
		override val params get() = listOf("zone" to zone)

		override fun describe() = "set the time zone to $zone"

		override fun edit(d: Draft): InterventionProblem? {
			if (zone.isBlank() || zone.any { it.isWhitespace() }) return invalid("not a zone name")
			d.envNames += "TZ"
			d.set("env.TZ", zone)
			return null
		}
	}

	data class SetCpuLimit(val value: String) : Intervention() {
		override val type get() = "set-cpu-limit"
		override val params get() = listOf("value" to value)

		override fun describe() = "set the cpu limit (cgroup cpu.max) to $value"

		override fun edit(d: Draft): InterventionProblem? {
			if (!CPU_VALUE.matches(value)) return invalid("not max or a quota and a period")
			d.set("cgroup.cpu.max", value)
			return null
		}

		companion object {
			/** A limit of [millis] thousandths of a CPU over the default 100 ms period. */
			fun ofMillis(millis: Long): SetCpuLimit {
				require(millis > 0) { "a cpu limit is positive" }
				return SetCpuLimit("${millis * 100} 100000")
			}
		}
	}

	data class SetMemoryLimit(val value: String) : Intervention() {
		override val type get() = "set-memory-limit"
		override val params get() = listOf("value" to value)

		override fun describe() = "set the memory limit (cgroup memory.max) to $value"

		override fun edit(d: Draft): InterventionProblem? {
			if (value != "max" && !DIGITS.matches(value)) return invalid("not max or a byte count")
			d.set("cgroup.memory.max", value)
			return null
		}

		companion object {
			fun ofBytes(bytes: Long): SetMemoryLimit {
				require(bytes > 0) { "a memory limit is positive" }
				return SetMemoryLimit(bytes.toString())
			}
		}
	}

	data class SetUlimit(val name: String, val value: String) : Intervention() {
		override val type get() = "set-ulimit"
		override val params get() = listOf("name" to name, "value" to value)

		override fun describe() = "set ulimit $name to $value"

		override fun edit(d: Draft): InterventionProblem? {
			if (!LIMIT_NAME.matches(name)) return invalid("not a limit name")
			if (value.isBlank()) return invalid("empty value")
			d.set("limits.$name", value)
			return null
		}
	}

	/** Changes a toolchain version, which in a container is the base image tag. */
	data class SetRuntime(val tool: String, val version: String) : Intervention() {
		override val type get() = "set-runtime"
		override val params get() = listOf("tool" to tool, "version" to version)

		override fun describe() = "set the $tool version to $version (image tag or setup action)"

		override fun edit(d: Draft): InterventionProblem? {
			if (!TOOL_NAME.matches(tool)) return invalid("not a tool name")
			if (version.isBlank()) return invalid("empty version")
			d.set("tool.$tool.version", version)
			return null
		}
	}

	/** Changes the distribution release, which in a container is the base image tag. */
	data class SetOs(val distro: String, val release: String) : Intervention() {
		override val type get() = "set-os"
		override val params get() = listOf("distro" to distro, "release" to release)

		override fun describe() = "set the operating system to $distro $release (base image)"

		override fun edit(d: Draft): InterventionProblem? {
			if (distro.isBlank() || release.isBlank()) {
				return invalid("empty distribution or release")
			}
			d.set("os.release.ID", distro)
			d.set("os.release.VERSION_ID", release)
			return null
		}
	}

	/** Adds or removes one `-x` or `--x` token of the test command. */
	data class SetFlag(val flag: String, val present: Boolean) : Intervention() {
		override val type get() = "set-flag"
		override val params get() = listOf("flag" to flag, "present" to present.toString())

		override fun describe() =
			if (present) "add the flag $flag to the test command" else "remove the flag $flag"

		override fun edit(d: Draft): InterventionProblem? {
			if (!FLAG.matches(flag)) return invalid("not a flag")
			val tokens = d.command.split(' ').filter { it.isNotEmpty() }
			if ((flag in tokens) == present) return null
			d.command = if (present) {
				d.command.trimEnd() + " $flag"
			} else {
				tokens.filter { it != flag }.joinToString(" ")
			}
			if (d.command.isBlank()) return invalid("the command would be empty")
			d.touched += "arm.flag:$flag"
			return null
		}
	}

	/**
	 * Adds or replaces a workspace file ([Intervention.SetFile.content] set), or deletes it (null).
	 */
	data class SetFile(val path: String, val content: String?) : Intervention() {
		override val type get() = "set-file"
		override val params get() = listOf(
			"path" to path,
			"sha256" to (content?.let { Sha256.hex(it) } ?: "deleted"),
		)

		override val observable get() = false

		override fun describe() = if (content == null) {
			"delete the workspace file $path"
		} else {
			"add or replace the workspace file $path"
		}

		override fun toJson(): JsonObject {
			val base = super.toJson()
			val content = content ?: return base
			return JsonObject(base.fields + ("content" to JsonString(content)))
		}

		override fun edit(d: Draft): InterventionProblem? {
			val parts = path.split('/')
			if (path.isBlank() || path.startsWith("/") || ".." in parts || "" in parts) {
				return invalid("not a relative path inside the workspace")
			}
			d.files[path] = content
			d.extra("arm.file:$path", content?.let { "sha256:" + Sha256.hex(it) } ?: "deleted")
			return null
		}
	}

	/** Adds or removes a workflow step; a container has no steps. */
	data class ToggleStep(val step: String, val present: Boolean) : Intervention() {
		override val type get() = "toggle-step"
		override val params get() = listOf("step" to step, "present" to present.toString())

		override val observable get() = false

		override fun describe() =
			if (present) "add the workflow step $step" else "remove the workflow step $step"

		override fun unsupported(environment: Environment) =
			if (environment == Environment.LOCAL) "a step exists only in a workflow" else null

		override fun edit(d: Draft): InterventionProblem? {
			if (step.isBlank()) return invalid("empty step name")
			d.extra("step:$step", if (present) "present" else "absent")
			return null
		}
	}

	/** Pins one dependency in the workspace lockfile; the container installs from it. */
	data class PinDependency(val name: String, val version: String) : Intervention() {
		override val type get() = "pin-dependency"
		override val params get() = listOf("name" to name, "version" to version)

		override val observable get() = false

		override fun describe() = "pin dependency $name to $version in the lockfile"

		override fun edit(d: Draft): InterventionProblem? {
			if (name.isBlank() || version.isBlank()) return invalid("empty name or version")
			d.set("deps.$name.version", version)
			return null
		}
	}

	companion object {
		private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
		private val TOOL_NAME = Regex("^[a-z][a-z0-9-]*$")
		private val LIMIT_NAME = Regex("^[a-z]+$")
		private val DIGITS = Regex("^[0-9]+$")
		private val CPU_VALUE = Regex("^(max|[0-9]+)( [0-9]+)?$")
		private val FLAG = Regex("^--?[A-Za-z0-9][A-Za-z0-9._=:/-]*$")
	}
}

/** What the interventions say about a capsule path, shared by controllability and the arms. */
object Interventions {
	private val toolVersion = Regex("^tool\\.([a-z][a-z0-9-]*)\\.version$")
	private val depVersion = Regex("^deps\\.(.+)\\.version$")

	sealed interface Toward {
		data class Sets(val intervention: Intervention) : Toward

		data class Cannot(val reason: String) : Toward
	}

	/** The intervention type that flips [path], or null when none does. */
	fun via(path: String): String? = when {
		path == "env.TZ" -> "set-timezone"
		path == "env.LANG" || path.startsWith("env.LC_") -> "set-locale"
		path.startsWith("env.") -> "set-env"
		path == "cgroup.cpu.max" -> "set-cpu-limit"
		path == "cgroup.memory.max" -> "set-memory-limit"
		path.startsWith("limits.") -> "set-ulimit"
		toolVersion.matches(path) -> "set-runtime"
		path.startsWith("os.release.") || path == "os.version" -> "set-os"
		depVersion.matches(path) -> "pin-dependency"
		path.startsWith("step:") -> "toggle-step"
		path.startsWith("arm.flag:") -> "set-flag"
		path.startsWith("arm.file:") -> "set-file"
		else -> null
	}

	/**
	 * The intervention that moves the attribute at [path] from [from] to [to] (null is absent).
	 * `os.*` paths are answered by [Arms] as one change, so they are not handled here.
	 */
	fun toward(path: String, from: String?, to: String?): Toward {
		val via = via(path)
		fun no(why: String) = Toward.Cannot(why)
		fun sets(i: Intervention) = Toward.Sets(i)
		if (via == null) return no("no typed intervention changes $path")
		if (via == "toggle-step") {
			return sets(Intervention.ToggleStep(path.removePrefix("step:"), true))
		}
		if (to == null) {
			return if (path.startsWith("env.")) {
				sets(Intervention.UnsetEnv(path.removePrefix("env.")))
			} else {
				no("the failing side has no value for $path, so there is nothing to set")
			}
		}
		return when (via) {
			"set-timezone" -> sets(Intervention.SetTimezone(to))

			"set-locale" -> sets(Intervention.SetLocale(path.removePrefix("env."), to))

			"set-env" -> sets(Intervention.SetEnv(path.removePrefix("env."), to))

			"set-cpu-limit" -> sets(Intervention.SetCpuLimit(to))

			"set-memory-limit" -> sets(Intervention.SetMemoryLimit(to))

			"set-ulimit" -> sets(Intervention.SetUlimit(path.removePrefix("limits."), to))

			"set-runtime" ->
				sets(Intervention.SetRuntime(toolVersion.matchEntire(path)!!.groupValues[1], to))

			"pin-dependency" ->
				sets(Intervention.PinDependency(depVersion.matchEntire(path)!!.groupValues[1], to))

			else -> no("$path is set by one operating system change, not on its own")
		}
	}
}
