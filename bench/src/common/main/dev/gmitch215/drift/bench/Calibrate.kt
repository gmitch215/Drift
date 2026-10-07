package dev.gmitch215.drift.bench

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.obj

/**
 * The inputs of a run: the capsule pairs with probes ([Inputs.probed], the fit and the primary
 * evaluation) and the first attribute-only pairs ([Inputs.original], for the before numbers).
 */
class Inputs(val probed: Lab, val original: Lab)

/** The result of one calibration run: both files as canonical JSON text. */
class Calibration(val fit: String, val evaluation: String, val chosen: Model)

object Calibrate {
	/** A model replaces the best so far only by cutting out-of-fold Brier by this share. */
	const val BRIER_GAIN_PERCENT = 2

	private class Row(val model: Model, val covered: Long, val all: Long, val mrr: Long)

	fun select(obs: List<Observed>): Pair<Model, List<JsonObject>> {
		val rows = Models.all.map { m ->
			val cv = Evaluate.crossValidated(obs, m)
			Row(m, Evaluate.brierCovered(cv), Evaluate.brier(cv), Evaluate.mrr(cv))
		}
		var best = rows.first()
		val verdicts = mutableListOf<JsonObject>()
		for (row in rows) {
			val wins = row !== best &&
				row.covered * 100 <= best.covered * (100 - BRIER_GAIN_PERCENT) &&
				row.mrr >= best.mrr
			if (wins) best = row
			verdicts += obj(
				"id" to JsonString(row.model.id),
				"cv_brier" to JsonInt(row.covered),
				"cv_brier_all_rankings" to JsonInt(row.all),
				"cv_mrr" to JsonInt(row.mrr),
				"replaces_incumbent" to JsonBool(wins),
			)
		}
		return best.model to verdicts
	}

	private fun inputJson(lab: Lab, obs: List<Observed>): JsonObject = obj(
		"input_sha256" to JsonString(lab.inputSha256),
		"scenarios" to JsonInt(obs.size.toLong()),
		"scenarios_with_probe_difference" to JsonInt(obs.count { it.probes.isNotEmpty() }.toLong()),
		"scenarios_with_probe_candidate" to
			JsonInt(obs.count { o -> o.ranked.any { it.probe } }.toLong()),
		"scenarios_with_rule_match" to JsonInt(obs.count { it.rules.isNotEmpty() }.toLong()),
		"candidates" to JsonInt(obs.sumOf { it.ranked.size }.toLong()),
		"causes" to JsonInt(obs.sumOf { o -> o.ranked.count { it.label } }.toLong()),
		"candidates_with_probe" to JsonInt(obs.sumOf { o -> o.ranked.count { it.probe } }.toLong()),
		"candidates_with_rule" to
			JsonInt(obs.sumOf { o -> o.ranked.count { it.rules.isNotEmpty() } }.toLong()),
	)

	fun run(inputs: Inputs, seed: Long): Calibration {
		val lab = inputs.probed
		val obs = lab.observeDev()
		val stripped = lab.withoutProbes().observeDev()
		val original = inputs.original.observeDev()
		val (chosen, verdicts) = select(obs)
		val provenance = obj(
			"inputs" to obj(
				"probed" to inputJson(lab, obs),
				"probes_removed" to inputJson(lab.withoutProbes(), stripped),
				"original" to inputJson(inputs.original, original),
			),
			"seed" to JsonInt(seed),
			"scenario_list_sha256" to JsonString(lab.scenarioListSha256),
			"dev_ids_sha256" to JsonString(lab.devIdsSha256),
			"dev_scenarios" to JsonInt(obs.size.toLong()),
			"none_grid" to JsonArray(Fit.NONE_GRID.map { JsonInt(it) }),
			"laplace" to JsonInt(Fit.LAPLACE.toLong()),
			"folds" to JsonInt(Folds.K.toLong()),
			"fold_rule" to JsonString("first 4 bytes of sha256(\"fold:\" + id) mod ${Folds.K}"),
			"selection_rule" to JsonString(
				"start from the shipped model and walk the others in order; one replaces the " +
					"best so far only if its out-of-fold Brier over the rankings that print a " +
					"probability is at least $BRIER_GAIN_PERCENT percent lower and its " +
					"out-of-fold MRR is not lower",
			),
			"method" to JsonString(
				"dimension prior: smoothed odds of a candidate being the cause against the " +
					"overall odds; calibration: isotonic regression (pool adjacent violators) " +
					"on the summed log-odds with smoothed block rates",
			),
		)
		return Calibration(
			encode(fit(obs, chosen, verdicts, provenance)),
			encode(evaluation(obs, stripped, original, lab.reversed().observeDev(), provenance)),
			chosen,
		)
	}

	private fun encode(o: JsonObject) = CanonicalJson.encode(o) + "\n"

	private fun tableJson(t: Table): JsonObject = obj(
		"dimensions" to JsonObject(
			t.dimensions.entries.associate { it.key.id to JsonInt(it.value) },
		),
		"probe" to JsonInt(t.probe),
		"rules" to JsonObject(t.rules.entries.associate { it.key.id to JsonInt(it.value) }),
	)

