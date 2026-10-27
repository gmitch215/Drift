package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.delta.Ddmin
import dev.gmitch215.drift.delta.Verdict
import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.ProbeChangeStatus
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Arm
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Cost
import dev.gmitch215.drift.plan.Decision
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.Experiment
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.Hypothesis
import dev.gmitch215.drift.plan.Information
import dev.gmitch215.drift.plan.InterventionClass
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.OutcomeTable
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.plan.Planned
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.plan.StuckCode
import dev.gmitch215.drift.rank.EvidenceKind
import dev.gmitch215.drift.rank.Ranker

/**
 * How a solve runs. [SolveOptions.budget] caps the whole run: trials over every arm and wall
 * minutes. [SolveOptions.pilot] trials per arm check that the failure reproduces and give the
 * failure rate the planner sizes later arms from. [SolveOptions.trialMinutes] is the planner's
 * assumed wall time of a trial.
 */
data class SolveOptions(
    val command: String,
    val failWhen: FailWhen,
    val budget: Budget,
    val name: String? = null,
    val trialMinutes: Long = 1,
    val pilot: Int = 8,
    val maxRounds: Int = 12,
    val digests: Map<String, String> = emptyMap(),
    val passing: String = "passing.json",
    val failing: String = "failing.json",
) {
    init {
        require(pilot in 3..MAX_PILOT) { "--pilot must be within 3..$MAX_PILOT" }
        require(maxRounds >= 1) { "at least one round" }
    }

    companion object {
        const val MAX_PILOT = 30
    }
}

private class Halt(val why: StuckWhy, val detail: String) : RuntimeException(detail)

/**
 * The closed loop, one use per instance: pilot, rank, plan, run the preregistered arms, update
 * the belief with the exact test, repeat. It never claims an experiment it did not run; every
 * result holds its trials. The first experiment that moves the failure rate is taken through the
 * minimal-set search and the reverse arm before anything is called CONFIRMED.
 */
