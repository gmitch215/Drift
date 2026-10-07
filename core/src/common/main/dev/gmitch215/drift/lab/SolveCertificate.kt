package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.CaseBuilder
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.Chain
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Plan
import dev.gmitch215.drift.rank.Ranking

/**
 * Turns a solve into a case directory: the 6b2 files, the preregistered specs, the arms, a
 * hash-chained `results/` that continues the observation chain, and `certificate.json`. 6d4
 * packs these files into a `.driftcase` and re-derives the posteriors and statistics from them.
 */
object SolveCertificate {
	const val FORMAT = "drift-solve-1"
	const val CERTIFICATE = "certificate.json"

	class Confirmed(
		val forward: ExperimentRun,
		val minimal: List<String>,
		val tested: Int,
		val unresolved: Int,
		val order: List<List<String>>,
		val reverse: ExperimentRun?,
		val replicate: ExperimentRun?,
		val diff: MeasuredDiff,
	)

	val honesty = listOf(
		"Experiments ran in containers built from the capsules, not on the original host.",
		"A bundle is never one cause: a CONFIRMED EFFECT (bundle) names every attribute that " +
			"differed between the arms and says which were not isolated.",
		"Candidate weights and the prior are hand-set and uncalibrated; the posteriors order " +
			"experiments and are not measurements.",
		"Only experiments that ran are recorded, each with every trial; nothing here was " +
			"inferred from an experiment that did not run.",
		"verify can re-derive hashes, posteriors and test statistics from this data; it does " +
			"not re-run the experiments.",
	)

	fun build(
		name: String,
		green: Capsule,
		red: Capsule,
		ranking: Ranking,
		plan: Plan,
		runs: List<ExperimentRun>,
		extra: Map<String, String>,
		posteriors: List<Pair<String, Belief>>,
		skipped: List<Pair<String, List<String>>>,
		blocked: Map<String, String>,
		confirmation: Confirmed?,
		verdict: SolveVerdict,
		baseline: Reproduction?,
		options: SolveOptions,
		trials: Int,
		seconds: Long,
	): CaseFile {
		val base = CaseBuilder.build(name, green, red, ranking, plan)
		val files = LinkedHashMap(base.files)
		files.putAll(extra)
		val head = CanonicalJson.parse(files.getValue(CaseFile.CASE)).let {
			(it as JsonObject).require("head").string()
		}
		val (records, resultsHead) = seal(runs, head)
		val index = records.mapIndexed { i, r ->
			val file = resultPath(i + 1)
			files[file] = CanonicalJson.encode(r)
			obj(
				"seq" to JsonInt(i + 1L),
				"id" to (r.fields.getValue("experiment")),
				"role" to (r.fields.getValue("role")),
				"outcome" to (r.fields.getValue("outcome")),
				"file" to JsonString(file),
				"hash" to (r.fields.getValue("hash")),
			)
		}
		files["results/index.json"] = CanonicalJson.encode(obj("results" to JsonArray(index)))
		val certificate = obj(
			"schema" to JsonInt(1),
			"format" to JsonString(FORMAT),
			"name" to JsonString(name),
			"frame" to plan.frame.toJson(),
			"command" to JsonString(options.command),
			"failWhen" to JsonString(options.failWhen.text()),
			"budget" to obj(
				"maxTrials" to JsonInt(options.budget.maxTrials.toLong()),
				"maxMinutes" to JsonInt(options.budget.maxMinutes),
				"trials" to JsonInt(trials.toLong()),
				"seconds" to JsonInt(seconds),
			),
			"verdict" to verdict.toJson(),
			"conditions" to (confirmation?.let { conditions(it, verdict) } ?: JsonNull),
			"posteriors" to JsonArray(
				posteriors.map { (after, belief) ->
					obj("after" to JsonString(after), "belief" to belief.toJson())
				},
			),
			"experiments" to JsonArray(runs.mapIndexed { i, r -> summary(r, resultPath(i + 1)) }),
			"skipped" to JsonArray(
				skipped.map { (id, why) ->
					obj(
						"experiment" to JsonString(id),
						"blockers" to JsonArray(why.map(::JsonString)),
					)
				},
			),
			"blocked" to JsonObject(blocked.mapValues { JsonString(it.value) }),
			"notMirrored" to JsonArray(notMirrored(baseline)),
			"chain" to obj(
				"observationsHead" to JsonString(head),
				"resultsHead" to JsonString(resultsHead),
				"results" to JsonInt(records.size.toLong()),
			),
			"honesty" to JsonArray(honesty.map(::JsonString)),
		)
		files[CERTIFICATE] = CanonicalJson.encode(certificate)
		files[CaseFile.MANIFEST] = CaseFile.manifest(files)
		return CaseFile(files)
	}

	fun resultPath(n: Int) = "results/" + n.toString().padStart(4, '0') + ".json"

	/** Chains the run records from [head], the head of the observation chain. */
	fun seal(runs: List<ExperimentRun>, head: String): Pair<List<JsonObject>, String> {
		var prev = head
		val sealed = runs.map {
			val content = it.toJson()
			val hash = Chain.link(prev, content)
			val record = JsonObject(
				content.fields + mapOf("prev" to JsonString(prev), "hash" to JsonString(hash)),
			)
			prev = hash
			record
		}
		return sealed to prev
	}

