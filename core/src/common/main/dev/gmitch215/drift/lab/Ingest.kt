package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.Chain
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonNull
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Information
import dev.gmitch215.drift.plan.Mass
import dev.gmitch215.drift.plan.OutcomeTable
import dev.gmitch215.drift.plan.PValue

/** Why an externally produced result was not accepted; nothing is applied when any is raised. */
sealed interface IngestProblem {
	val file: String

	fun message(): String
}

data class MalformedResult(override val file: String, val detail: String) : IngestProblem {
	override fun message() = "$file: not a result file: $detail"
}

data class UnsupportedResultSchema(override val file: String, val found: Long?) : IngestProblem {
	override fun message() = "$file: unsupported result schema ${found ?: "(none)"}; expected 1"
}

data class UnknownExperiment(override val file: String, val id: String) : IngestProblem {
	override fun message() = "$file: experiment $id is not in the case's plan"
}

data class MissingRule(override val file: String, val id: String, val why: String) :
	IngestProblem {
	override fun message() = "$file: experiment $id has no preregistered rule ($why)"
}

data class RuleMismatch(
	override val file: String,
	val id: String,
	val expected: String,
	val found: String?,
) : IngestProblem {
	override fun message() = "$file: result for $id names rule ${found ?: "(none)"}, but the " +
		"preregistered rule is $expected"
}

data class ArmMismatch(override val file: String, val found: List<String>) : IngestProblem {
	override fun message() = "$file: arms must be control and treatment, found " +
		(found.joinToString(", ").ifEmpty { "none" })
}

data class BadCounts(override val file: String, val detail: String) : IngestProblem {
	override fun message() = "$file: $detail"
}

data class AlreadyIngested(override val file: String, val id: String) : IngestProblem {
	override fun message() = "$file: a result for $id is already in the case"
}

data class CaseNotIntact(override val file: String, val detail: String) : IngestProblem {
	override fun message() = "$file: the case failed its checks: $detail"
}

data class Accepted(
	val id: String,
	val outcome: Outcome,
	val control: Counts,
	val treatment: Counts,
	val p: PValue,
)

sealed interface IngestOutcome {
	data class Done(val case: CaseFile, val accepted: List<Accepted>) : IngestOutcome

	data class Refused(val problems: List<IngestProblem>) : IngestOutcome
}

/**
 * Feeds results someone else produced (a CI workflow variant, a run on another host) into a case
 * through the same decision rule and belief update as `solve`. A result is accepted only when it
 * names a preregistered experiment, the sha256 of that experiment's rule and both arm ids, and
 * its trial counts equal the preregistered count; the rule is derived from the case's plan, never
 * from the result. An ingested result can narrow the case, never confirm it: confirmation needs
 * the minimal-set search and the reverse arm, which are separate preregistered experiments.
 */
object Ingest {
	private class Pre(
		val id: String,
		val flips: List<String>,
		val rule: DecisionRule,
		val power: Long,
		val labels: List<String>,
	)

	fun apply(case: CaseFile, results: Map<String, String>): IngestOutcome {
		val broken = case.check().map { it.message() } +
			(if (hasCertificate(case)) SolveCertificate.check(case) else emptyList())
		if (broken.isNotEmpty()) {
			return IngestOutcome.Refused(listOf(CaseNotIntact("case", broken.first())))
		}
		val plan = try {
			case.json("experiments/plan.json")
		} catch (e: JsonException) {
			return IngestOutcome.Refused(listOf(CaseNotIntact("case", "no plan: ${e.message}")))
		}
		val done = recorded(case).toMutableSet()
		val problems = mutableListOf<IngestProblem>()
		val accepted = mutableListOf<Pair<Pre, Accepted>>()
		for (file in results.keys.sorted()) {
			val step = try {
				one(file, results.getValue(file), plan, done)
			} catch (e: JsonException) {
				Step.Bad(MalformedResult(file, e.message ?: "unreadable"))
			}
			when (step) {
				is Step.Bad -> problems += step.problem

				is Step.Ok -> {
					done += step.pre.id
					accepted += step.pre to step.accepted
				}
			}
		}
		if (problems.isNotEmpty()) return IngestOutcome.Refused(problems)
		return IngestOutcome.Done(extend(case, accepted), accepted.map { it.second })
	}

	private sealed interface Step {
		class Ok(val pre: Pre, val accepted: Accepted) : Step

		class Bad(val problem: IngestProblem) : Step
	}

	private fun hasCertificate(case: CaseFile) = case.text(SolveCertificate.CERTIFICATE) != null

