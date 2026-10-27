package dev.gmitch215.drift.bench

import dev.gmitch215.drift.diff.CapsuleDiff
import dev.gmitch215.drift.diff.ProbeChangeStatus
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.know.Basis
import dev.gmitch215.drift.know.Severity
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.rank.EvidenceClass
import dev.gmitch215.drift.rank.EvidenceKind
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.rank.Weights
import dev.gmitch215.drift.symptom.Symptom

/**
 * One ranked candidate with the facts the weights act on, and the scenario's label for it.
 * [probe] is a differing probe that reads the candidate's path, [dimensionProbe] one that only
 * shares its dimension.
 */
class Cand(
    val path: String,
    val dimension: Dimension,
    val probe: Boolean,
    val rules: List<Severity>,
    val tier: Tier,
    val bundled: Boolean,
    val rankerScore: Long,
    val probability: Long?,
    val label: Boolean,
    val decoy: Boolean,
    val verifiedRule: Boolean = false,
    val dimensionProbe: Boolean = false,
    val observationalProbe: Boolean = false,
    val mechanisticRule: Boolean = false,
)

/**
 * What the real pipeline produced for one dev scenario: the changed attributes, the probes that
 * differ and the rules that matched (ids), and the ranked candidates.
 */
class Observed(
    val scenario: Scenario,
    val changed: List<String>,
    val ranked: List<Cand>,
    val probes: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
) {
    /**
     * True when the product prints a probability for this ranking: every candidate rests on its
     * attribute change alone. The dev pairs have no history, so no candidate has a spectrum.
     */
    val covered: Boolean
        get() = ranked.none {
            it.probe || it.dimensionProbe || it.observationalProbe || it.rules.isNotEmpty()
        }
}

/**
 * The dev scenarios and their capsules. Test ids are refused here unless the lab was built with
 * [Unsealed], so no fit or evaluation path can reach a sealed scenario before the unseal.
 */
class Lab(
    val scenarios: List<Scenario>,
    val dev: Set<String>,
    private val capsule: (String) -> String,
    val scenarioListSha256: String,
    private val open: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
) {
    val devIds: List<String> =
        scenarios.map { it.id }.filter { it in dev && it !in excluded }.sorted()

    val devIdsSha256: String = Sha256.hex(devIds.joinToString("") { it + "\n" })

    /** The dev capsule texts this lab reads, green then red per id in id order. */
    val inputSha256: String by lazy {
        Sha256.hex(devIds.joinToString("") { capsule("$it.green.json") + capsule("$it.red.json") })
    }

    /** The same pairs read the other way round: red as the passing side, green as the failing. */
    fun reversed(): Lab = Lab(
        scenarios,
        dev,
        {
            capsule(
                if (it.endsWith(".green.json")) {
                    it.removeSuffix(".green.json") + ".red.json"
                } else {
                    it.removeSuffix(".red.json") + ".green.json"
                },
            )
        },
        scenarioListSha256,
        open,
        excluded,
    )

    /** The same pairs with every probe dropped, to measure what the probes add. */
    fun withoutProbes(): Lab = Lab(
        scenarios,
        dev,
        { Capsule.parse(capsule(it)).copy(probes = emptyList()).canonical() },
        scenarioListSha256,
        open,
        excluded,
    )

    /** The same lab without [ids] among its dev ids; they stay dev ids, never test ids. */
    fun excluding(ids: Set<String>): Lab =
        Lab(scenarios, dev, capsule, scenarioListSha256, open, excluded + ids)

    /** [failure] gives the observed failure of an id, or null to rank without one. */
    fun observe(ids: List<String>, failure: (String) -> List<Symptom>? = { null }): List<Observed> {
        ids.firstOrNull { it !in dev && it !in open }?.let { throw SealedException(it) }
        val byId = scenarios.associateBy { it.id }
        return ids.map { id ->
            val s = byId[id] ?: throw SealedException(id)
            val green = Capsule.parse(capsule("$id.green.json"))
            observe(s, green, Capsule.parse(capsule("$id.red.json")), failure(id))
        }
    }

    fun observeDev(): List<Observed> = observe(devIds)

    /** The passing and failing capsules of one scenario; a sealed id is refused. */
    fun pair(id: String): Pair<Capsule, Capsule> {
        if (id !in dev && id !in open) throw SealedException(id)
        return Capsule.parse(capsule("$id.green.json")) to Capsule.parse(capsule("$id.red.json"))
    }

    companion object {
        fun parse(
            scenariosText: String,
            splitText: String,
            unsealed: Unsealed? = null,
            excluded: Set<String> = emptySet(),
            capsule: (String) -> String,
        ): Lab {
            val scenarios = (CanonicalJson.parse(scenariosText) as JsonArray).items
                .map { Scenario.fromJson(it) }
            val dev = (CanonicalJson.parse(splitText) as JsonObject).fields.getValue("dev")
                .array().map { it.string() }.toSet()
            return Lab(
                scenarios,
                dev,
                capsule,
                Sha256.hex(scenariosText),
                unsealed?.test?.toSet().orEmpty(),
                excluded,
            )
        }

        /**
         * Dev scenarios with no committed probed capture: four read a file at a path no capture
         * names (a pin under `/etc/app`) that the bench harness mounts, and the three hosts and
         * name service ones are solvable but have no probed pair yet, because adding them moves
         * the isotonic map in `Weights`, which the committed case fixtures pin. The probed lab
         * leaves them out of the fit and the evaluation; they stay in `dev` and in the capsule
         * results.
         */
        val UNPROBED: Set<String> = setOf(
            "build-checksum-pin-1",
            "build-checksum-pin-2",
            "build-version-pin-1",
            "build-version-pin-2",
            "network-hosts-stale-1",
            "network-hosts-stale-2",
            "network-nsswitch-order-1",
        )

        fun observe(
            s: Scenario,
            green: Capsule,
            red: Capsule,
            failure: List<Symptom>? = null,
        ): Observed {
            val ranking = Ranker.rank(green, red, failure = failure)
            val cause = s.cause?.path
            val decoys = s.decoys.map { it.path }.toSet()
            val inBundle = ranking.bundles.flatMap { it.members }.toSet()
            val ranked = ranking.candidates.map { c ->
                val probeIds = c.evidence.filter { it.kind == EvidenceKind.PROBE }.map { it.id }
                Cand(
                    c.path,
                    requireNotNull(Dimension.of(c.path)),
                    Weights.probe.id in probeIds,
                    c.matches.map { it.severity },
                    c.tier,
                    c.path in inBundle,
                    c.score,
                    c.probability,
                    c.path == cause,
                    c.path in decoys,
                    c.matches.any { it.basis == Basis.VERIFIED },
                    Weights.probeDimension.id in probeIds,
                    Weights.probeObservational.id in probeIds,
                    c.evidence.any {
                        it.kind == EvidenceKind.RULE && it.cls == EvidenceClass.MECHANISTIC
                    },
                )
            }
            val diff = CapsuleDiff.diff(green, red)
            val changed = diff.changes.map { it.path }.sorted()
            val probes = diff.probes.filter { it.status == ProbeChangeStatus.DIFFERENT }
                .map { it.id }
            val rules = (ranking.candidates.flatMap { it.matches } + ranking.unattached)
                .map { it.ruleId }.distinct().sorted()
            return Observed(s, changed, ranked, probes, rules)
        }
    }
}
