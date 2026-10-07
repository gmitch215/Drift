package dev.gmitch215.drift.lab

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Stability
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.Experiment
import dev.gmitch215.drift.plan.Hypothesis
import dev.gmitch215.drift.plan.InterventionClass

/**
 * The configuration both arms start from: a capsule, the failing command, where it runs, and
 * the env names run.sh mirrors beyond the allowlist (so a variable under test is in the control).
 */
data class ArmBase(
	val capsule: Capsule,
	val command: String,
	val environment: Environment,
	val digests: Map<String, String> = emptyMap(),
	val envNames: Set<String> = emptySet(),
)

/**
 * One arm as a full run configuration. [RunConfig.reproduction] holds the Dockerfile, run.sh and
 * manifest of a local arm; a CI arm has none and is carried out as a workflow variant from [edits].
 * [RunConfig.edited] lists, per intervention id, the paths it changed; [RunConfig.extras] and
 * [RunConfig.files] hold what has no capsule path (workspace files, workflow steps). Nothing here
 * runs docker.
 */
class RunConfig internal constructor(
	val label: String,
	val environment: Environment,
	val capsule: Capsule,
	val command: String,
	val interventions: List<Intervention>,
	val edited: Map<String, List<String>>,
	val extras: Map<String, String>,
	val files: Map<String, String?>,
	val reproduction: Reproduction?,
) {
	val edits: List<String> get() = interventions.map { it.describe() }
}

sealed interface ConfigResult {
	data class Built(val config: RunConfig) : ConfigResult

	data class Refused(val problem: InterventionProblem) : ConfigResult
}

/**
 * The attributes that differ between two arms. [ArmDiff.changed] holds every non-volatile one,
 * sorted, and includes [ArmDiff.cochanging], the members a base image swap is known to bring along;
 * [ArmDiff.ignored] holds the volatile and noisy ones, which never count.
 */
data class ArmDiff(
	val changed: List<String>,
	val ignored: List<String>,
	val cochanging: List<String>,
) {
	fun toJson(): JsonObject = obj(
		"changed" to JsonArray(changed.map(::JsonString)),
		"ignored" to JsonArray(ignored.map(::JsonString)),
		"cochanging" to JsonArray(cochanging.map(::JsonString)),
	)
}

/**
 * What an arm-to-arm [ArmDiff] allows the result to claim. `mechanism` is filled only when a
 * rule or probe for that specific attribute agreed; otherwise it is null and the effect is
 * reported without a mechanism.
 */
sealed interface BundleVerdict {
	val reason: String
	val kind: String

	/** Exactly one non-volatile attribute differs, and it is the one the experiment set. */
	data class Isolated(val path: String, val mechanism: String?, override val reason: String) :
		BundleVerdict {
		override val kind get() = "isolated"
	}

	/** Several attributes differ: a confirmed effect of the bundle, not of one member. */
	data class Bundle(
		val members: List<String>,
		val notIsolated: List<String>,
		val mechanism: String?,
		override val reason: String,
	) : BundleVerdict {
		override val kind get() = "bundle"
	}

	/** No non-volatile attribute differs, so no effect can be attributed. */
	data class Empty(override val reason: String) : BundleVerdict {
		override val kind get() = "empty"
	}

	fun toJson(): JsonObject = when (this) {
		is Isolated -> obj(
			"kind" to JsonString(kind),
			"path" to JsonString(path),
			"mechanism" to (mechanism?.let(::JsonString) ?: JsonNull),
			"reason" to JsonString(reason),
		)

		is Bundle -> obj(
			"kind" to JsonString(kind),
			"members" to JsonArray(members.map(::JsonString)),
			"notIsolated" to JsonArray(notIsolated.map(::JsonString)),
			"mechanism" to (mechanism?.let(::JsonString) ?: JsonNull),
			"reason" to JsonString(reason),
		)

		is Empty -> obj("kind" to JsonString(kind), "reason" to JsonString(reason))
	}
}

enum class Pairing(val id: String) {
	CONTROL_TREATMENT("control-treatment"),
	TREATMENT_AB("treatment-ab"),
}

/** A member the arms could not set, and why; a blocker makes the experiment non-executable. */
data class Blocker(val member: String, val reason: String) {
	fun toJson(): JsonObject = obj(
		"member" to JsonString(member),
		"reason" to JsonString(reason),
	)
}

/**
 * Two arms, the diff predicted from what their interventions changed, and the verdict that diff
 * allows. [ArmSet.primaries] are the paths an intervention set directly. [executable] is false when
 * any member could not be set or the experiment is of a manual class.
 */