	/** Experiment ids that already have a result in the case. */
	fun recorded(case: CaseFile): Set<String> = case.files.keys
		.filter { it.startsWith("results/0") }.mapNotNull {
			(case.json(it)["experiment"] as? JsonString)?.value
		}.toSet()

	private fun one(file: String, text: String, plan: JsonObject, done: Set<String>): Step {
		val json = CanonicalJson.parse(text) as? JsonObject
			?: return Step.Bad(MalformedResult(file, "not an object"))
		val schema = (json["schema"] as? JsonInt)?.value
		if (schema != 1L) return Step.Bad(UnsupportedResultSchema(file, schema))
		val id = json.require("experiment").string()
		val experiment = plan.require("experiments").array()
			.map { (it as JsonObject).require("experiment") as JsonObject }
			.firstOrNull { it.require("id").string() == id }
			?: return Step.Bad(UnknownExperiment(file, id))
		val rule = rule(plan)
			?: return Step.Bad(
				MissingRule(file, id, "no trial count reaches alpha at the target power"),
			)
		val perArm = rule.perArm.toLong()
		val trials = plan.require("trials") as JsonObject
		val labels = experiment.require("arms").array()
			.map { (it as JsonObject).require("label").string() }
		val flips = experiment.require("flips").array().map { it.string() }
		val pre = Pre(id, flips, rule, trials.require("power").long(), labels)
		if (id in done) return Step.Bad(AlreadyIngested(file, id))
		val named = (json["ruleSha256"] as? JsonString)?.value
		if (named != rule.sha256()) return Step.Bad(RuleMismatch(file, id, rule.sha256(), named))
		val arms = json.require("arms").array().map { it as? JsonObject }
		if (arms.any { it == null }) {
			return Step.Bad(MalformedResult(file, "an arm is not an object"))
		}
		val ids = arms.map { it!!.require("id").string() }
		if (ids != listOf("control", "treatment")) return Step.Bad(ArmMismatch(file, ids))
		val counts = mutableListOf<Counts>()
		for (a in arms) {
			val arm = a!!.require("id").string()
			if (a["failures"] == JsonNull) {
				return Step.Bad(BadCounts(file, "$arm failures are not filled in"))
			}
			val f = a.require("failures").long()
			val n = a.require("trials").long()
			if (n != perArm) {
				val detail = "$arm has $n trials, the preregistered count is $perArm"
				return Step.Bad(BadCounts(file, detail))
			}
			if (f !in 0..n) return Step.Bad(BadCounts(file, "$arm failures $f are outside 0..$n"))
			counts += Counts(f.toInt(), n.toInt())
		}
		val outcome = rule.evaluate(counts[1], counts[0])
		val p = ExperimentRun.pValue(counts[1], counts[0])
		return Step.Ok(pre, Accepted(id, outcome, counts[0], counts[1], p))
	}

	/** The preregistered rule of a plan, or null when no trial count reaches alpha. */
	fun rule(plan: JsonObject): DecisionRule? {
		val trials = plan.require("trials") as JsonObject
		val perArm = (trials["perArm"] as? JsonInt)?.value
		val threshold = (trials["threshold"] as? JsonInt)?.value
		if (perArm == null || threshold == null) return null
		return DecisionRule(
			perArm.toInt(),
			threshold.toInt(),
			trials.require("alpha").long(),
			trials.require("rate").long(),
		)
	}

	/**
	 * One result file per experiment of the case that has no result yet and is not rejected, with
	 * the ids and the preregistered rule hash filled in; `failures` is null until its counts are
	 * entered, so an untouched file is refused rather than ingested as zero failures.
	 */
	fun skeleton(case: CaseFile): Map<String, String> {
		val plan = case.json("experiments/plan.json")
		val rule = rule(plan) ?: return emptyMap()
		val done = recorded(case)
		return plan.require("experiments").array().map { it.obj() }
			.filter { it.require("decision").string() != "rejected" }
			.map { it.require("experiment").obj().require("id").string() }
			.filter { it !in done }
			.associate { id ->
				"$id.json" to CanonicalJson.encode(
					obj(
						"schema" to JsonInt(1),
						"experiment" to JsonString(id),
						"ruleSha256" to JsonString(rule.sha256()),
						"arms" to JsonArray(
							listOf("control", "treatment").map {
								obj(
									"id" to JsonString(it),
									"failures" to JsonNull,
									"trials" to JsonInt(rule.perArm.toLong()),
								)
							},
						),
					),
				)
			}
	}

	private fun beliefOf(json: JsonObject): Belief = Belief(
		json.require("masses").array().map {
			val o = it as JsonObject
			Mass(o.require("id").string(), o.require("micro").long())
		},
	)