	private fun fit(
		obs: List<Observed>,
		chosen: Model,
		verdicts: List<JsonObject>,
		provenance: JsonObject,
	): JsonObject {
		val table = chosen.table?.invoke(obs) ?: Table.handSet
		val map: JsonValue = if (chosen.mapped) {
			val iso = Fit.isotonic(obs, table, chosen.normalized)
			JsonArray(iso.steps.map { obj("from" to JsonInt(it.first), "p" to JsonInt(it.second)) })
		} else {
			JsonNull
		}
		val cv = Evaluate.crossValidated(obs, chosen)
		val folds = (0 until Folds.K).map { f ->
			val held = cv.filter { Folds.of(it.o.scenario.id) == f }
			obj(
				"fold" to JsonInt(f.toLong()),
				"scenarios" to JsonInt(held.size.toLong()),
				"brier" to JsonInt(Evaluate.brierCovered(held)),
				"mrr" to JsonInt(Evaluate.mrr(held)),
			)
		}
		val probability: JsonValue = chosen.none?.let { fitNone ->
			obj(
				"covers" to JsonArray(listOf(JsonString("attribute"))),
				"none" to JsonInt(fitNone(obs)),
				"none_by_fold" to JsonArray(
					(0 until Folds.K).map { f ->
						JsonInt(fitNone(obs.filter { Folds.of(it.scenario.id) != f }))
					},
				),
			)
		} ?: JsonNull
		return obj(
			"schema" to JsonInt(1),
			"synthetic" to JsonBool(true),
			"provenance" to provenance,
			"probability" to probability,
			"selection" to obj(
				"chosen" to JsonString(chosen.id),
				"normalized" to JsonBool(chosen.normalized),
				"mapped" to JsonBool(chosen.mapped),
				"fitted_weights" to JsonBool(chosen.fitsWeights),
				"models" to JsonArray(verdicts),
			),
			"evidence_fit" to obj(
				"applied" to JsonBool(chosen === Models.evidenceFit),
				"table" to tableJson(Fit.evidence(obs)),
				"probe_by_fold" to JsonArray(
					(0 until Folds.K).map { f ->
						JsonInt(Fit.evidence(obs.filter { Folds.of(it.scenario.id) != f }).probe)
					},
				),
			),
			"table" to tableJson(table),
			"hand_set" to tableJson(Table.handSet),
			"calibration" to map,
			"fold_results" to JsonArray(folds),
		)
	}

	private fun section(obs: List<Observed>): JsonObject = obj(
		"scenarios" to JsonArray(obs.map(::scenarioJson)),
		"versus_random" to Evaluate.versusRandom(obs),
		"models" to JsonArray(Models.everything.map { Evaluate.model(obs, it) }),
	)

	private fun evaluation(
		obs: List<Observed>,
		stripped: List<Observed>,
		original: List<Observed>,
		reversed: List<Observed>,
		provenance: JsonObject,
	): JsonObject = obj(
		"schema" to JsonInt(2),
		"synthetic" to JsonBool(true),
		"provenance" to provenance,
		"fold_of" to JsonObject(
			obs.associate { it.scenario.id to JsonInt(Folds.of(it.scenario.id).toLong()) },
		),
		"probed" to section(obs),
		"probes_removed" to section(stripped),
		"original" to section(original),
		"probe_sweep" to Evaluate.probeSweep(obs),
		"reversed" to obj(
			"note" to JsonString(
				"the same pairs with green and red swapped; labels mean nothing here, so only " +
					"label-free facts are kept",
			),
			"honesty" to Evaluate.honesty(Evaluate.entries(reversed)),
			"rule_matches" to JsonObject(
				reversed.filter { it.rules.isNotEmpty() }.associate { o ->
					o.scenario.id to JsonArray(o.rules.map { JsonString(it) })
				},
			),
		),
	)

	private fun scenarioJson(o: Observed): JsonObject = obj(
		"id" to JsonString(o.scenario.id),
		"class" to JsonString(o.scenario.klass),
		"cause" to (o.scenario.cause?.path?.let { JsonString(it) } ?: JsonNull),
		"manual" to JsonBool(!o.scenario.controllable),
		"changed" to JsonArray(o.changed.map { JsonString(it) }),
		"probes" to JsonArray(o.probes.map { JsonString(it) }),
		"rules" to JsonArray(o.rules.map { JsonString(it) }),
		"ranked" to JsonArray(
			o.ranked.map {
				obj(
					"path" to JsonString(it.path),
					"tier" to JsonString(it.tier.name.lowercase()),
					"score" to JsonInt(it.rankerScore),
					"probe" to JsonBool(it.probe),
					"rules" to JsonInt(it.rules.size.toLong()),
					"verified_rule" to JsonBool(it.verifiedRule),
					"bundled" to JsonBool(it.bundled),
					"cause" to JsonBool(it.label),
					"decoy" to JsonBool(it.decoy),
				)
			},
		),
	)
}
