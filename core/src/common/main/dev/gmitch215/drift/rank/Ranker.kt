package dev.gmitch215.drift.rank

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.ProbeChangeStatus
import dev.gmitch215.drift.history.FailingStep
import dev.gmitch215.drift.history.GreenToRed
import dev.gmitch215.drift.history.Transition
import dev.gmitch215.drift.history.TransitionKind
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Detect
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.Rules
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.spectrum.Element
import dev.gmitch215.drift.spectrum.Ochiai
import dev.gmitch215.drift.symptom.FailureSignature
import dev.gmitch215.drift.symptom.Symptom
import dev.gmitch215.drift.history.Evidence as HistoryEvidence

/**
 * Ranks the changed attributes of a green-to-red pair. Each candidate collects independent
 * evidence items (its attribute change, probes that differ, rule matches, the Ochiai score of
 * its red value over a history) and the combiner adds their log-odds. The weights are hand-set
 * and uncalibrated, see [Weights]. Neither the order of attributes, probes, runs or rules nor a
 * vacuous green changes the result.
 *
 * Tier policy, the single source of truth:
 * - `known`: a matched rule whose symptoms are all verified.
 * - `plausible`: a probe that differs and reads the candidate's path (see [ProbeLinks]), or a
 *   matched rule with any inferred symptom.
 * - `correlated`: a probe that differs in the candidate's dimension but does not read its path,
 *   or a spectrum score of at least [Weights.MIN_CORRELATED].
 * - `difference`: changed, with no support.
 *
 * Never `confirmed`: that needs an experiment.
 *
 * Conditioned on a failure (a `failure` argument that is not null), evidence has a class and the
 * tiers follow it. A probe or rule is mechanistic when its symptom matches the failure (see
 * [ProbeSymptoms] and [FailureSignature]) and observational otherwise:
 * - `known`: a mechanistic rule whose matching symptom is verified.
 * - `plausible`: a mechanistic probe that reads the candidate's path, or a mechanistic rule whose
 *   matching symptoms are all inferred.
 * - `correlated`: an observational probe or rule match, a probe in the candidate's dimension, or
 *   a spectrum score of at least [Weights.MIN_CORRELATED].
 * - `difference`: changed, with no support.
 *
 * An empty `failure` conditions on a failure that shows nothing, so all evidence is
 * observational. A null `failure` keeps the unconditioned policy above.
 */
object Ranker {
    /** Two captures and no history: no spectrum, so no candidate is `correlated`. */
    fun rank(
        green: Capsule,
        red: Capsule,
        rules: List<Rule> = Rules.all,
        failure: List<Symptom>? = null,
    ): Ranking = build(green, red, null, null, null, null, rules, failure)

    /**
     * The transition of [history] with its runs as spectrum input. Greens that did not execute
     * the failing step are left out and listed in [Ranking.excluded]; a transition without
     * both capsules, a red with no failed step or more than [Weights.MAX_RUNS] included runs
     * ranks without a spectrum.
     */
    fun rank(
        transition: Transition,
        history: List<Run>,
        job: String? = null,
        rules: List<Rule> = Rules.all,
        failure: List<Symptom>? = null,
    ): Ranking {
        val result = GreenToRed.of(transition, job)
        val note = TransitionNote(result.kind, result.steps)
        val green = transition.green.capsule
        val red = transition.red.capsule
        if (result.kind == TransitionKind.NO_CAPSULE || green == null || red == null) {
            return Ranking(emptyList(), emptyList(), emptyList(), emptyList(), note)
        }
        val evidence = FailingStep.of(transition.red)?.let { HistoryEvidence.of(history, it) }
        return build(green, red, transition.green, transition.red, evidence, note, rules, failure)
    }