	private fun summary(r: ExperimentRun, file: String): JsonObject = obj(
		"seq" to JsonInt(r.seq.toLong()),
		"id" to JsonString(r.id),
		"role" to JsonString(r.kind),
		"flips" to JsonArray(r.flips.map(::JsonString)),
		"control" to counts(r.control),
		"treatment" to counts(r.treatment),
		"outcome" to (r.outcome?.let { JsonString(it.id) } ?: JsonNull),
		"p" to (r.p?.let { ExperimentRun.pJson(it) } ?: JsonNull),
		"specSha256" to (r.spec?.let { JsonString(it.sha256()) } ?: JsonNull),
		"ruleSha256" to (r.spec?.rule?.let { JsonString(it.sha256()) } ?: JsonNull),
		"rule" to (r.spec?.rule?.toJson() ?: JsonNull),
		"diff" to (r.diff?.toJson() ?: JsonNull),
		"file" to JsonString(file),
	)

	private fun counts(a: ArmRun) = obj(
		"label" to JsonString(a.label),
		"failures" to JsonInt(a.failures.toLong()),
		"trials" to JsonInt((a.failures + a.passes).toLong()),
		"errors" to JsonInt(a.errors.toLong()),
	)

	private fun conditions(c: Confirmed, verdict: SolveVerdict): JsonObject {
		val confirmed = verdict.kind == VerdictKind.CONFIRMED || verdict.kind == VerdictKind.BUNDLE
		val agree = c.reverse?.outcome == Outcome.SUPPORTED ||
			c.replicate?.outcome == Outcome.SUPPORTED
		return obj(
			"confirmed" to JsonBool(confirmed),
			"effect" to obj(
				"experiment" to JsonString(c.forward.id),
				"outcome" to (c.forward.outcome?.let { JsonString(it.id) } ?: JsonNull),
				"diff" to (c.diff.toJson()),
			),
			"agreement" to obj(
				"kind" to JsonString(
					when {
						c.reverse != null -> "reverse"
						c.replicate != null -> "replication"
						else -> "none"
					},
				),
				"experiment" to (
					(c.reverse ?: c.replicate)?.let { JsonString(it.id) } ?: JsonNull
					),
				"agrees" to JsonBool(agree),
			),
			"minimalSet" to obj(
				"set" to JsonArray(c.minimal.map(::JsonString)),
				"tested" to JsonInt(c.tested.toLong()),
				"unresolved" to JsonInt(c.unresolved.toLong()),
				"order" to JsonArray(c.order.map { JsonArray(it.map(::JsonString)) }),
			),
			"mechanism" to obj(
				"state" to JsonString(if (verdict.mechanism != null) "agrees" else "unexplained"),
				"text" to (verdict.mechanism?.let { JsonString(it) } ?: JsonNull),
			),
		)
	}

	private fun notMirrored(r: Reproduction?): List<JsonObject> =
		r?.entries?.filter { it.status == Status.NOT_MIRRORED }?.map {
			obj("path" to JsonString(it.path), "reason" to JsonString(it.detail))
		}.orEmpty()

	/** Problems of the results chain and certificate of [case]; empty when they hold. */
	fun check(case: CaseFile): List<String> {
		val text = case.text(CERTIFICATE) ?: return listOf("missing case file: $CERTIFICATE")
		return try {
			val certificate = CanonicalJson.parse(text) as JsonObject
			val chain = certificate.require("chain") as JsonObject
			var prev = (chain.require("observationsHead")).string()
			val paths = case.files.keys.filter { it.startsWith("results/0") }.sorted()
			val problems = mutableListOf<String>()
			for ((i, path) in paths.withIndex()) {
				val record = case.json(path)
				val before = (record["prev"] as? JsonString)?.value
				val stored = (record["hash"] as? JsonString)?.value
				val seq = (record["seq"] as? JsonInt)?.value
				val broken = when {
					seq != i + 1L -> "sequence number is not ${i + 1}"

					before != prev -> "previous hash does not match"

					stored != Chain.link(prev, Chain.content(record)) ->
						"content does not match its hash"

					else -> null
				}
				if (broken != null) {
					return problems + "results chain broken at entry $i ($path): $broken"
				}
				prev = stored ?: ""
			}
			val head = chain.require("resultsHead").string()
			val n = (chain.require("results") as JsonInt).value
			if (prev != head || n != paths.size.toLong()) {
				problems += "results chain head differs from $CERTIFICATE"
			}
			problems
		} catch (e: JsonException) {
			listOf("malformed case file $CERTIFICATE: ${e.message}")
		} catch (e: ClassCastException) {
			listOf("malformed case file $CERTIFICATE")
		}
	}

	/** The certificate of [case] as written. */
	fun certificate(case: CaseFile): JsonObject? =
		case.text(CERTIFICATE)?.let { CanonicalJson.parse(it) as? JsonObject }
}