class ArmSet internal constructor(
	val pairing: Pairing,
	val kind: InterventionClass,
	val left: RunConfig,
	val right: RunConfig,
	val diff: ArmDiff,
	val verdict: BundleVerdict,
	val primaries: List<String>,
	val blockers: List<Blocker>,
) {
	val executable: Boolean get() = blockers.isEmpty() && kind != InterventionClass.MANUAL
}

sealed interface ArmSetResult {
	data class Built(val arms: ArmSet) : ArmSetResult

	data class Refused(val problem: InterventionProblem) : ArmSetResult
}

object Arms {
	/**
	 * Applies [interventions] to [base] in order. In a container each one is also applied alone
	 * and checked against the unchanged control: its attributes must be mirrored and the image,
	 * command, run flags or Dockerfile steps must differ, so an intervention that does not reach
 * the container is
	 * refused with the manifest's reason instead of being counted as a change.
	 */
	fun config(base: ArmBase, interventions: List<Intervention>, label: String): ConfigResult {
		if (base.environment == Environment.LOCAL) {
			val control = when (val r = build(base, emptyList(), label)) {
				is ConfigResult.Refused -> return r
				is ConfigResult.Built -> r.config
			}
			for (i in interventions) {
				verify(base, control, i)?.let { return ConfigResult.Refused(it) }
			}
		}
		return build(base, interventions, label)
	}

	private fun verify(base: ArmBase, control: RunConfig, i: Intervention): InterventionProblem? {
		val alone = when (val r = build(base, listOf(i), "")) {
			is ConfigResult.Refused -> return r.problem
			is ConfigResult.Built -> r.config
		}
		if (!i.observable) return null
		val after = alone.reproduction ?: return null
		val untouched = control.reproduction
		for (path in alone.edited.getValue(i.id)) {
			val entry = after.entries.firstOrNull { it.path == path }
				?: untouched?.entries?.firstOrNull { it.path == path }
			if (entry != null && entry.status == Status.NOT_MIRRORED) {
				return InterventionProblem.NotMirrored(i.id, path, entry.detail)
			}
		}
		val before = control.reproduction ?: return null
		if (container(after) == container(before)) {
			return InterventionProblem.NoEffect(
				i.id,
				"the container it generates equals the control's",
			)
		}
		return null
	}

	private fun container(r: Reproduction) =
		listOf(r.image, r.command, r.flags, r.dockerfile.substringAfter('\n'))

	private fun build(base: ArmBase, list: List<Intervention>, label: String): ConfigResult {
		val ids = list.map { it.id }
		ids.firstOrNull { id -> ids.count { it == id } > 1 }?.let {
			return ConfigResult.Refused(InterventionProblem.Invalid(it, "applied twice"))
		}
		val d = Draft(base.capsule, base.command, base.envNames)
		val edited = LinkedHashMap<String, List<String>>()
		for (i in list) {
			i.unsupported(base.environment)?.let {
				return ConfigResult.Refused(
					InterventionProblem.NotApplicable(i.id, base.environment, it),
				)
			}
			d.touched = mutableListOf()
			i.edit(d)?.let { return ConfigResult.Refused(it) }
			if (d.touched.isEmpty()) {
				val why = "the run configuration already has this value"
				return ConfigResult.Refused(InterventionProblem.NoEffect(i.id, why))
			}
			edited[i.id] = d.touched.toList()
		}
		val capsule = d.capsule()
		val reproduction = if (base.environment == Environment.LOCAL) {
			when (
				val r = Reproduce.synthesize(capsule, d.command, base.digests, d.envNames)
			) {
				is ReproduceResult.Refused -> {
					val problem = InterventionProblem.Invalid("run", r.problem.message())
					return ConfigResult.Refused(problem)
				}

				is ReproduceResult.Built -> r.reproduction
			}
		} else {
			null
		}
		val extras = d.extras.entries.sortedBy { it.key }.associate { it.key to it.value }
		val files = d.files.entries.sortedBy { it.key }.associate { it.key to it.value }
		val config = RunConfig(
			label, base.environment, capsule, d.command, list, edited, extras, files,
			reproduction,
		)
		return ConfigResult.Built(config)
	}

