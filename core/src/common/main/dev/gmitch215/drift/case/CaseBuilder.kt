package dev.gmitch215.drift.case

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Run
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.rank.Ranking

/**
 * Builds the case directory for one comparison from the engine's full structured results.
 * Nothing here depends on time, paths or the host, so the bytes are the same on every target.
 * The `results/` file is empty: outcomes of experiments are ingested later (6d3).
 */
object CaseBuilder {
	fun build(
		name: String,
		passing: Capsule,
		failing: Capsule,
		ranking: Ranking,
		plan: Plan,
		runs: List<Run> = emptyList(),
	): CaseFile {
		val contents = buildList {
			add(capsuleRecord("passing", passing))
			add(capsuleRecord("failing", failing))
			runs.sortedBy { it.id }.forEach { add(runRecord(it)) }
		}.mapIndexed { i, c -> JsonObject(c.fields + ("seq" to JsonInt(i + 1L))) }
		val (records, head) = Chain.seal(contents)
		val files = linkedMapOf<String, String>()
		fun put(path: String, json: JsonObject) {
			files[path] = CanonicalJson.encode(json)
		}
		put(
			CaseFile.CASE,
			obj(
				"schema" to JsonInt(CaseFile.SCHEMA),
				"name" to JsonString(name),
				"frame" to plan.frame.toJson(),
				"observations" to JsonInt(records.size.toLong()),
				"head" to JsonString(head),
			),
		)
		put("environments/passing.json", passing.toJson())
		put("environments/failing.json", failing.toJson())
		records.forEachIndexed { i, r -> put("observations/${pad(i + 1)}.json", r) }
		put("hypotheses/ranking.json", ranking.toJson())
		put(
			"hypotheses/prior.json",
			obj(
				"hypotheses" to JsonArray(plan.hypotheses.map { it.toJson() }),
				"prior" to plan.prior.toJson(),
			),
		)
		put("experiments/plan.json", plan.toJson())
		put("results/index.json", obj("results" to JsonArray(emptyList())))
		put(
			"eliminations/excluded.json",
			obj(
				"candidates" to JsonArray(plan.excluded.map { it.toJson() }),
				"runs" to ranking.toJson().fields.getValue("excluded"),
			),
		)
		files[CaseFile.MANIFEST] = CaseFile.manifest(files)
		return CaseFile(files)
	}

	private fun capsuleRecord(role: String, capsule: Capsule) = obj(
		"kind" to JsonString("capsule"),
		"role" to JsonString(role),
		"label" to JsonString(capsule.label),
		"capsule" to JsonString(capsule.hash()),
		"file" to JsonString("environments/$role.json"),
	)

	private fun runRecord(run: Run): JsonObject {
		val capsule = run.capsule?.let { JsonString(it.hash()) } ?: JsonNull
		return JsonObject(
			run.toJson().fields + mapOf("kind" to JsonString("run"), "capsule" to capsule),
		)
	}

	private fun pad(n: Int) = n.toString().padStart(4, '0')
}