    private fun build(
        green: Capsule,
        red: Capsule,
        greenRun: Run?,
        redRun: Run?,
        history: HistoryEvidence?,
        note: TransitionNote?,
        rules: List<Rule>,
        failure: List<Symptom>?,
    ): Ranking {
        val signature = failure?.let(::FailureSignature)
        val byId = rules.associateBy { it.id }
        val diff = CapsuleDiff.diff(green, red)
        val greenEnv = Detect.env(green, greenRun)
        val redEnv = Detect.env(red, redRun)
        val matches = rules.sortedBy { it.id }
            .mapNotNull { Detect.match(it, greenEnv, redEnv).match }
        val probes = diff.probes.filter { it.status == ProbeChangeStatus.DIFFERENT }
            .map { it.id }.sorted()
        val passes = history?.input?.count { !it.failing } ?: 0
        val failures = history?.input?.count { it.failing } ?: 0
        val enough = passes >= Weights.MIN_PASSES && failures >= 1
        val scored = if (history != null && enough && history.input.size <= Weights.MAX_RUNS) {
            Ochiai.rank(history.input).associateBy { it.element }
        } else {
            null
        }
        val coverage = history?.runs?.minOfOrNull { it.coverage }

        val candidates = diff.changes.mapNotNull { change ->
            val dimension = change.dimension ?: return@mapNotNull null
            val weight = Weights.dimensions.getValue(dimension)
            val items = mutableListOf(
                EvidenceItem(
                    EvidenceKind.ATTRIBUTE,
                    change.path,
                    weight.logOdds,
                    weight.id,
                    signature?.let { EvidenceClass.OBSERVATIONAL },
                ),
            )
            val linked = probes.filter { ProbeLinks.informs(it, change.path) }
            val broad = probes.filter { Dimension.ofProbe(it) == dimension && it !in linked }
            var strong = 0
            for (id in linked) {
                val ref = "probe:$id"
                if (signature == null || ProbeSymptoms.explains(id, signature)) {
                    val cls = signature?.let { EvidenceClass.MECHANISTIC }
                    val first = strong++ == 0
                    items += item(EvidenceKind.PROBE, ref, Weights.probe, cls, first)
                } else {
                    val cls = EvidenceClass.OBSERVATIONAL
                    items += item(EvidenceKind.PROBE, ref, Weights.probeObservational, cls)
                }
            }
            for (id in broad) {
                val cls = signature?.let { EvidenceClass.OBSERVATIONAL }
                items += item(EvidenceKind.PROBE, "probe:$id", Weights.probeDimension, cls)
            }
            val mine = matches.filter { m -> m.evidence.any { it.ref == change.path } }
            val ruleBases = mutableListOf<Basis>()
            for (m in mine) {
                val ref = "rule:${m.ruleId}"
                val basis =
                    if (signature == null) m.basis else matchedBasis(byId[m.ruleId], signature)
                if (basis != null) {
                    ruleBases += basis
                    val cls = signature?.let { EvidenceClass.MECHANISTIC }
                    items += item(EvidenceKind.RULE, ref, Weights.rules.getValue(m.severity), cls)
                } else {
                    val cls = EvidenceClass.OBSERVATIONAL
                    items += item(EvidenceKind.RULE, ref, Weights.ruleObservational, cls)
                }
            }
            val after = change.after
            val s = if (scored != null && after != null) {
                scored[Element(change.path, after)]
            } else {
                null
            }
            val spectrum = s?.let { Spectrum(it.ef, it.nf, it.ep, it.score) }
            if (spectrum != null && coverage != null) {
                val ratio = Weights.spectrumRatio(spectrum.score, coverage)
                items += EvidenceItem(
                    EvidenceKind.SPECTRUM,
                    change.path,
                    FixedPoint.ln(ratio),
                    Weights.spectrum.id,
                )
            }
            val tier = when {
                Basis.VERIFIED in ruleBases -> Tier.KNOWN
                ruleBases.isNotEmpty() || strong > 0 -> Tier.PLAUSIBLE
                mine.isNotEmpty() || linked.isNotEmpty() || broad.isNotEmpty() -> Tier.CORRELATED
                spectrum != null && spectrum.score >= Weights.MIN_CORRELATED -> Tier.CORRELATED
                else -> Tier.DIFFERENCE
            }
            val basis = if (tier == Tier.KNOWN) Basis.VERIFIED else Basis.INFERRED
            Candidate(
                change.path,
                tier,
                items.sumOf { it.weight },
                items.sortedWith(compareBy({ it.kind }, { it.ref })),
                mine,
                basis,
                spectrum,
                coverage,
                null,
            )
        }.sortedWith(compareBy({ -it.tier.ordinal }, { -it.score }, { it.path }))

        val (bundled, bundles) = bundle(candidates)
        val ranked = withProbabilities(bundled)
        val touched = ranked.map { it.path }.toSet()
        return Ranking(
            ranked,
            bundles,
            matches.filter { m -> m.evidence.none { it.ref in touched } },
            history?.excluded.orEmpty().sortedWith(compareBy({ it.runId }, { it.reason })),
            note,
        )
    }

    private fun item(
        kind: EvidenceKind,
        ref: String,
        w: Weight,
        cls: EvidenceClass?,
        counts: Boolean = true,
    ) = EvidenceItem(kind, ref, if (counts) w.logOdds else 0L, w.id, cls)

    private fun matchedBasis(rule: Rule?, failure: FailureSignature): Basis? {
        val hits = rule?.symptoms.orEmpty()
            .filter { it.basis != Basis.RANGE && failure.matchesText(it.text) }
        return when {
            hits.isEmpty() -> null
            hits.any { it.basis == Basis.VERIFIED } -> Basis.VERIFIED
            else -> Basis.INFERRED
        }
    }

    private fun withProbabilities(candidates: List<Candidate>): List<Candidate> {
        val covered = candidates.all { c -> c.evidence.all { it.kind == EvidenceKind.ATTRIBUTE } }
        if (!covered) return candidates
        val shares = Weights.shares(candidates.map { it.score })
        return candidates.mapIndexed { i, c -> c.copy(probability = shares[i]) }
    }

    private fun bundle(sorted: List<Candidate>): Pair<List<Candidate>, List<Bundle>> {
        fun key(c: Candidate) = Triple(
            c.tier,
            c.evidence.filter { it.kind == EvidenceKind.PROBE || it.kind == EvidenceKind.RULE }
                .map { it.ref },
            c.spectrum?.let { listOf(it.ef, it.nf, it.ep) },
        )

        val groups = sorted.groupBy(::key).values.filter { it.size > 1 }
        val bundles = groups.mapIndexed { i, g -> Bundle("bundle-${i + 1}", g.map { it.path }) }
        val member = bundles.flatMap { b -> b.members.map { it to b.id } }.toMap()
        return sorted.map { it.copy(bundle = member[it.path]) } to bundles
    }
}