	/** Builds a control and treatment pair, or an A and B pair when [left] is not empty. */
	fun pair(
		base: ArmBase,
		left: List<Intervention>,
		right: List<Intervention>,
		labels: Pair<String, String> = "control" to "treatment",
		mechanisms: Map<String, String> = emptyMap(),
	): ArmSetResult {
		val a = when (val r = config(base, left, labels.first)) {
			is ConfigResult.Refused -> return ArmSetResult.Refused(r.problem)
			is ConfigResult.Built -> r.config
		}
		val b = when (val r = config(base, right, labels.second)) {
			is ConfigResult.Refused -> return ArmSetResult.Refused(r.problem)
			is ConfigResult.Built -> r.config
		}
		val kind = when (base.environment) {
			Environment.LOCAL -> InterventionClass.AUTOMATIC_LOCAL
			Environment.CI -> InterventionClass.AUTOMATIC_CI
		}
		return ArmSetResult.Built(assemble(kind, a, b, emptyList(), mechanisms))
	}

	/**
	 * The arms of a planner [experiment]: the passing side unchanged against the passing side
	 * with every flipped hypothesis moved to the [failing] capsule's values. Members that no
	 * intervention can set, or that the container does not mirror, become blockers and the
	 * experiment is not executable. A manual experiment has no arms and returns null; its
	 * instructions are the experiment.
	 */
	fun fromExperiment(
		experiment: Experiment,
		hypotheses: List<Hypothesis>,
		passing: Capsule,
		failing: Capsule,
		command: String,
		environment: Environment,
		digests: Map<String, String> = emptyMap(),
		mechanisms: Map<String, String> = emptyMap(),
	): ArmSet? {
		if (experiment.kind == InterventionClass.MANUAL) return null
		val byId = hypotheses.associateBy { it.id }
		val members = experiment.flips.flatMap {
			requireNotNull(byId[it]) { "unknown hypothesis $it" }.members
		}.distinct().sorted()
		val tested = passing.attributes.map { it.path }.filter { it in members }
			.filter { it.startsWith("env.") }.map { it.removePrefix("env.") }.toSet()
		val base = ArmBase(passing, command, environment, digests, tested)
		val labels = experiment.arms.first().label to experiment.arms.last().label
		val control = when (val r = config(base, emptyList(), labels.first)) {
			is ConfigResult.Refused -> throw IllegalArgumentException(r.problem.message())
			is ConfigResult.Built -> r.config
		}
		val blockers = mutableListOf<Blocker>()
		val candidates = mutableListOf<Pair<Intervention, List<String>>>()
		val before = passing.attributes.reversed().associate { it.path to it.value }
		val after = failing.attributes.reversed().associate { it.path to it.value }
		val osMembers = mutableListOf<String>()
		for (m in members) {
			val verdict = Controllability.classify(m, environment, control.reproduction)
			if (!verdict.controllable) {
				blockers += Blocker(m, verdict.reason)
			} else if (m.startsWith("os.release.") || m == "os.version") {
				osMembers += m
			} else if (!m.startsWith("step:") && before[m] == after[m]) {
				continue
			} else {
				when (val t = Interventions.toward(m, before[m], after[m])) {
					is Interventions.Toward.Cannot -> blockers += Blocker(m, t.reason)
					is Interventions.Toward.Sets -> candidates += t.intervention to listOf(m)
				}
			}
		}
		if (osMembers.isNotEmpty()) {
			val id = after["os.release.ID"] ?: after["os.release.name"]
			val version = after["os.release.VERSION_ID"] ?: after["os.version"]
			if (id == null || version == null) {
				osMembers.forEach {
					blockers += Blocker(it, "the failing capsule names no distribution release")
				}
			} else {
				candidates += Intervention.SetOs(id.lowercase(), version) to osMembers
			}
		}
		val kept = mutableListOf<Intervention>()
		for ((i, owners) in candidates) {
			val single = config(base, listOf(i), "")
			if (single is ConfigResult.Refused) {
				owners.forEach { blockers += Blocker(it, single.problem.message()) }
			} else {
				kept += i
			}
		}
		val right = when (val r = config(base, kept, labels.second)) {
			is ConfigResult.Built -> r.config

			is ConfigResult.Refused -> {
				blockers += Blocker(experiment.id, r.problem.message())
				control
			}
		}
		val sorted = blockers.sortedWith(compareBy({ it.member }, { it.reason }))
		return assemble(experiment.kind, control, right, sorted, mechanisms)
	}

	/** The members of a manual [experiment] that no intervention can set, each with why. */
	fun blockers(
		experiment: Experiment,
		hypotheses: List<Hypothesis>,
		environment: Environment,
	): List<Blocker> {
		val byId = hypotheses.associateBy { it.id }
		return experiment.flips.flatMap { byId[it]?.members ?: listOf(it) }.distinct().sorted()
			.map { Blocker(it, Controllability.classify(it, environment).reason) }
			.filter { b -> !Controllability.classify(b.member, environment).controllable }
	}

