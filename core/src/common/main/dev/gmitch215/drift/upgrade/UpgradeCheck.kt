package dev.gmitch215.drift.upgrade

import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Env
import dev.gmitch215.drift.know.Node
import dev.gmitch215.drift.know.Pred
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.know.Status
import dev.gmitch215.drift.know.Verdict
import dev.gmitch215.drift.know.Which
import dev.gmitch215.drift.model.Capsule

/**
 * Evaluates every rule with the capture as side `a` and the capture plus the [Target]s as side
 * `b`. Probe results and run facts describe today's environment, so side `b` has none: a rule
 * that reads one is unknown. A `pending-scanner` rule reads keys no scanner emits, so a key
 * missing from the capture counts as unknown there, not as absent. The groups follow [Detect]'s
 * three-valued logic:
 * - `will-break`: the rule matches, every fact it reads is known, and at least one matched
 *   clause reads a project fact or an environment key that is not a target.
 * - `may-break`: every fact the rule reads on a target key is known and the verdict is unknown
 *   because another fact is missing or no scanner emits it yet.
 * - `version-boundary`: the rule matches but every clause that matched reads only a target key,
 *   so the evidence is the version crossing alone; no project fact or other environment key
 *   was read.
 * - `outside-range`: the rule matches, every fact it reads is known, and every symptom of the
 *   rule has the basis `range`: the source lists a compatible range and states no failure
 *   outside it. The group takes the place of `will-break` and `version-boundary`.
 * - `unknown`: the capture lacks the current value of a target key, or the target is not a
 *   version the rule can compare. A rule that matches only because that value is missing is
 *   listed here too, not as `will-break`.
 *
 * Locked packages are checked apart from the rules: a package whose recorded runtime constraint
 * allows the capture's runtime and excludes the target is listed in [UpgradeReport.packages]
 * (see [PackageConstraints]); a lockfile that records no constraint is listed in
 * [UpgradeReport.unchecked].
 *
 * Only rules with a condition on side `b` (or a cross or changed condition) that reads a target
 * key and gives another verdict for the target than for the capture itself are reported. A
 * rule that already matches without the targets is left out.
 */
object UpgradeCheck {
    fun run(capsule: Capsule, targets: List<Target>, rules: List<Rule> = Rules.all): UpgradeReport {
        val a = Detect.env(capsule)
        val keys = targets.map { it.key }.toSet()
        val b = Env(a.attrs + targets.associate { it.key to it.value })
        var touched = 0
        val findings = rules.sortedBy { it.id }.mapNotNull { rule ->
            val leaves = leaves(rule.detect)
            val reading = leaves.filter { reads(it, keys) }
            if (reading.isEmpty()) return@mapNotNull null
            touched++
            val moved = reading.filter { verdict(rule, it, a, b) != verdict(rule, it, a, a) }
            if (moved.isEmpty()) null else finding(rule, leaves, moved, a, b, keys)
        }.sortedWith(order)
        val (packages, unchecked) = PackageConstraints.check(capsule, targets, a.attrs)
        return UpgradeReport(
            capsule.label,
            targets.map { UpgradeReport.Row(it, a.attrs[it.key]) },
            rules.size,
            touched,
            findings,
            packages,
            unchecked,
        )
    }

    private val order = compareBy<Finding>(
        { it.group.ordinal },
        { -it.rule.severity.ordinal },
        { if (it.basis == Basis.VERIFIED) 0 else 1 },
        { it.rule.id },
    )

    private fun verdict(rule: Rule, node: Node, a: Env, b: Env): Verdict = when (node) {
        is Node.AllOf -> all(node.nodes.map { verdict(rule, it, a, b) })

        is Node.AnyOf -> any(node.nodes.map { verdict(rule, it, a, b) })

        is Node.Not -> when (verdict(rule, node.node, a, b)) {
            Verdict.MATCH -> Verdict.NO_MATCH
            Verdict.NO_MATCH -> Verdict.MATCH
            Verdict.UNKNOWN -> Verdict.UNKNOWN
        }

        else -> if (unscanned(rule, node, a, b)) {
            Verdict.UNKNOWN
        } else {
            Detect.walk(node, a, b, mutableSetOf())
        }
    }

    private fun all(vs: List<Verdict>) = when {
        Verdict.NO_MATCH in vs -> Verdict.NO_MATCH
        Verdict.UNKNOWN in vs -> Verdict.UNKNOWN
        else -> Verdict.MATCH
    }

    private fun any(vs: List<Verdict>) = when {
        Verdict.MATCH in vs -> Verdict.MATCH
        Verdict.UNKNOWN in vs -> Verdict.UNKNOWN
        else -> Verdict.NO_MATCH
    }

    // a key no scanner emits is absent from every capture, so `present` cannot say it is missing
    private fun unscanned(rule: Rule, leaf: Node, a: Env, b: Env): Boolean {
        val path = path(leaf)
        return rule.status == Status.PENDING_SCANNER && path != null &&
            a.lookup(path) == null && b.lookup(path) == null
    }