	private fun extend(case: CaseFile, accepted: List<Pair<Pre, Accepted>>): CaseFile {
		val files = LinkedHashMap(case.files)
		val certificate = SolveCertificate.certificate(case)
		val head = (case.json(CaseFile.CASE).require("head")).string()
		var prev = certificate?.let {
			((it["chain"] as JsonObject)["resultsHead"] as JsonString).value
		}
			?: head
		var n = files.keys.count { it.startsWith("results/0") }
		val prior = case.json("hypotheses/prior.json").require("prior") as JsonObject
		val posteriors = (certificate?.get("posteriors") as? JsonArray)?.items?.toMutableList()
			?: mutableListOf<JsonValue>(
				obj("after" to JsonString("prior"), "belief" to prior),
			)
		var belief = beliefOf(
			(posteriors.last() as JsonObject).require("belief") as JsonObject,
		)
		val summaries = (certificate?.get("experiments") as? JsonArray)?.items?.toMutableList()
			?: mutableListOf()
		val index = (case.json("results/index.json").require("results").array()).toMutableList()
		for ((pre, a) in accepted) {
			n++
			val file = SolveCertificate.resultPath(n)
			val content = obj(
				"kind" to JsonString("result"),
				"seq" to JsonInt(n.toLong()),
				"experiment" to JsonString(pre.id),
				"role" to JsonString("ingested"),
				"flips" to JsonArray(pre.flips.map(::JsonString)),
				"specSha256" to JsonNull,
				"ruleSha256" to JsonString(pre.rule.sha256()),
				"evaluation" to JsonString("the treatment arm fails more than the control arm"),
				"arms" to JsonArray(
					listOf(a.control to "control", a.treatment to "treatment")
						.mapIndexed { i, (c, r) ->
						obj(
							"role" to JsonString(r),
							"label" to JsonString(pre.labels.getOrElse(i) { r }),
							"failures" to JsonInt(c.failures.toLong()),
							"passes" to JsonInt((c.trials - c.failures).toLong()),
							"errors" to JsonInt(0),
							"seconds" to JsonInt(0),
							"capsule" to JsonNull,
							"trials" to JsonArray(emptyList()),
						)
					},
				),
				"outcome" to JsonString(a.outcome.id),
				"p" to ExperimentRun.pJson(a.p),
				"diff" to JsonNull,
				"source" to JsonString("ingested"),
			)
			val hash = Chain.link(prev, content)
			val record = JsonObject(
				content.fields + mapOf("prev" to JsonString(prev), "hash" to JsonString(hash)),
			)
			prev = hash
			files[file] = CanonicalJson.encode(record)
			index += obj(
				"seq" to JsonInt(n.toLong()),
				"id" to JsonString(pre.id),
				"role" to JsonString("ingested"),
				"outcome" to JsonString(a.outcome.id),
				"file" to JsonString(file),
				"hash" to JsonString(hash),
			)
			summaries += obj(
				"seq" to JsonInt(n.toLong()),
				"id" to JsonString(pre.id),
				"role" to JsonString("ingested"),
				"flips" to JsonArray(pre.flips.map(::JsonString)),
				"control" to counts(pre.labels.getOrElse(0) { "control" }, a.control),
				"treatment" to counts(pre.labels.getOrElse(1) { "treatment" }, a.treatment),
				"outcome" to JsonString(a.outcome.id),
				"p" to ExperimentRun.pJson(a.p),
				"specSha256" to JsonNull,
				"ruleSha256" to JsonString(pre.rule.sha256()),
				"rule" to pre.rule.toJson(),
				"diff" to JsonNull,
				"file" to JsonString(file),
			)
			if (a.outcome != Outcome.INCONCLUSIVE) {
				val ids = belief.masses.map { it.id }
				val table = OutcomeTable.binary(ids, pre.flips.toSet(), pre.power, pre.rule.alpha)
				val wanted = if (a.outcome == Outcome.SUPPORTED) {
					OutcomeTable.REPRODUCED
				} else {
					OutcomeTable.NOT_REPRODUCED
				}
				val branches = Information.branches(belief, table)
				belief = branches.first { it.outcome == wanted }.posterior
				posteriors += obj(
					"after" to JsonString("${pre.id} ${a.outcome.id}"),
					"belief" to belief.toJson(),
				)
			}
		}
		files["results/index.json"] = CanonicalJson.encode(obj("results" to JsonArray(index)))
		val verdict = verdict(case, certificate, summaries, belief)
		val base = certificate?.fields ?: emptyMap()
		val updated = obj(
			"schema" to JsonInt(1),
			"format" to JsonString(SolveCertificate.FORMAT),
			"name" to (base["name"] ?: case.json(CaseFile.CASE).require("name")),
			"frame" to (base["frame"] ?: case.json(CaseFile.CASE).require("frame")),
			"command" to (base["command"] ?: JsonNull),
			"failWhen" to (base["failWhen"] ?: JsonNull),
			"budget" to (base["budget"] ?: JsonNull),
			"verdict" to verdict,
			"conditions" to (base["conditions"] ?: JsonNull),
			"posteriors" to JsonArray(posteriors),
			"experiments" to JsonArray(summaries),
			"skipped" to (base["skipped"] ?: JsonArray(emptyList())),
			"blocked" to (base["blocked"] ?: JsonObject(emptyMap())),
			"notMirrored" to (base["notMirrored"] ?: JsonArray(emptyList())),
			"chain" to obj(
				"observationsHead" to JsonString(head),
				"resultsHead" to JsonString(prev),
				"results" to JsonInt(n.toLong()),
			),
			"honesty" to JsonArray(SolveCertificate.honesty.map(::JsonString)),
		)
		files[SolveCertificate.CERTIFICATE] = CanonicalJson.encode(updated)
		files[CaseFile.MANIFEST] = CaseFile.manifest(files)
		return CaseFile(files)
	}