	private fun assemble(
		kind: InterventionClass,
		left: RunConfig,
		right: RunConfig,
		blockers: List<Blocker>,
		mechanisms: Map<String, String>,
	): ArmSet {
		val diff = predict(left, right)
		val primaries = (left.edited.values + right.edited.values).flatten().distinct().sorted()
			.filter { it in diff.changed }
		val pairing = if (left.interventions.isEmpty()) {
			Pairing.CONTROL_TREATMENT
		} else {
			Pairing.TREATMENT_AB
		}
		return ArmSet(
			pairing,
			kind,
			left,
			right,
			diff,
			verdict(diff, primaries, mechanisms),
			primaries,
			blockers,
		)
	}

	/**
	 * The diff the interventions are expected to produce: attribute changes between the two
	 * capsules (static only), differing flags, files and steps, and, when the two containers
	 * have different base images, the members an image swap brings along: bundle attributes the
	 * image supplies rather than run.sh, and the components it ships (tzdata, CA bundle, shell).
	 */
	fun predict(left: RunConfig, right: RunConfig): ArmDiff {
		val measured = diff(left.capsule, right.capsule)
		val changed = measured.changed.toMutableSet()
		for (key in left.extras.keys + right.extras.keys) {
			if (left.extras[key] != right.extras[key]) changed += key
		}
		val tokens = { c: RunConfig -> c.command.split(' ').filter { it.startsWith("-") }.toSet() }
		for (flag in tokens(left) xor tokens(right)) changed += "arm.flag:$flag"
		val cochanging = mutableSetOf<String>()
		val a = left.reproduction
		val b = right.reproduction
		if (a != null && b != null && a.image != b.image) {
			val both = left.capsule.attributes.reversed() + right.capsule.attributes.reversed()
			val stability = both
				.associate { it.path to it.stability }
			val ignored = measured.ignored.toMutableSet()
			for (r in listOf(a, b)) {
				for (e in r.entries.filter { it.bundle && it.status != Status.MIRRORED }) {
					if ((stability[e.path] ?: Stability.STATIC) == Stability.STATIC) {
						cochanging += e.path
					} else {
						ignored += e.path
					}
				}
				r.bundleOthers.mapTo(cochanging) { "component:$it" }
			}
			changed += cochanging
			return ArmDiff(changed.sorted(), ignored.sorted(), cochanging.sorted())
		}
		return ArmDiff(changed.sorted(), measured.ignored, emptyList())
	}

	/** The diff between two captured capsules: what 6d3 measures once both arms ran. */
	fun diff(a: Capsule, b: Capsule): ArmDiff {
		val d = CapsuleDiff.diff(a, b)
		return ArmDiff(
			d.changes.map {
			it.path
		}.sorted(),
			d.ignored.map { it.path }.sorted(),
			emptyList(),
		)
	}

	/**
	 * The bundle rule. One non-volatile attribute that is the one the experiment set is
	 * [BundleVerdict.Isolated]; several, or one that is not the target, is
	 * [BundleVerdict.Bundle] with every member listed and the members not yet isolated (all but
	 * the target when exactly one attribute was set, else all of them); none is
	 * [BundleVerdict.Empty]. [mechanisms] maps an attribute to the sentence of a rule or probe
	 * for it that agreed; it is used only for the single target.
	 */
	fun verdict(
		diff: ArmDiff,
		primaries: List<String>,
		mechanisms: Map<String, String> = emptyMap(),
	): BundleVerdict {
		val members = diff.changed.distinct().sorted()
		val targets = primaries.distinct().filter { it in members }
		val target = targets.singleOrNull()
		val ignoredNote = if (diff.ignored.isEmpty()) {
			""
		} else {
			"; ${diff.ignored.size} volatile or noisy ignored"
		}
		if (members.isEmpty()) {
			return BundleVerdict.Empty(
				"no non-volatile attribute differs between the arms$ignoredNote, so no effect " +
					"can " +
					"be attributed",
			)
		}
		val single = members.singleOrNull()
		if (single != null && (primaries.isEmpty() || single in primaries)) {
			return BundleVerdict.Isolated(
				single,
				mechanisms[single],
				"exactly one non-volatile attribute differs between the arms: $single$ignoredNote",
			)
		}
		val notIsolated = if (target != null) members.filter { it != target } else members
		val reason = if (single != null) {
			"the one attribute that differs ($single) is not the one the experiment set"
		} else {
			"${members.size} non-volatile attributes differ between the arms$ignoredNote, so an " +
				"effect cannot be attributed to one of them"
		}
		return BundleVerdict.Bundle(members, notIsolated, target?.let { mechanisms[it] }, reason)
	}
}

private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)