    private fun finding(
        rule: Rule,
        leaves: List<Node>,
        moved: List<Node>,
        a: Env,
        b: Env,
        keys: Set<String>,
    ): Finding? {
        val basis = when {
            rule.symptoms.all { it.basis == Basis.VERIFIED } -> Basis.VERIFIED
            rule.symptoms.all { it.basis == Basis.RANGE } -> Basis.RANGE
            else -> Basis.INFERRED
        }
        val plain = leaves(rule.detect, negated = false)
        val crossings = keys.sorted().mapNotNull { crossing(it, plain, leaves, moved, a, b) }
        val absent = crossings.filter { it.from == null }.map { it.key }
        return when (verdict(rule, rule.detect, a, b)) {
            Verdict.NO_MATCH -> null

            Verdict.MATCH -> {
                if (verdict(rule, rule.detect, a, a) == Verdict.MATCH) return null
                val facts = facts(rule, a, b, keys)
                val group = when {
                    absent.isNotEmpty() -> Group.UNKNOWN
                    basis == Basis.RANGE -> Group.OUTSIDE_RANGE
                    facts.isEmpty() -> Group.VERSION_BOUNDARY
                    else -> Group.WILL_BREAK
                }
                Finding(rule, group, basis, crossings, absent, facts)
            }

            Verdict.UNKNOWN -> {
                val unknown = leaves.filter { verdict(rule, it, a, b) == Verdict.UNKNOWN }
                val missing = (unknown.mapNotNull(::path) + absent).distinct().sorted()
                val boundary = absent.isNotEmpty() ||
                    unknown.any { n -> path(n)?.let { matches(it, keys) } == true }
                val group = if (boundary) Group.UNKNOWN else Group.MAY_BREAK
                Finding(rule, group, basis, crossings, missing)
            }
        }
    }

    // paths outside the targets read by the clauses that made the rule match
    private fun facts(rule: Rule, a: Env, b: Env, keys: Set<String>): List<String> =
        contributing(rule, rule.detect, Verdict.MATCH, a, b).mapNotNull(::path)
            .filter { !matches(it, keys) }.distinct().sorted()

    private fun contributing(rule: Rule, node: Node, want: Verdict, a: Env, b: Env): List<Node> =
        when (node) {
            is Node.Not -> contributing(rule, node.node, flip(want), a, b)
            is Node.AllOf -> pick(rule, node.nodes, want == Verdict.MATCH, want, a, b)
            is Node.AnyOf -> pick(rule, node.nodes, want == Verdict.NO_MATCH, want, a, b)
            else -> listOf(node)
        }

    private fun pick(
        rule: Rule,
        nodes: List<Node>,
        every: Boolean,
        want: Verdict,
        a: Env,
        b: Env,
    ) = nodes.filter { every || verdict(rule, it, a, b) == want }
        .flatMap { contributing(rule, it, want, a, b) }

    private fun flip(v: Verdict) = if (v == Verdict.MATCH) Verdict.NO_MATCH else Verdict.MATCH

    private fun crossing(
        key: String,
        plain: List<Node>,
        leaves: List<Node>,
        moved: List<Node>,
        a: Env,
        b: Env,
    ): Crossing? {
        val now = moved.filter { path(it)?.let { p -> matches(p, setOf(key)) } == true }
        if (now.isEmpty()) return null
        val was = plain.filterIsInstance<Node.On>()
            .filter { it.which == Which.A && matches(it.path, setOf(key)) }
        return Crossing(
            key,
            a.attrs[key],
            b.attrs.getValue(key),
            was.map { describe(it.pred) }.distinct(),
            now.map(::describe).distinct(),
        )
    }

    private fun leaves(node: Node): List<Node> = leaves(node, negated = true)

    // `was` reads the conditions the rule asks for, not the ones it excludes with `not`
    private fun leaves(node: Node, negated: Boolean): List<Node> = when (node) {
        is Node.AllOf -> node.nodes.flatMap { leaves(it, negated) }
        is Node.AnyOf -> node.nodes.flatMap { leaves(it, negated) }
        is Node.Not -> if (negated) leaves(node.node, true) else emptyList()
        else -> listOf(node)
    }

    private fun path(leaf: Node): String? = when (leaf) {
        is Node.On -> leaf.path
        is Node.Cross -> leaf.path
        is Node.Changed -> leaf.path
        else -> null
    }

    private fun matches(path: String, keys: Set<String>) =
        if (path.endsWith("*")) keys.any { it.startsWith(path.dropLast(1)) } else path in keys

    private fun reads(leaf: Node, keys: Set<String>): Boolean {
        val side = leaf !is Node.On || leaf.which == Which.B
        return side && path(leaf)?.let { matches(it, keys) } == true
    }

    private fun describe(leaf: Node): String = when (leaf) {
        is Node.On -> describe(leaf.pred)
        is Node.Cross -> "differs on ${describe(leaf.pred)}"
        else -> "changes"
    }

    private fun describe(pred: Pred): String = when (pred) {
        is Pred.Ver -> pred.ranges.joinToString(" or ")
        is Pred.Eq -> "== ${pred.value}"
        is Pred.Ne -> "!= ${pred.value}"
        is Pred.In -> "in ${pred.values.joinToString(", ")}"
        is Pred.Has -> "has ${pred.value}"
        is Pred.Starts -> "starts ${pred.value}"
        is Pred.Present -> if (pred.present) "present" else "absent"
    }
}