	private fun counts(label: String, c: Counts) = obj(
		"label" to JsonString(label),
		"failures" to JsonInt(c.failures.toLong()),
		"trials" to JsonInt(c.trials.toLong()),
		"errors" to JsonInt(0),
	)

	private fun verdict(
		case: CaseFile,
		certificate: JsonObject?,
		summaries: List<JsonValue>,
		belief: Belief,
	): JsonValue {
		val old = certificate?.get("verdict") as? JsonObject
		val kind = (old?.get("kind") as? JsonString)?.value
		if (kind == VerdictKind.CONFIRMED.id || kind == VerdictKind.BUNDLE.id) return old
		val ingested = summaries.map { it as JsonObject }.filter {
			(it["role"] as? JsonString)?.value ==
			"ingested"
		}
		val supported = ingested.filter { (it["outcome"] as? JsonString)?.value == "supported" }
		val refuted = ingested.filter { (it["outcome"] as? JsonString)?.value == "refuted" }
		val hypotheses = case.json("hypotheses/prior.json").require("hypotheses").array()
			.map { (it as JsonObject).require("id").string() }
		if (supported.isNotEmpty()) {
			val s = supported.first()
			val flips = (s.require("flips").array()).map { it.string() }
			return SolveVerdict(
				VerdictKind.NARROWED,
				null,
				"the failure reproduces when ${flips.joinToString(", ")} are set to the failing " +
					"values (ingested result ${s.require("id").string()}), but it is not " +
					"confirmed: the minimal-set search and the reverse arm are not in this case",
				candidates = flips,
				next = "preregister and run the reverse arm for ${flips.joinToString(", ")}",
			).toJson()
		}
		val ruledOut = refuted.flatMap { r -> r.require("flips").array().map { it.string() } }
			.toSet()
		val remaining = hypotheses.filter { it !in ruledOut && belief.mass(it) > 0 }
		if (refuted.isNotEmpty() && remaining.isEmpty()) {
			return SolveVerdict(
				VerdictKind.STUCK,
				StuckWhy.NOT_REPRODUCED,
				"every candidate was ruled out by ingested results; the cause is outside them",
			).toJson()
		}
		if (refuted.isNotEmpty()) {
			return SolveVerdict(
				VerdictKind.NARROWED,
				null,
				"${ruledOut.size} candidates were ruled out by ingested results, " +
					"${remaining.size} remain: ${remaining.joinToString(", ")}",
				candidates = remaining,
				next = "run drift next for the best experiment among the remaining candidates",
			).toJson()
		}
		return old ?: SolveVerdict(
			VerdictKind.NARROWED,
			null,
			"the ingested results were inconclusive; no candidate was ruled out",
			candidates = hypotheses,
			next = "run the experiment again with more trials",
		).toJson()
	}

	/** The shape `ingest` reads, for a result of experiment [id] under the rule [ruleSha256]. */
	fun template(id: String, ruleSha256: String, control: Counts, treatment: Counts): String =
		CanonicalJson.encode(
			obj(
				"schema" to JsonInt(1),
				"experiment" to JsonString(id),
				"ruleSha256" to JsonString(ruleSha256),
				"arms" to JsonArray(
					listOf("control" to control, "treatment" to treatment).map { (r, c) ->
						obj(
							"id" to JsonString(r),
							"failures" to JsonInt(c.failures.toLong()),
							"trials" to JsonInt(c.trials.toLong()),
						)
					},
				),
			),
		)
}
