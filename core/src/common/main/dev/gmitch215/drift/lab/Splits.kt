package dev.gmitch215.drift.lab

import dev.gmitch215.drift.delta.Ddmin
import dev.gmitch215.drift.delta.Verdict

/** A smaller arm that sets only some of a bundle's members, with the verdict it would allow. */
data class SplitProposal(
	val members: List<String>,
	val interventions: List<Intervention>,
	val verdict: BundleVerdict,
)

sealed interface SplitResult {
	/** [SplitResult.Proposed.fixed] are the members that cannot be set on their own, with why. */
	data class Proposed(val proposals: List<SplitProposal>, val fixed: List<Blocker>) : SplitResult

	data class NoSplit(val reason: String) : SplitResult
}

object Splits {
	/**
	 * Proposes arms that pull a [BundleVerdict.Bundle] apart: the subsets Zeller's ddmin would
	 * test over the members an intervention set directly and the environment can control, for
	 * every possible single culprit (the same pool the planner uses). Members that only travel
	 * with a base image cannot be set alone; when fewer than two members can be, there is
	 * nothing to split and the reason says so.
	 */
	fun propose(base: ArmBase, arms: ArmSet): SplitResult {
		val bundle = arms.verdict as? BundleVerdict.Bundle
			?: return SplitResult.NoSplit(
				"the verdict is ${arms.verdict.kind}, so there is no bundle",
			)
		if (arms.left.interventions.isNotEmpty()) {
			return SplitResult.NoSplit("only a control and treatment pair is split")
		}
		val owner = mutableMapOf<String, Intervention>()
		for (i in arms.right.interventions) {
			for (path in arms.right.edited.getValue(i.id)) owner[path] = i
		}
		val controllable = mutableListOf<String>()
		val fixed = mutableListOf<Blocker>()
		for (m in bundle.members) {
			val verdict = Controllability.classify(m, base.environment, arms.left.reproduction)
			when {
				owner[m] == null -> {
					val why = "no intervention sets it; it travels with the base image"
					fixed += Blocker(m, why)
				}

				verdict.controllable -> controllable += m

				else -> fixed += Blocker(m, verdict.reason)
			}
		}
		if (controllable.size < 2) {
			val why = fixed.joinToString("; ") { "${it.member}: ${it.reason}" }
			val head = if (controllable.isEmpty()) {
				"none of the ${bundle.members.size} members can be set on its own"
			} else {
				"only ${controllable.single()} can be set on its own"
			}
			return SplitResult.NoSplit(if (why.isEmpty()) head else "$head ($why)")
		}
		val pool = linkedSetOf<List<String>>()
		for (culprit in controllable) {
			val result = Ddmin.run(controllable) {
				if (culprit in it) Verdict.FAIL else Verdict.PASS
			}
			result.sequence.mapTo(pool) { subset -> subset.map { controllable[it] } }
		}
		val all = arms.right.interventions.map { it.id }.toSet()
		val seen = mutableSetOf<Set<String>>()
		val proposals = mutableListOf<SplitProposal>()
		for (subset in pool.sortedWith(compareBy({ it.size }, { it.joinToString("\u0000") }))) {
			val chosen = arms.right.interventions.filter { i ->
				arms.right.edited.getValue(i.id).any { it in subset }
			}
			val ids = chosen.map { it.id }.toSet()
			if (ids.isEmpty() || ids == all || !seen.add(ids)) continue
			val built = Arms.pair(base, emptyList(), chosen) as? ArmSetResult.Built ?: continue
			val members = chosen.flatMap { arms.right.edited.getValue(it.id) }
				.filter { it in bundle.members }.distinct().sorted()
			proposals += SplitProposal(members, chosen, built.arms.verdict)
		}
		if (proposals.isEmpty()) {
			return SplitResult.NoSplit(
				"every subset of the controllable members is the original arm",
			)
		}
		return SplitResult.Proposed(proposals, fixed)
	}
}