class Solver(
    private val executor: Executor,
    private val files: CaseFiles,
    private val dir: String,
    private val options: SolveOptions,
) {
    private val runs = mutableListOf<ExperimentRun>()
    private val extra = linkedMapOf<String, String>()
    private val skipped = mutableListOf<Pair<String, List<String>>>()
    private val posteriors = mutableListOf<Pair<String, Belief>>()
    private val blocked = linkedMapOf<String, String>()
    private val tried = mutableSetOf<Set<String>>()
    private val ruledOut = linkedSetOf<String>()
    private val captured = mutableMapOf<String, Capsule?>()
    private var spentTrials = 0
    private var spentSeconds = 0L
    private var counter = 0
    private var secondsPerTrial = 1L

    private lateinit var green: Capsule
    private lateinit var red: Capsule
    private lateinit var frame: Frame
    private lateinit var plan: Plan
    private lateinit var belief: Belief
    private lateinit var baseline: Reproduction
    private var confirmation: Confirmation? = null

    private class Confirmation(
        val forward: ExperimentRun,
        val minimal: List<String>,
        val search: SearchRecord,
        val reverse: ExperimentRun?,
        val replicate: ExperimentRun?,
        val diff: MeasuredDiff,
        val verdict: SolveVerdict,
        val removals: List<Removal> = emptyList(),
    )

    class SearchRecord(val tested: Int, val unresolved: Int, val order: List<List<String>>)

    fun solve(green: Capsule, red: Capsule): SolveResult {
        this.green = green
        this.red = red
        this.frame = Frame.environments(green.label, red.label)
        val ranking = Ranker.rank(green, red)
        var verdict: SolveVerdict
        var first: Plan? = null
        try {
            val pilot = runPilot(green, red)
            val pilotTrials = 2L * pilot.control.trials.size
            secondsPerTrial = maxOf(
                1L,
                (pilot.control.seconds + pilot.treatment.seconds + pilotTrials - 1) / pilotTrials,
            )
            val seen = pilot.treatment.counts
            val control = pilot.control.counts
            if (seen.failures == 0) {
                throw Halt(
                    StuckWhy.NOT_REPRODUCED,
                    "the failure does not reproduce in the container built from the failing " +
                        "capsule: 0 of ${seen.trials} trials failed, so the failure rate is " +
                        "below ${PilotRule.upperPercent(seen.trials)}% at 95%",
                )
            }
            if (control.failures >= 2 && control.failures >= seen.failures) {
                val both = "the passing configuration fails as often as the failing one in a " +
                    "container (${control.failures} of ${control.trials} against " +
                    "${seen.failures} " +
                    "of ${seen.trials})"
                val probe = Planner.from(
                    ranking,
                    frame,
                    Options(options.trialMinutes, seen.failures, seen.trials),
                )
                val manual = probe.manualIds().distinct().sorted()
                if (manual.isNotEmpty()) {
                    throw Halt(
                        StuckWhy.UNCONTROLLABLE,
                        "$both; the candidates that differ cannot be set from a container: " +
                            manual.joinToString(", "),
                    )
                }
                throw Halt(
                    StuckWhy.NOT_REPRODUCED,
                    "$both, so nothing controllable separates them",
                )
            }
            val opts = Options(options.trialMinutes, seen.failures, seen.trials)
            plan = Planner.from(ranking, frame, opts)
            first = plan
            belief = plan.prior
            posteriors += "prior" to belief
            verdict = loop()
        } catch (h: Halt) {
            verdict = finish(h)
        }
        val why = verdict.why
        if (verdict.kind == VerdictKind.STUCK && why != null) {
            val unmirrored = if (::baseline.isInitialized) {
                baseline.entries.filter { it.status == Status.NOT_MIRRORED }.map { it.path }
            } else {
                emptyList()
            }
            val context = NextContext(
                why,
                verdict.statement,
                options,
                dir,
                unmirrored,
                verdict.candidates,
            )
            verdict = SolveNext.resolve(verdict, context)
        }
        val initial = first ?: Planner.from(ranking, frame, Options(options.trialMinutes, 1, 1))
        return SolveCertificate.build(
            name = options.name ?: "${green.label} to ${red.label}",
            green = green,
            red = red,
            ranking = ranking,
            plan = initial,
            runs = runs,
            extra = extra,
            posteriors = posteriors,
            skipped = skipped,
            blocked = blocked,
            confirmation = confirmation?.let {
                SolveCertificate.Confirmed(
                    it.forward,
                    it.minimal,
                    it.search.tested,
                    it.search.unresolved,
                    it.search.order,
                    it.reverse,
                    it.replicate,
                    it.diff,
                    it.removals,
                )
            },
            verdict = verdict,
            baseline = if (::baseline.isInitialized) baseline else null,
            options = options,
            trials = spentTrials,
            seconds = spentSeconds,
        ).let {
            SolveResult(
                verdict,
                it,
                runs.toList(),
                spentTrials,
                spentSeconds,
                initial.hypotheses.size,
            )
        }
    }

    // #region pilot

    private fun runPilot(green: Capsule, red: Capsule): ExperimentRun {
        fun config(c: Capsule, label: String): RunConfig {
            val base = ArmBase(
                c,
                options.command,
                Environment.LOCAL,
                options.digests,
                diffEnv(c),
            )
            return when (val r = Arms.config(base, emptyList(), label)) {
                is ConfigResult.Built -> r.config

                is ConfigResult.Refused ->
                    throw Halt(StuckWhy.EXECUTOR, "cannot build $label: ${r.problem.message()}")
            }
        }
        val left = config(green, "passing baseline")
        val right = config(red, "failing baseline")
        baseline = left.reproduction!!
        val cap = PilotRule.max(options.pilot, options.budget.maxTrials)
        return execute("p0", "pilot", emptyList(), null, left, right, options.pilot, null, cap)
    }

    /** The names of the environment variables that differ between the capsules and [c] holds. */
    private fun diffEnv(c: Capsule): Set<String> {
        val names = CapsuleDiff.attributes(green, red).map { it.path }
            .filter { it.startsWith("env.") }.map { it.removePrefix("env.") }.toSet()
        val present = c.attributes.map { it.path.removePrefix("env.") }.toSet()
        return names.filter { it in present }.toSet()
    }

    // #endregion

    // #region the loop

    private fun loop(): SolveVerdict {
        var rounds = 0
        while (rounds < options.maxRounds) {
            val next = nextRunnable() ?: break
            rounds++
            val (planned, arms) = next
            tried += planned.experiment.flips.toSet()
            val run = runExperiment(
                planned.experiment,
                arms,
                "forward",
                planned.experiment.flips,
                "x${++counter}",
            )
            when (run.outcome) {
                Outcome.SUPPORTED -> {
                    update(run.flips, true)
                    return confirm(run)
                }

                Outcome.REFUTED -> {
                    ruledOut += run.flips
                    update(run.flips, false)
                }

                else -> Unit
            }
            replan()
        }
        return exhausted(rounds)
    }

    private fun nextRunnable(): Pair<Planned, ArmSet>? {
        while (true) {
            val newly = linkedMapOf<String, String>()
            for (p in plan.experiments) {
                if (p.decision != Decision.NEXT && p.decision != Decision.ALTERNATIVE) continue
                if (p.experiment.flips.toSet() in tried) continue
                val arms = build(p.experiment, green, red)
                if (arms != null && arms.executable) return p to arms
                val why = arms?.blockers.orEmpty()
                skipped += p.experiment.id to why.map { "${it.member}: ${it.reason}" }
                val owners = plan.hypotheses.filter { h -> why.any { it.member in h.members } }
                if (owners.isEmpty()) {
                    tried += p.experiment.flips.toSet()
                } else {
                    for (h in owners) {
                        newly[h.id] = why.filter { it.member in h.members }
                            .joinToString("; ") { it.reason }
                    }
                    break
                }
            }
            if (newly.isEmpty()) return null
            blocked += newly
            replan()
        }
    }

    private fun build(e: Experiment, passing: Capsule, failing: Capsule): ArmSet? = try {
        Arms.fromExperiment(
            e,
            plan.hypotheses,
            passing,
            failing,
            options.command,
            Environment.LOCAL,
            options.digests,
        )
    } catch (x: IllegalArgumentException) {
        throw Halt(StuckWhy.EXECUTOR, "cannot build the control arm: ${x.message}")
    }

    private fun replan() {
        plan = Planner.plan(
            plan.hypotheses,
            belief,
            frame,
            plan.options,
            excluded = plan.excluded,
            blocked = blocked,
        )
    }

    private fun update(flips: List<String>, reproduced: Boolean) {
        val ids = belief.masses.map { it.id }
        val table = OutcomeTable.binary(ids, flips.toSet(), plan.trials.power, plan.options.alpha)
        val wanted = if (reproduced) OutcomeTable.REPRODUCED else OutcomeTable.NOT_REPRODUCED
        belief = Information.branches(belief, table).first { it.outcome == wanted }.posterior
        posteriors += "${runs.last().id} ${runs.last().outcome?.id}" to belief
    }

    private fun exhausted(rounds: Int): SolveVerdict {
        val untried = plan.experiments.any {
            (it.decision == Decision.NEXT || it.decision == Decision.ALTERNATIVE) &&
                it.experiment.flips.toSet() !in tried
        }
        if (rounds >= options.maxRounds && untried) {
            throw Halt(StuckWhy.BUDGET, "the round limit of ${options.maxRounds} was reached")
        }
        plan.stuck.firstOrNull { it.code == StuckCode.BUDGET }
            ?.let { throw Halt(StuckWhy.BUDGET, it.detail) }
        val inconclusive = runs.filter {
            it.kind == "forward" && it.outcome == Outcome.INCONCLUSIVE
        }
        if (inconclusive.isNotEmpty()) {
            val ids = inconclusive.flatMap { it.flips }.distinct().sorted()
            val text = "no experiment reached significance; ${inconclusive.size} stayed " +
                "inconclusive at ${inconclusive.first().control.trials.size} trials per arm: " +
                ids.joinToString(", ")
            return SolveVerdict(
                VerdictKind.NARROWED,
                null,
                text,
                candidates = ids,
                next = "re-run ${ids.joinToString(", ")} with more trials per arm",
            )
        }
        val reproduced = "the failing container reproduces it " +
            "(${plan.options.failures} of ${plan.options.runs})"
        if (plan.hypotheses.isEmpty()) {
            return SolveVerdict(
                VerdictKind.STUCK,
                StuckWhy.NO_CANDIDATES,
                "the capsules differ in no attribute an experiment could test; $reproduced, so " +
                    "the cause is outside what the capsules show",
            )
        }
        val manual = plan.manualIds().distinct().sorted()
        if (manual.isNotEmpty()) {
            return SolveVerdict(
                VerdictKind.STUCK,
                StuckWhy.UNCONTROLLABLE,
                "no controllable difference reproduces the failure; the candidates left cannot " +
                    "be set from a container: ${manual.joinToString(", ")}",
                candidates = manual,
            )
        }
        return SolveVerdict(
            VerdictKind.STUCK,
            StuckWhy.NOT_REPRODUCED,
            "the failure does not reproduce in any controllable environment: ${ruledOut.size} " +
                "candidates were ruled out and $reproduced, so the cause is outside what the " +
                "capsules differ in or what a container mirrors",
            candidates = ruledOut.toList(),
        )
    }

    private fun finish(h: Halt): SolveVerdict {
        val supported = runs.lastOrNull {
            it.kind == "forward" && it.outcome == Outcome.SUPPORTED && !it.void
        }
        if (supported != null) {
            return SolveVerdict(
                VerdictKind.NARROWED,
                null,
                "the failure reproduces when ${supported.flips.joinToString(", ")} are set to " +
                    "the failing values, but the run stopped before it was confirmed: ${h.detail}",
                candidates = supported.flips,
                next = "finish the minimal-set search and the reverse arm for " +
                    supported.flips.joinToString(", "),
            )
        }
        return SolveVerdict(VerdictKind.STUCK, h.why, h.detail)
    }

    // #endregion

    // #region confirmation

    private fun confirm(forward: ExperimentRun): SolveVerdict {
        val tests = linkedMapOf<Set<String>, ExperimentRun>(forward.flips.toSet() to forward)
        val order = mutableListOf<List<String>>()
        var halted: Halt? = null
        var minimal = forward.flips
        var unresolved = 0
        val atoms = atoms(forward.flips)
        if (atoms.size >= 2) {
            val found = Ddmin.run(atoms) { chosen ->
                val subset = chosen.flatten()
                order += subset
                val cached = tests[subset.toSet()]
                val run = cached ?: if (halted != null) {
                    null
                } else {
                    try {
                        runSubset(subset, "${forward.id}.m${tests.size}", "minimal")
                    } catch (h: Halt) {
                        halted = h
                        null
                    }
                }
                if (run != null) tests[subset.toSet()] = run
                when (run?.outcome) {
                    Outcome.SUPPORTED -> Verdict.FAIL
                    Outcome.REFUTED -> Verdict.PASS
                    else -> Verdict.UNRESOLVED
                }
            }
            minimal = found.minimal.flatten()
            unresolved = found.unresolved
        }
        val names = minimal.joinToString(", ")
        val search = SearchRecord(order.size, unresolved, order)
        val minimalRun = tests.getValue(minimal.toSet())
        val arms = build(experimentOf("m", minimal, green.label, red.label), green, red)
        val diff = armsDiff(minimalRun, arms)
        val note = halted?.let { " (the search stopped early: ${it.detail})" }.orEmpty()
        if (unresolved > 0 || halted != null) {
            val v = SolveVerdict(
                VerdictKind.NARROWED,
                null,
                "the failure reproduces when $names are set to the failing " +
                    "values, but the minimal-set search left $unresolved subsets unresolved$note",
                set = minimal,
                candidates = minimal,
                next = "run the unresolved subsets of $names with more " +
                    "trials",
            )
            confirmation = Confirmation(forward, minimal, search, null, null, diff, v)
            return v
        }
        var reverse: ExperimentRun? = null
        var replicate: ExperimentRun? = null
        var agree = false
        try {
            reverse = runReverse(minimal, "${forward.id}.rev")
            agree = reverse != null && reverse.outcome == Outcome.SUPPORTED
            if (reverse == null) {
                replicate = runSubset(minimal, "${forward.id}.rep", "replicate")
                agree = replicate?.outcome == Outcome.SUPPORTED
            }
        } catch (h: Halt) {
            halted = h
        }
        if (!agree) {
            val reason = halted?.detail ?: if (reverse != null) {
                "the reverse arm (${reverse.outcome?.id}) did not agree"
            } else {
                "the replication (${replicate?.outcome?.id}) did not agree"
            }
            val v = SolveVerdict(
                VerdictKind.NARROWED,
                null,
                "the failure reproduces when $names are set to the failing " +
                    "values, but it is not confirmed: $reason",
                set = minimal,
                candidates = minimal,
                next = "repeat the reverse arm for $names with more trials",
            )
            confirmation = Confirmation(forward, minimal, search, reverse, replicate, diff, v)
            return v
        }
        val mech = mechanism(minimalRun, minimal)
        val counts = "${minimalRun.treatment.failures} of ${minimalRun.treatment.trials.size} " +
            "(exact one-sided p ${p(minimalRun)})"
        val base = "${minimalRun.control.failures} of ${minimalRun.control.trials.size}"
        val set = minimal.joinToString(", ")
        var removals = emptyList<Removal>()
        val v = if (!SolveCertificate.measured(diff, reverse ?: replicate)) {
            SolveVerdict(
                VerdictKind.EFFECT,
                null,
                "effect confirmed, isolation not measured: setting $set to the failing values " +
                    "moved the failure count from $base to $counts and " +
                    "${confirmedBy(reverse, replicate)}. The attributes that differ between " +
                    "the arms were predicted from the interventions and not captured in the " +
                    "containers, so the effect is not attributed to $set alone",
                set = minimal,
                members = diff.changed,
                notIsolated = diff.changed,
                mechanism = mech,
            )
        } else if (diff.isolated(arms?.primaries.orEmpty())) {
            val kind = if (minimal.size == 1) {
                "this dimension"
            } else {
                "this conjunction of ${minimal.size}"
            }
            SolveVerdict(
                VerdictKind.CONFIRMED,
                null,
                "$kind causes the failure: $set. Setting it to the failing values moved the " +
                    "failure count from $base to $counts and ${confirmedBy(reverse, replicate)}",
                set = minimal,
                members = diff.changed,
                mechanism = mech,
            )
        } else {
            val primaries = arms?.primaries.orEmpty()
            val splits = splitBundle(arms, minimalRun)
            removals = testMembers(forward, diff, primaries)
            SolveVerdict(
                VerdictKind.BUNDLE,
                null,
                "the arms differ in ${diff.changed.size} attributes, so the effect is the " +
                    "bundle's and not one member's: ${diff.changed.joinToString(", ")}. " +
                    "Setting $set moved " +
                    "the failure count from $base to $counts and " +
                    confirmedBy(reverse, replicate) + ". $splits" + removalText(removals),
                set = minimal,
                members = diff.changed,
                notIsolated = diff.changed.filter { it !in primaries || primaries.size > 1 },
                mechanism = mech,
                necessary = removals.filter { it.status == NECESSARY }.map { it.member },
                sufficient = removals.filter { it.status == SUFFICIENT }.map { it.member },
            )
        }
        confirmation =
            Confirmation(forward, minimal, search, reverse, replicate, diff, v, removals)
        return v
    }

    /**
     * Forward flips that one intervention sets together are one atom: no arm tests one without the
     * others, so the minimal-set search keeps or drops them as a group.
     */
    private fun atoms(flips: List<String>): List<List<String>> {
        val groups = linkedMapOf<String, MutableList<String>>()
        for (f in flips) {
            val key = try {
                build(experimentOf("a", listOf(f), green.label, red.label), green, red)
                    ?.takeIf { it.executable }?.right?.interventions?.map { it.id }?.sorted()
                    ?.joinToString("|")
            } catch (h: Halt) {
                null
            }
            groups.getOrPut(key?.takeIf { it.isNotEmpty() } ?: "own:$f") { mutableListOf() } += f
        }
        return groups.values.toList()
    }

    /**
     * Tests each member of a bundle that its own intervention can set, one at a time. Setting the
     * member alone on the passing side to its failing value that reproduces the failure makes it
     * sufficient; otherwise reverting it alone on the failing side that removes the failure makes
     * it necessary. A member counts only when the arm's capture shows that member changed and
     * nothing else. Members no arm sets alone are reported as untested, with the reason.
     */
    private fun testMembers(
        forward: ExperimentRun,
        diff: MeasuredDiff,
        primaries: List<String>,
    ): List<Removal> {
        val out = mutableListOf<Removal>()
        var stopped: String? = null
        var ran = 0
        val members = diff.changed.filter { it !in primaries }
            .sortedBy { it.startsWith("env.") }
        for (m in members) {
            fun untested(why: String) {
                out += Removal(m, UNTESTED, null, why)
            }

            fun attempt(
                from: Capsule,
                to: Capsule,
                fromSeen: Capsule?,
                toSeen: Capsule?,
                kind: String,
            ): Pair<ExperimentRun?, String?> {
                val toward = Interventions.toward(m, seen(from, fromSeen, m), seen(to, toSeen, m))
                if (toward is Interventions.Toward.Cannot) return null to toward.reason
                if (ran >= MAX_MEMBER_RUNS) {
                    return null to "at most $MAX_MEMBER_RUNS member arms are run per solve"
                }
                val i = (toward as Interventions.Toward.Sets).intervention
                val id = "${forward.id}.r${++ran}"
                val e = experimentOf(id, listOf(m), from.label, to.label)
                val labels = e.arms.first().label to e.arms.last().label
                val base = ArmBase(
                    from,
                    options.command,
                    Environment.LOCAL,
                    options.digests,
                    diffEnv(from),
                )
                return when (val built = Arms.pair(base, emptyList(), listOf(i), labels)) {
                    is ArmSetResult.Refused -> null to built.problem.message()

                    is ArmSetResult.Built -> if (built.arms.diff.changed != listOf(m)) {
                        val also = built.arms.diff.changed.filter { it != m }
                        null to "the arm also changes ${also.joinToString(", ")}"
                    } else {
                        try {
                            val run = runExperiment(
                                e,
                                built.arms,
                                kind,
                                listOf(m),
                                id,
                                from.label,
                                to.label,
                            )
                            run to null
                        } catch (h: Halt) {
                            if (h.why == StuckWhy.BUDGET) stopped = h.detail
                            null to h.detail
                        }
                    }
                }
            }
            val halted = stopped
            if (halted != null) {
                untested(halted)
                continue
            }
            val passingSeen = forward.control.capsule
            val failingSeen = forward.treatment.capsule
            val set = attempt(green, red, passingSeen, failingSeen, "member")
            val setRun = set.first
            if (setRun != null) {
                val r = judgeMember(m, setRun, SUFFICIENT)
                if (r.status == SUFFICIENT || r.status == UNTESTED) {
                    out += r
                    continue
                }
            }
            val revert = attempt(red, green, failingSeen, passingSeen, "removal")
            val run = revert.first
            when {
                run != null -> out += judgeMember(m, run, NECESSARY)

                setRun != null -> out += judgeMember(m, setRun, SUFFICIENT)

                else -> untested(
                    listOfNotNull(set.second, revert.second).distinct().joinToString("; "),
                )
            }
        }
        return out
    }

    private fun judgeMember(m: String, run: ExperimentRun, status: String): Removal {
        val d = run.diff
        val more = if (status == SUFFICIENT) run.treatment else run.control
        val less = if (status == SUFFICIENT) run.control else run.treatment
        val how = if (status == SUFFICIENT) {
            "set alone on the passing side"
        } else {
            "reverted alone on the failing side"
        }
        val counts = "$how: ${more.failures} of ${more.trials.size} against " +
            "${less.failures} of ${less.trials.size}"
        return when {
            run.outcome != Outcome.SUPPORTED ->
                Removal(m, NOT_SHOWN, run.id, "the failure did not follow ($counts)")

            d == null || d.source != MeasuredDiff.MEASURED || d.changed != listOf(m) ->
                Removal(
                    m,
                    UNTESTED,
                    run.id,
                    "the capture of the arm shows ${d?.changed.orEmpty().joinToString(", ")} " +
                        "changed, not this member alone",
                )

            else -> Removal(m, status, run.id, "($counts, p ${p(run)})")
        }
    }

    private fun removalText(removals: List<Removal>): String {
        if (removals.isEmpty()) return ""
        val parts = mutableListOf<String>()
        for (r in removals.filter { it.status == SUFFICIENT }) {
            parts += "${r.member} alone reproduces the failure ${r.detail}"
        }
        for (r in removals.filter { it.status == NECESSARY }) {
            parts += "${r.member} alone is needed for the failure ${r.detail}"
        }
        for (r in removals.filter { it.status == NOT_SHOWN }) {
            parts += "${r.member} alone does not decide it (${r.detail})"
        }
        val rest = removals.filter { it.status == UNTESTED }
        if (rest.isNotEmpty()) {
            parts += "Not tested alone: " +
                rest.joinToString("; ") { "${it.member} (${it.detail})" }
        }
        return " Member tests: " + parts.joinToString(". ") + "."
    }

    /** The value in [c], or in the capsule captured inside an arm when [c] does not record it. */
    private fun seen(c: Capsule, captured: Capsule?, path: String): String? =
        value(c, path) ?: captured?.let { value(it, path) }

    private fun value(c: Capsule, path: String): String? =
        c.attributes.firstOrNull { it.path == path }?.value

    private fun splitBundle(arms: ArmSet?, forward: ExperimentRun): String {
        if (arms == null) return "No bundle-splitting arm was possible: the arms cannot run."
        val names = plan.hypotheses.flatMap { it.members }.filter { it.startsWith("env.") }
            .map { it.removePrefix("env.") }
            .filter { n -> green.attributes.any { it.path == "env.$n" } }.toSet()
        val base = ArmBase(green, options.command, Environment.LOCAL, options.digests, names)
        return when (val split = Splits.propose(base, arms)) {
            is SplitResult.NoSplit ->
                "No bundle-splitting arm was possible: ${split.reason.substringBefore(" (")}; " +
                    "the other members travel with the base image."

            is SplitResult.Proposed -> {
                val ran = mutableListOf<String>()
                for ((k, proposal) in split.proposals.take(MAX_SPLITS).withIndex()) {
                    val built = Arms.pair(base, emptyList(), proposal.interventions)
                    val set = (built as? ArmSetResult.Built)?.arms ?: continue
                    val id = "${forward.id}.s${k + 1}"
                    try {
                        val e = experimentOf(id, proposal.members, green.label, red.label)
                        val run = runExperiment(e, set, "split", proposal.members, id)
                        ran += "${proposal.members.joinToString("+")} ${run.outcome?.id} " +
                            "(${run.treatment.failures} of ${run.treatment.trials.size})"
                    } catch (h: Halt) {
                        ran += "stopped: ${h.detail}"
                        break
                    }
                }
                "Bundle-splitting arms proposed: ${split.proposals.size}; run: " +
                    ran.joinToString("; ").ifEmpty { "none" } +
                    ". The bundle stays a bundle: a split result does not name one member."
            }
        }
    }

    private fun p(run: ExperimentRun) = FixedPoint.format(run.p?.micro() ?: 0)

    private fun confirmedBy(reverse: ExperimentRun?, replicate: ExperimentRun?): String =
        if (reverse != null) {
            "back from ${reverse.control.failures} of ${reverse.control.trials.size} to " +
                "${reverse.treatment.failures} of ${reverse.treatment.trials.size} when the " +
                "failing side was set to the passing values (p ${p(reverse)})"
        } else {
            "a second run agreed (${replicate?.treatment?.failures} of " +
                "${replicate?.treatment?.trials?.size}, p ${replicate?.let { p(it) }})"
        }

    private fun MeasuredDiff.isolated(primaries: List<String>) =
        changed.isNotEmpty() && changed.all { it in primaries }

    private fun armsDiff(run: ExperimentRun, arms: ArmSet?): MeasuredDiff {
        val measured = run.diff
        val primaries = arms?.primaries.orEmpty()
        if (measured == null || measured.source != "measured") {
            return measured ?: MeasuredDiff("predicted", primaries, emptyList(), emptyList())
        }
        return measured.copy(changed = (measured.changed + primaries).distinct().sorted())
    }

    private fun mechanism(run: ExperimentRun, members: List<String>): String? {
        val c = run.control.capsule ?: return null
        val t = run.treatment.capsule ?: return null
        val owned = plan.hypotheses.filter { it.id in members }.flatMap { it.members }.toSet()
        val candidate = Ranker.rank(c, t).candidates.firstOrNull { cand ->
            cand.path in owned &&
                (cand.matches.isNotEmpty() || cand.evidence.any { it.kind == EvidenceKind.PROBE })
        } ?: return null
        return candidate.matches.firstOrNull()?.let { "${it.ruleId}: ${it.mechanism}" }
            ?: "a probe differs for ${candidate.path}"
    }

    private fun runSubset(subset: List<String>, id: String, kind: String): ExperimentRun? {
        val e = experimentOf(id, subset, green.label, red.label)
        val arms = build(e, green, red) ?: return null
        if (!arms.executable) return null
        val run = runExperiment(e, arms, kind, subset, id)
        when (run.outcome) {
            Outcome.SUPPORTED -> update(subset, true)
            Outcome.REFUTED -> update(subset, false)
            else -> Unit
        }
        return run
    }

    private fun runReverse(minimal: List<String>, id: String): ExperimentRun? {
        val e = experimentOf(id, minimal, red.label, green.label)
        val arms = build(e, red, green) ?: return null
        if (!arms.executable) return null
        return runExperiment(e, arms, "reverse", minimal, id, red.label, green.label)
    }

    private fun experimentOf(
        id: String,
        flips: List<String>,
        passing: String,
        failing: String,
    ): Experiment {
        val ids = belief.masses.map { it.id }
        val table = OutcomeTable.binary(ids, flips.toSet(), plan.trials.power, plan.options.alpha)
        val label = "$passing with ${flips.joinToString(", ")} set to $failing values"
        return Experiment(
            id,
            InterventionClass.AUTOMATIC_LOCAL,
            flips,
            emptyList(),
            Cost(0, 0, 0),
            table,
            listOf(Arm("$passing unchanged", emptyList()), Arm(label, flips)),
            "",
        )
    }

    // #endregion

    // #region running

    private fun sizing(): Sizing {
        val slots = ((options.budget.maxMinutes * 60 - spentSeconds) / secondsPerTrial)
            .coerceAtLeast(0)
        val minutes = slots * options.trialMinutes
        val left = Budget((options.budget.maxTrials - spentTrials).coerceAtLeast(0), minutes)
        return TrialSizing.fromRate(
            plan.options.rate,
            left,
            options.trialMinutes,
            SolveCertificate.alphaPerTest(plan.options.alpha, options.maxRounds),
            plan.options.power,
        )
    }

    private fun runExperiment(
        e: Experiment,
        arms: ArmSet,
        kind: String,
        flips: List<String>,
        id: String,
        passing: String = green.label,
        failing: String = red.label,
    ): ExperimentRun {
        val size = sizing()
        if (size !is Sizing.Sufficient) {
            throw Halt(StuckWhy.BUDGET, (size as Sizing.Insufficient).detail)
        }
        val shape = Frame.environments(passing, failing)
        val spec = ExperimentSpec.from(e.copy(id = id), shape, arms, size)
        extra["experiments/$id.json"] = spec.json()
        files.write("$dir/experiments/$id.json", spec.json())
        return execute(
            id,
            kind,
            flips,
            spec,
            arms.left,
            arms.right,
            requireNotNull(spec.rule).perArm,
            arms.diff,
        )
    }

    private fun execute(
        id: String,
        kind: String,
        flips: List<String>,
        spec: ExperimentSpec?,
        left: RunConfig,
        right: RunConfig,
        perArm: Int,
        predicted: ArmDiff?,
        cap: Int = perArm,
    ): ExperimentRun {
        val folders = listOf("control", "treatment")
        val capsules = listOf(left, right).mapIndexed { i, config ->
            place(config, "$id-${folders[i]}")
        }
        val records = listOf(mutableListOf<TrialRecord>(), mutableListOf())
        val seconds = longArrayOf(0, 0)
        val seed = TrialSchedule.seed(green.hash(), red.hash(), id)
        val schedule = TrialSchedule(seed, cap)
        for (i in 0 until cap) {
            if (i >= perArm && records[1].any { it.status == TrialStatus.FAIL }) break
            if (spentTrials + 2 > options.budget.maxTrials ||
                spentSeconds >= options.budget.maxMinutes * 60
            ) {
                throw Halt(
                    StuckWhy.BUDGET,
                    "the budget of ${options.budget.maxTrials} trials and " +
                        "${options.budget.maxMinutes} minutes ran out during $id",
                )
            }
            val sides = if (schedule.treatmentFirst[i]) listOf(1, 0) else listOf(0, 1)
            for (side in sides) {
                val config = if (side == 0) left else right
                val raw = trial(config, i, id)
                records[side] += classify(i, raw)
                seconds[side] += raw.seconds
                spentSeconds += raw.seconds
                spentTrials++
            }
        }
        val control = ArmRun("control", left.label, records[0], seconds[0], capsules[0])
        val treatment = ArmRun("treatment", right.label, records[1], seconds[1], capsules[1])
        val reverse = kind == "reverse" || kind == "removal"
        val more = if (reverse) control.counts else treatment.counts
        val less = if (reverse) treatment.counts else control.counts
        val rule = spec?.rule
        val outcome = rule?.evaluate(more, less)
        val p = ExperimentRun.pValue(more, less)
        val run = ExperimentRun(
            runs.size + 1, id, kind, flips, spec, control, treatment, outcome, p,
            diffOf(capsules[0], capsules[1], predicted, right),
            TrialSchedule(seed, records[0].size),
        )
        runs += run
        if (run.void) {
            throw Halt(
                StuckWhy.EXECUTOR,
                "experiment $id is void: the treatment capsule does not show the requested " +
                    "value of ${run.diff!!.unreached.joinToString(", ")}",
            )
        }
        return run
    }

    private fun place(config: RunConfig, folder: String): Capsule? {
        val repro = config.reproduction
            ?: throw Halt(StuckWhy.EXECUTOR, "${config.label} has no container to run")
        val path = "$dir/arms/$folder"
        for ((name, text) in repro.files()) {
            extra["arms/$folder/$name"] = text
            if (!files.write("$path/$name", text)) {
                throw Halt(StuckWhy.EXECUTOR, "cannot write $path/$name")
            }
        }
        val problem = executor.prepare(config, path)
        if (problem != null) throw Halt(StuckWhy.EXECUTOR, "${config.label}: $problem")
        val capsule = captured.getOrPut(repro.tag + " " + repro.flags.joinToString(" ")) {
            executor.capture(config)
        }
        capsule?.let { extra["arms/$folder/capsule.json"] = it.canonical() }
        return capsule
    }

    private fun trial(config: RunConfig, index: Int, id: String): RawTrial {
        var raw = executor.trial(config, index)
        if (raw.infra != null) raw = executor.trial(config, index)
        raw.infra?.let { throw Halt(StuckWhy.EXECUTOR, "trial $index of $id: $it") }
        return raw
    }

    private fun classify(index: Int, raw: RawTrial): TrialRecord {
        val status = if (options.failWhen.failed(raw)) TrialStatus.FAIL else TrialStatus.PASS
        val tail = raw.output.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim()
            .map { if (it.code in 32..126) it else '?' }.joinToString("").take(TAIL)
        return TrialRecord(index, status, raw.exit, tail)
    }

    private fun diffOf(
        c: Capsule?,
        t: Capsule?,
        predicted: ArmDiff?,
        requested: RunConfig,
    ): MeasuredDiff? {
        if (c != null && t != null) {
            val d = CapsuleDiff.diff(c, t)
            val unreached = unreached(t, requested)
            val derived = derived(requested)
            val (consequence, changed) = d.changes.map { it.path }.partition { it in derived }
            return MeasuredDiff(
                if (unreached.isEmpty()) MeasuredDiff.MEASURED else MeasuredDiff.VOID,
                changed.sorted(),
                (d.ignored.map { it.path } + consequence).sorted(),
                d.probes.filter { it.status == ProbeChangeStatus.DIFFERENT }.map { it.id }.sorted(),
                (c.attributes.map { it.path } + t.attributes.map { it.path }).toSet().size,
                unreached,
            )
        }
        return predicted?.let {
            MeasuredDiff(MeasuredDiff.PREDICTED, it.changed, it.ignored, emptyList())
        }
    }

    /** Attributes the arm changes by carrying out the edit; they are not a second cause. */
    private fun derived(requested: RunConfig): Set<String> {
        val edited = requested.edited.values.flatten()
        val locale = edited.any { it == "env.LANG" || it.startsWith("env.LC_") }
        val derived = mutableSetOf<String>()
        if (locale) derived += "locale.installed"
        if ("tool.coreutils.flavor" in edited) derived += "tool.coreutils.version"
        return derived
    }

    private fun unreached(captured: Capsule, requested: RunConfig): List<String> {
        fun value(c: Capsule, path: String) = c.attributes.firstOrNull { it.path == path }?.value
        return requested.edited.values.flatten().distinct().sorted()
            .filter { value(captured, it) != value(requested.capsule, it) }
    }

    // #endregion

    companion object {
        const val TAIL = 120
        const val MAX_SPLITS = 3
        const val MAX_MEMBER_RUNS = 6
        const val SUFFICIENT = "sufficient"
        const val NECESSARY = "necessary"
        const val NOT_SHOWN = "not-shown"
        const val UNTESTED = "untested"
    }
}

private fun Plan.manualIds(): List<String> =
    experiments.filter { it.decision == Decision.MANUAL }.flatMap { it.experiment.flips }
