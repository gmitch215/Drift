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
import dev.gmitch215.drift.rank.EvidenceKind
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Tier
import dev.gmitch215.drift.rank.Weights

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
		get() = ranked.none { it.probe || it.dimensionProbe || it.rules.isNotEmpty() }
}

/**
 * The dev scenarios and their capsules. Test ids are refused here, so no fit or evaluation
 * path can reach a sealed scenario.
 */
class Lab(
	private val scenarios: List<Scenario>,
	val dev: Set<String>,
	private val capsule: (String) -> String,
	val scenarioListSha256: String,
) {
	val devIds: List<String> = scenarios.map { it.id }.filter { it in dev }.sorted()

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
	)

	/** The same pairs with every probe dropped, to measure what the probes add. */
	fun withoutProbes(): Lab = Lab(
		scenarios,
		dev,
		{ Capsule.parse(capsule(it)).copy(probes = emptyList()).canonical() },
		scenarioListSha256,
	)

	fun observe(ids: List<String>): List<Observed> {
		ids.firstOrNull { it !in dev }?.let { throw SealedException(it) }
		val byId = scenarios.associateBy { it.id }
		return ids.map { id ->
			val s = byId[id] ?: throw SealedException(id)
			val green = Capsule.parse(capsule("$id.green.json"))
			observe(s, green, Capsule.parse(capsule("$id.red.json")))
		}
	}

	fun observeDev(): List<Observed> = observe(devIds)

	companion object {
		fun parse(scenariosText: String, splitText: String, capsule: (String) -> String): Lab {
			val scenarios = (CanonicalJson.parse(scenariosText) as JsonArray).items
				.map { Scenario.fromJson(it) }
			val dev = (CanonicalJson.parse(splitText) as JsonObject).fields.getValue("dev")
				.array().map { it.string() }.toSet()
			return Lab(scenarios, dev, capsule, Sha256.hex(scenariosText))
		}

		fun observe(s: Scenario, green: Capsule, red: Capsule): Observed {
			val ranking = Ranker.rank(green, red)
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
