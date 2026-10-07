package dev.gmitch215.drift.lab

import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.case.ChainBroken
import dev.gmitch215.drift.case.HashMismatch
import dev.gmitch215.drift.case.MalformedFile
import dev.gmitch215.drift.case.MissingFile
import dev.gmitch215.drift.case.TarProblem
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
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
import dev.gmitch215.drift.math.FixedPoint
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.plan.Belief
import dev.gmitch215.drift.plan.Environment
import dev.gmitch215.drift.plan.Fisher
import dev.gmitch215.drift.plan.Frame
import dev.gmitch215.drift.plan.FrameKind
import dev.gmitch215.drift.plan.Information
import dev.gmitch215.drift.plan.Mass
import dev.gmitch215.drift.plan.Options
import dev.gmitch215.drift.plan.OutcomeTable
import dev.gmitch215.drift.plan.Planner
import dev.gmitch215.drift.plan.Trials
import dev.gmitch215.drift.rank.Ranker

enum class CheckStatus(val id: String) { PASS("pass"), FAIL("fail"), SKIP("skip") }

/** One check: [Check.expected] is what the data derives, [Check.recorded] what the file says. */
data class Check(
	val id: String,
	val status: CheckStatus,
	val detail: String,
	val expected: String? = null,
	val recorded: String? = null,
) {
	fun toJson(): JsonObject = obj(
		"id" to JsonString(id),
		"status" to JsonString(status.id),
		"detail" to JsonString(detail),
		"expected" to (expected?.let(::JsonString) ?: JsonNull),
		"recorded" to (recorded?.let(::JsonString) ?: JsonNull),
	)

	fun line(): String {
		val tag = status.id.uppercase()
		val values = if (expected != null) "; expected $expected, recorded $recorded" else ""
		return "$tag $id: $detail$values"
	}
}

class VerifyReport(val checks: List<Check>, val verdict: String?) {
	val ok: Boolean get() = checks.none { it.status == CheckStatus.FAIL }
	val first: Check? get() = checks.firstOrNull { it.status == CheckStatus.FAIL }

	fun text(): String {
		val lines = checks.map { it.line() }.toMutableList()
		verdict?.let { lines += "verdict: $it" }
		lines += Verify.NOT_RERUN
		lines += if (ok) "verify: ok" else "verify: failed at ${first!!.line().substringAfter(' ')}"
		return lines.joinToString("\n")
	}

	fun toJson(): JsonObject = obj(
		"schema" to JsonInt(1),
		"format" to JsonString("drift-verify-1"),
		"ok" to JsonBool(ok),
		"firstFailure" to (first?.let { JsonString(it.id) } ?: JsonNull),
		"verdict" to (verdict?.let(::JsonString) ?: JsonNull),
		"note" to JsonString(Verify.NOT_RERUN),
		"checks" to JsonArray(checks.map { it.toJson() }),
	)

	fun json(): String = CanonicalJson.encode(toJson())
}

/**
 * Re-derives what a solved case records from its own data: the file hashes and both hash
 * chains, every exact Fisher p from the recorded trials, the outcome of each experiment under
 * its preregistered rule, the posterior sequence, the ranking and plan, and that the verdict
 * follows from the recorded experiments. It does not re-run anything.
 */
object Verify {
	const val NOT_RERUN = "experiments are not re-run; the recorded results are taken as given"

	private val PILOT = "pilot"
	private val UPDATING = setOf("forward", "minimal", "replicate")

	fun open(bytes: ByteArray): VerifyReport = when (val opened = Archive.open(bytes)) {
		is ArchiveOpen.Opened -> {
			val tar = Check("tar", CheckStatus.PASS, "${opened.case.files.size} entries, canonical")
			val rest = run(opened.case)
			VerifyReport(listOf(tar) + rest.checks, rest.verdict)
		}

		is ArchiveOpen.Failed -> VerifyReport(listOf(tarFailure(opened.problem)), null)
	}

	private fun tarFailure(problem: TarProblem) =
		Check("tar", CheckStatus.FAIL, problem.message(), "a canonical USTAR archive", "unreadable")

	fun run(case: CaseFile): VerifyReport {
		val checks = mutableListOf<Check>()
		checks += guard("manifest") { manifest(case) }
		checks += guard("observation-chain") { observationChain(case) }
		val certificate = try {
			SolveCertificate.certificate(case)
		} catch (e: Exception) {
			null
		}
		if (certificate == null) {
			checks += Check(
				"certificate",
				CheckStatus.FAIL,
				"${SolveCertificate.CERTIFICATE} is missing or unreadable",
				"a certificate",
				"none",
			)
			return VerifyReport(checks, null)
		}
		checks += guard("results-chain") { resultsChain(case) }
		val runs = try {
			loadRuns(case, certificate)
		} catch (e: Exception) {
			checks += Check(
				"counts",
				CheckStatus.FAIL,
				"the certificate and its results cannot be read: ${e.message}",
				"a result file for each experiment",
				"unreadable",
			)
			return VerifyReport(checks, verdictLine(certificate))
		}
		val ctx = Context(case, certificate, runs)
		checks += guard("capsules") { ctx.capsules() }
		checks += guard("counts") { ctx.counts() }
		checks += guard("fisher") { ctx.fisher() }
		checks += guard("rules") { ctx.rules() }
		checks += guard("decision") { ctx.decision() }
		checks += guard("posteriors") { ctx.posteriors() }
		checks += guard("ranking") { ctx.ranking() }
		checks += guard("plan") { ctx.plan() }
		checks += guard("verdict") { ctx.verdict() }
		checks += guard("report") { ctx.report() }
		return VerifyReport(checks, verdictLine(certificate))
	}

	private fun verdictLine(certificate: JsonObject): String? = try {
		val v = certificate.require("verdict").obj()
		v.require("label").string() + ": " + v.require("statement").string()
	} catch (e: Exception) {
		null
	}

	private fun guard(id: String, f: () -> Check): Check = try {
		f()
	} catch (e: Exception) {
		Check(id, CheckStatus.FAIL, "malformed data: ${e.message ?: e::class.simpleName}")
	}

	private fun fail(id: String, detail: String, expected: Any?, recorded: Any?) =
		Check(id, CheckStatus.FAIL, detail, expected.toString(), recorded.toString())

	private fun pass(id: String, detail: String) = Check(id, CheckStatus.PASS, detail)

	private fun manifest(case: CaseFile): Check {
		val schema = CaseFile.schemaOf(CaseFile.MANIFEST, case.json(CaseFile.MANIFEST))
		if (schema != null) return fail("manifest", schema.message(), "schema 1", schema.path)
		val listed = case.json(CaseFile.MANIFEST).require("files").array()
			.map { it.obj().require("path").string() }
		val problem = case.check().firstOrNull { it !is ChainBroken }
		when (problem) {
			is HashMismatch ->
				return fail(
					"manifest",
					"${problem.path} was changed",
					problem.expected,
					problem.actual,
				)

			is MissingFile ->
				return fail(
					"manifest",
					"a file the manifest lists is missing",
					problem.path,
					"missing",
				)

			is MalformedFile ->
				return fail("manifest", problem.message(), "readable JSON", problem.detail)

			else -> Unit
		}
		val extra = case.files.keys.filter { it != CaseFile.MANIFEST && it !in listed }
		if (extra.isNotEmpty()) {
			return fail("manifest", "a file is not in the manifest", "listed", extra.first())
		}
		return pass("manifest", "${listed.size} files match the manifest")
	}

	private fun observationChain(case: CaseFile): Check {
		val problems = case.check()
		val broken = problems.filterIsInstance<ChainBroken>().firstOrNull()
		if (broken != null) {
			return fail("observation-chain", broken.message(), "an intact chain", broken.detail)
		}
		val unreadable = problems.filterIsInstance<MalformedFile>().firstOrNull()
		if (unreadable != null) {
			return fail(
				"observation-chain",
				unreadable.message(),
				"readable JSON",
				unreadable.detail,
			)
		}
		val count = case.json(CaseFile.CASE).require("observations").long()
		return pass("observation-chain", "$count observations chain to the head in case.json")
	}

	private fun resultsChain(case: CaseFile): Check {
		val problems = SolveCertificate.check(case)
		if (problems.isNotEmpty()) {
			return fail("results-chain", problems.first(), "an intact chain", "broken")
		}
		val n = case.files.keys.count { it.startsWith("results/0") }
		return pass(
			"results-chain",
			"$n results continue the observation chain to the recorded head",
		)
	}

	private class Arm(val label: String, val failures: Int, val passes: Int, val errors: Int) {
		val trials: Int get() = failures + passes
		val size: Int get() = failures + passes + errors
	}

	private class RunView(val summary: JsonObject, val result: JsonObject) {
		val id: String = summary.require("id").string()
		val role: String = summary.require("role").string()
		val reverse: Boolean get() = role == "reverse"
		val flips: List<String> = summary.require("flips").array().map { it.string() }
		val recordedOutcome: String? = (result["outcome"] as? JsonString)?.value
		val derived: Pair<Arm, Arm> = arms(result)

		val more: Arm get() = if (reverse) derived.first else derived.second
		val less: Arm get() = if (reverse) derived.second else derived.first
	}

	private fun arms(result: JsonObject): Pair<Arm, Arm> {
		val list = result.require("arms").array().map { it.obj() }
		require(list.size == 2) { "a result needs two arms" }
		fun arm(a: JsonObject): Arm {
			val trials = a.require("trials").array().map { it.obj().require("status").string() }
			val recorded = trials.isEmpty()
			return if (recorded) {
				Arm(
					a.require("label").string(),
					a.require("failures").long().toInt(),
					a.require("passes").long().toInt(),
					a.require("errors").long().toInt(),
				)
			} else {
				Arm(
					a.require("label").string(),
					trials.count { it == "fail" },
					trials.count { it == "pass" },
					trials.count { it == "error" },
				)
			}
		}
		return arm(list[0]) to arm(list[1])
	}

	private fun loadRuns(case: CaseFile, certificate: JsonObject): List<RunView> =
		certificate.require("experiments").array().map {
			val summary = it.obj()
			RunView(summary, case.json(summary.require("file").string()))
		}

	private fun enc(v: JsonValue?): String = if (v == null) "none" else CanonicalJson.encode(v)

	private fun pText(p: JsonValue?): String {
		val o = p as? JsonObject ?: return "none"
		return "${o["numerator"]?.let { (it as JsonInt).value }}/" +
			"${o["denominator"]?.let { (it as JsonInt).value }} " +
			"(${o["micro"]?.let { (it as JsonInt).value }})"
	}

	private class Context(val case: CaseFile, val cert: JsonObject, val runs: List<RunView>) {
		val plan: JsonObject by lazy { case.json("experiments/plan.json") }
		val options: JsonObject by lazy { plan.require("options").obj() }
		val ingested: Boolean get() = runs.any { it.role == "ingested" }

		fun capsules(): Check {
			val head = case.json(CaseFile.CASE)
			val certHead = cert.require("chain").obj().require("observationsHead").string()
			val headHash = head.require("head").string()
			if (certHead != headHash) {
				return fail(
					"capsules",
					"the certificate chains from another head",
					headHash,
					certHead,
				)
			}
			if (enc(head.require("frame")) != enc(cert.require("frame"))) {
				return fail(
					"capsules",
					"case.json and the certificate disagree on the frame",
					enc(head.require("frame")),
					enc(cert.require("frame")),
				)
			}
			for (role in listOf("passing", "failing")) {
				val capsule = Capsule.parse(
					case.text("environments/$role.json") ?: error("missing"),
				)
				val record = case.files.keys.filter { it.startsWith("observations/") }
					.map { case.json(it) }
					.firstOrNull {
						(it["kind"] as? JsonString)?.value == "capsule" &&
						it["role"] == JsonString(role)
					}
					?: return fail(
						"capsules",
						"no observation records the $role capsule",
						role,
						"none",
					)
				val recorded = record.require("capsule").string()
				if (capsule.hash() != recorded) {
					return fail(
						"capsules",
						"the $role capsule does not match its observation",
						capsule.hash(),
						recorded,
					)
				}
			}
			return pass(
				"capsules",
				"both capsules match their observations and the certificate chains from " +
					"the same head",
			)
		}

		fun counts(): Check {
			val files = case.files.keys.count { it.startsWith("results/0") }
			if (files != runs.size) {
				return fail(
					"counts",
					"results files and certificate experiments differ",
					files,
					runs.size,
				)
			}
			val index = case.json("results/index.json").require("results").array().size
			if (index != runs.size) {
				return fail(
					"counts",
					"results/index.json and the certificate differ",
					runs.size,
					index,
				)
			}
			for ((i, r) in runs.withIndex()) {
				val tag = "experiment ${r.id}"
				val s = r.summary
				val res = r.result
				val same = listOf(
					"seq" to (i + 1L).toString(),
				)
				for ((key, want) in same) {
					val sSeq = enc(s[key])
					val rSeq = enc(res[key])
					if (sSeq != want || rSeq != want) {
						return fail("counts", "$tag $key", want, if (rSeq != want) rSeq else sSeq)
					}
				}
				if (res.require("experiment").string() != r.id) {
					return fail(
						"counts",
						"$tag names another experiment",
						r.id,
						res.require("experiment").string(),
					)
				}
				for (key in listOf("role", "flips", "outcome", "p", "specSha256", "ruleSha256")) {
					if (enc(s[key]) != enc(res[key])) {
						return fail(
							"counts",
							"$tag $key differs between the certificate and its result",
							enc(res[key]),
							enc(s[key]),
						)
					}
				}
				val armJson = res.require("arms").array().map { it.obj() }
				val sides = listOf(
					Triple("control", r.derived.first, armJson[0]),
					Triple("treatment", r.derived.second, armJson[1]),
				)
				for ((side, d, a) in sides) {
					for ((key, want) in listOf(
							"failures" to d.failures,
							"passes" to d.passes,
							"errors" to d.errors,
						)) {
						val got = a.require(key).long()
						if (got != want.toLong()) {
							return fail(
							"counts",
							"$tag $side $key",
							want,
							got,
						)
						}
					}
					val sum = s.require(side).obj()
					for ((key, want) in listOf(
							"failures" to d.failures,
							"trials" to d.trials,
							"errors" to d.errors,
						)) {
						val got = sum.require(key).long()
						if (got != want.toLong()) {
							return fail("counts", "$tag $side $key in the certificate", want, got)
						}
					}
				}
			}
			return pass(
				"counts",
				"${runs.size} experiments: every count is the tally of its recorded trials",
			)
		}

		private fun pOf(r: RunView): JsonObject {
			val m = r.more
			val l = r.less
			require(m.trials + l.trials <= Fisher.MAX_TOTAL) { "too many trials for the test" }
			require(m.trials >= 1 && l.trials >= 1) { "an arm has no completed trial" }
			val p = Fisher.oneSided(
				m.failures,
				m.trials - m.failures,
				l.failures,
				l.trials - l.failures,
			)
			return ExperimentRun.pJson(p)
		}

		fun fisher(): Check {
			for (r in runs) {
				val derived = pOf(r)
				val recorded = r.result["p"]
				if (enc(derived) != enc(recorded)) {
					return fail(
						"fisher",
						"experiment ${r.id} exact one-sided p",
						pText(derived),
						pText(recorded),
					)
				}
				if (enc(derived) != enc(r.summary["p"])) {
					return fail(
						"fisher",
						"experiment ${r.id} p in the certificate",
						pText(derived),
						pText(r.summary["p"]),
					)
				}
			}
			return pass(
				"fisher",
				"${runs.size} exact one-sided p-values re-derived from the recorded counts",
			)
		}

		private fun ruleOf(r: RunView): JsonObject? = if (r.role ==
				PILOT
			) {
					null
				} else {
					spec(r)?.require("rule")?.obj() ?: r.summary["rule"] as? JsonObject
				}

		private fun spec(r: RunView): JsonObject? =
			case.text("experiments/${r.id}.json")?.let { case.json("experiments/${r.id}.json") }

		fun rules(): Check {
			val alpha = options.require("alpha").long()
			val rate = Trials.rate(
				options.require("failures").long().toInt(),
				options.require("runs").long().toInt(),
			)
			var checked = 0
			for (r in runs) {
				val tag = "experiment ${r.id}"
				if (r.role == PILOT) {
					if (r.recordedOutcome != null || r.result["specSha256"] != JsonNull) {
						return fail(
							"rules",
							"$tag is a pilot and carries no rule",
							"no outcome",
							r.recordedOutcome,
						)
					}
					continue
				}
				val specText = case.text("experiments/${r.id}.json")
				val rule = ruleOf(r)
					?: return fail("rules", "$tag has no preregistered rule", "a rule", "none")
				if (specText == null && r.role != "ingested") {
					return fail(
						"rules",
						"$tag has no preregistered spec",
						"experiments/${r.id}.json",
						"missing",
					)
				}
				if (specText != null) {
					val sha = Sha256.hex(specText)
					val recorded = enc(r.result["specSha256"])
					if (recorded != enc(JsonString(sha))) {
						return fail("rules", "$tag spec hash", sha, recorded.trim('"'))
					}
				}
				val ruleSha = Sha256.hex(CanonicalJson.encode(rule))
				for ((label, value) in listOf(
					"result" to r.result["ruleSha256"],
					"certificate" to r.summary["ruleSha256"],
				)) {
					if (enc(value) != enc(JsonString(ruleSha))) {
						val got = enc(value).trim('"')
						return fail("rules", "$tag rule hash in the $label", ruleSha, got)
					}
				}
				if (enc(rule) != enc(r.summary["rule"])) {
					return fail(
						"rules",
						"$tag rule text in the certificate",
						enc(rule),
						enc(r.summary["rule"]),
					)
				}
				val ruleAlpha = rule.require("alpha").long()
				if (ruleAlpha != alpha) {
					return fail("rules", "$tag alpha is not the plan's", alpha, ruleAlpha)
				}
				val perArm = rule.require("perArm").long().toInt()
				val threshold = Trials.threshold(perArm, alpha)
				if (threshold == null || threshold.toLong() != rule.require("threshold").long()) {
					return fail(
						"rules",
						"$tag threshold for $perArm trials per arm",
						threshold,
						rule.require("threshold").long(),
					)
				}
				if (rule.require("rate").long() != rate) {
					return fail(
						"rules",
						"$tag failure rate in the rule",
						rate,
						rule.require("rate").long(),
					)
				}
				for (arm in listOf(r.derived.first, r.derived.second)) {
					if (arm.size != perArm) {
						return fail("rules", "$tag trials per arm", perArm, arm.size)
					}
				}
				checked++
			}
			return pass(
				"rules",
				"$checked preregistered rules match their spec hashes, the plan's alpha and " +
					"the planned trials",
			)
		}

		fun decision(): Check {
			var checked = 0
			for (r in runs) {
				if (r.role == PILOT) continue
				val rule = ruleOf(r) ?: continue
				val decision = DecisionRule(
					rule.require("perArm").long().toInt(),
					rule.require("threshold").long().toInt(),
					rule.require("alpha").long(),
					rule.require("rate").long(),
				)
				val m = r.more
				val l = r.less
				val outcome = decision.evaluate(
					Counts(m.failures, m.trials),
					Counts(l.failures, l.trials),
				).id
				if (outcome != r.recordedOutcome) {
					return fail(
						"decision",
						"experiment ${r.id} outcome under its preregistered rule",
						outcome,
						r.recordedOutcome,
					)
				}
				checked++
			}
			return pass(
				"decision",
				"$checked outcomes follow from the counts under the preregistered rules",
			)
		}

		fun posteriors(): Check {
			if (ingested) {
				return Check(
					"posteriors",
					CheckStatus.SKIP,
					"ingested results are present; their posteriors are not re-derived",
				)
			}
			val prior = case.json("hypotheses/prior.json").require("prior").obj()
			var belief = beliefOf(prior)
			val recorded = cert.require("posteriors").array().map { it.obj() }
			if (recorded.isEmpty() && runs.none { it.role in UPDATING }) {
				return pass("posteriors", "the run stopped at the pilot, so no belief was formed")
			}
			val power = plan.require("trials").obj().require("power").long()
			val alpha = options.require("alpha").long()
			val expected = mutableListOf("prior" to belief)
			for (r in runs) {
				if (r.role !in UPDATING) continue
				val outcome = r.recordedOutcome
				if (outcome != "supported" && outcome != "refuted") continue
				val table = OutcomeTable.binary(
					belief.masses.map { it.id },
					r.flips.toSet(),
					power,
					alpha,
				)
				val wanted = if (outcome ==
					"supported"
				) {
						OutcomeTable.REPRODUCED
					} else {
						OutcomeTable.NOT_REPRODUCED
					}
				belief = Information.branches(belief, table)
					.first { it.outcome == wanted }.posterior
				expected += "${r.id} $outcome" to belief
			}
			if (expected.size != recorded.size) {
				return fail("posteriors", "number of posteriors", expected.size, recorded.size)
			}
			for ((i, e) in expected.withIndex()) {
				val got = recorded[i]
				val label = got.require("after").string()
				if (label !=
					e.first
				) {
						return fail(
							"posteriors",
							"posterior $i is for another step",
							e.first,
							label,
						)
					}
				val derived = enc(e.second.toJson())
				if (derived != enc(got["belief"])) {
					return fail(
						"posteriors",
						"posterior after ${e.first}",
						derived,
						enc(got["belief"]),
					)
				}
			}
			return pass(
				"posteriors",
				"${expected.size} beliefs re-derived by Bayes over the recorded outcomes",
			)
		}

		private fun beliefOf(json: JsonObject): Belief = Belief(
			json.require("masses").array().map {
				val m = it.obj()
				Mass(m.require("id").string(), m.require("micro").long())
			},
		)

		private fun pair(): Pair<Capsule, Capsule> = Capsule.parse(
			case.text("environments/passing.json") ?: error("missing environments/passing.json"),
		) to Capsule.parse(
			case.text("environments/failing.json") ?: error("missing environments/failing.json"),
		)

		private fun hasRuns(): Boolean = case.files.keys.filter { it.startsWith("observations/") }
			.any { (case.json(it)["kind"] as? JsonString)?.value == "run" }

		fun ranking(): Check {
			if (hasRuns()) {
				return Check(
					"ranking",
					CheckStatus.SKIP,
					"the case holds run records; a history ranking is not re-derived",
				)
			}
			val (passing, failing) = pair()
			val derived = CanonicalJson.encode(Ranker.rank(passing, failing).toJson())
			val recorded = case.text("hypotheses/ranking.json")
			if (derived != recorded) {
				return fail(
					"ranking",
					"hypotheses/ranking.json",
					Sha256.hex(derived),
					recorded?.let {
					Sha256.hex(it)
				},
				)
			}
			return pass("ranking", "tiers and candidates re-derived from the two capsules")
		}

		fun plan(): Check {
			if (hasRuns()) {
				return Check(
					"plan",
					CheckStatus.SKIP,
					"the case holds run records; a history plan is not re-derived",
				)
			}
			val (passing, failing) = pair()
			val f = plan.require("frame").obj()
			val frame = Frame(
				if (f.require("kind").string() == "sha") FrameKind.SHA else FrameKind.ENVIRONMENT,
				f.require("passing").string(),
				f.require("failing").string(),
				if (f.require("environment").string() == "ci") {
					Environment.CI
				} else {
					Environment.LOCAL
				},
			)
			val opts = Options(
				options.require("trialMinutes").long(),
				options.require("failures").long().toInt(),
				options.require("runs").long().toInt(),
				options.require("alpha").long(),
				options.require("power").long(),
				options.require("maxPerArm").long().toInt(),
				options.require("unknown").long(),
				options.require("couplings").obj().fields.mapValues { it.value.string() },
			)
			val derived = Planner.from(Ranker.rank(passing, failing), frame, opts).json()
			val recorded = case.text("experiments/plan.json")
			if (derived != recorded) {
				return fail(
					"plan",
					"experiments/plan.json",
					Sha256.hex(derived),
					recorded?.let {
					Sha256.hex(it)
				},
				)
			}
			return pass(
				"plan",
				"hypotheses, prior and plan re-derived from the ranking and the recorded options",
			)
		}

		private fun same(a: List<String>, b: List<String>) = a.sorted() == b.sorted()

		private fun strings(v: JsonValue?): List<String> = v?.array()?.map { it.string() }.orEmpty()

		fun verdict(): Check {
			val v = cert.require("verdict").obj()
			val id = v.require("kind").string()
			val kind = VerdictKind.entries.firstOrNull { it.id == id }
				?: return fail("verdict", "unknown verdict kind", "a known kind", id)
			if (v.require("label").string() != kind.label) {
				return fail("verdict", "verdict label", kind.label, v.require("label").string())
			}
			val conditions = cert["conditions"] as? JsonObject
			val forwardSupported =
				runs.any { it.role == "forward" && it.recordedOutcome == "supported" }
			val forwardInconclusive =
				runs.any { it.role == "forward" && it.recordedOutcome == "inconclusive" }
			when (kind) {
				VerdictKind.CONFIRMED, VerdictKind.BUNDLE ->
					confirmed(v, kind, conditions)?.let { return it }

				VerdictKind.NARROWED -> {
					if (conditions != null && conditionsConfirmed(conditions) != false) {
						return fail(
							"verdict",
							"NARROWED with confirmed conditions",
							"confirmed false",
							enc(conditions["confirmed"]),
						)
					}
					if (!forwardSupported && !forwardInconclusive) {
						return fail(
							"verdict",
							"NARROWED needs a supported or inconclusive experiment",
							"one",
							"none",
						)
					}
					if (conditions != null) {
						val search = conditions.require("minimalSet").obj()
						val agreement = conditions.require("agreement").obj()
						val agrees = (agreement["agrees"] as? JsonBool)?.value == true
						if (search.require("unresolved").long() == 0L && agrees) {
							return fail(
								"verdict",
								"NARROWED although the conditions of CONFIRMED hold",
								"unresolved or no agreement",
								"all conditions hold",
							)
						}
					}
				}

				VerdictKind.STUCK -> stuck(v, conditions, forwardSupported)?.let { return it }
			}
			return pass("verdict", "${kind.label} follows from the recorded experiments")
		}

		private fun runById(id: String?): RunView? = runs.firstOrNull { it.id == id }

		private fun confirmed(v: JsonObject, kind: VerdictKind, conditions: JsonObject?): Check? {
			if (conditions == null || conditionsConfirmed(conditions) != true) {
				return fail(
					"verdict",
					"${kind.label} without confirmed conditions",
					"confirmed true",
					enc(conditions?.get("confirmed")),
				)
			}
			val set = strings(v["minimalSet"])
			val search = conditions.require("minimalSet").obj()
			if (set.isEmpty() || !same(set, strings(search["set"]))) {
				return fail(
					"verdict",
					"minimal set of the verdict",
					enc(search["set"]),
					enc(v["minimalSet"]),
				)
			}
			if (search.require("unresolved").long() != 0L) {
				return fail("verdict", "unresolved subsets", 0, search.require("unresolved").long())
			}
			val effect = conditions.require("effect").obj()
			val forward = runById((effect["experiment"] as? JsonString)?.value)
			if (forward == null || forward.role != "forward" ||
				forward.recordedOutcome != "supported"
			) {
				return fail(
					"verdict",
					"the effect experiment is a supported forward run",
					"supported",
					forward?.recordedOutcome,
				)
			}
			val carrier = runs.firstOrNull {
				it.role in setOf("forward", "minimal") && same(it.flips, set)
			}
			if (carrier == null || carrier.recordedOutcome != "supported") {
				return fail(
					"verdict",
					"an experiment of exactly the minimal set is supported",
					"supported",
					carrier?.recordedOutcome,
				)
			}
			val agreement = conditions.require("agreement").obj()
			val partner = runById((agreement["experiment"] as? JsonString)?.value)
			val wanted = if (agreement["kind"] == JsonString("reverse")) "reverse" else "replicate"
			if (partner == null || partner.role != wanted || !same(partner.flips, set) ||
				partner.recordedOutcome != "supported" || agreement["agrees"] != JsonBool(true)
			) {
				return fail(
					"verdict",
					"the agreeing $wanted experiment is supported",
					"supported",
					partner?.recordedOutcome,
				)
			}
			val changed = strings((effect["diff"] as? JsonObject)?.get("changed"))
			if (!same(strings(v["members"]), changed)) {
				return fail(
					"verdict",
					"members of the verdict",
					changed.toString(),
					strings(v["members"]).toString(),
				)
			}
			val notIsolated = strings(v["notIsolated"])
			if (kind == VerdictKind.CONFIRMED && notIsolated.isNotEmpty()) {
				return fail(
					"verdict",
					"CONFIRMED with attributes not isolated",
					"none",
					notIsolated.toString(),
				)
			}
			val named = notIsolated.isNotEmpty() && changed.containsAll(notIsolated)
			if (kind == VerdictKind.BUNDLE && !named) {
				return fail(
					"verdict",
					"a bundle names what was not isolated",
					"a subset of the members",
					notIsolated.toString(),
				)
			}
			val statement = v.require("statement").string()
			for (needle in listOf(
				"exact one-sided p ${FixedPoint.format(pOf(carrier).require("micro").long())}",
				"(p ${FixedPoint.format(pOf(partner).require("micro").long())})",
				set.joinToString(", "),
			)) {
				if (needle !in statement) {
					return fail(
						"verdict",
						"the statement quotes the recorded figures",
						needle,
						"absent",
					)
				}
			}
			return null
		}

		private fun conditionsConfirmed(conditions: JsonObject): Boolean? =
			(conditions.require("confirmed") as? JsonBool)?.value

		private fun stuck(v: JsonObject, conditions: JsonObject?, supported: Boolean): Check? {
			if (conditions != null || supported) {
				return fail(
					"verdict",
					"STUCK although an experiment was supported",
					"no supported experiment",
					"supported",
				)
			}
			val why = (v["reason"] as? JsonString)?.value
			if (StuckWhy.entries.none { it.id == why }) {
				return fail("verdict", "STUCK names a reason", "a known reason", why)
			}
			if (strings(v["minimalSet"]).isNotEmpty()) {
				return fail("verdict", "STUCK has no minimal set", "none", enc(v["minimalSet"]))
			}
			if (runs.size == 1 && (why == "not-reproduced" || why == "uncontrollable")) {
				val control = runs[0].derived.first
				val treatment = runs[0].derived.second
				val bothFail = control.failures >= 2 && control.failures >= treatment.failures
				val holds = treatment.failures == 0 || bothFail
				if (!holds) {
					return fail(
						"verdict",
						"the pilot stops the run",
						"no failure in the failing baseline, or the passing one failing as often",
						"${control.failures} of ${control.trials} against " +
							"${treatment.failures} of ${treatment.trials}",
					)
				}
			}
			return null
		}

		fun report(): Check {
			val html = case.text(Archive.REPORT)
			val record = case.text(Archive.CONFIRMED)
			if (html == null && record == null) {
				return Check(
					"report",
					CheckStatus.SKIP,
					"a case directory holds no report.html or confirmed record",
				)
			}
			val wantHtml = ReportHtml.of(cert)
			if (html != wantHtml) {
				return fail(
					"report",
					"report.html is generated from the certificate",
					Sha256.hex(wantHtml),
					html?.let {
					Sha256.hex(it)
				},
				)
			}
			val wantRecord = Archive.confirmed(cert)
			if (record != wantRecord) {
				return fail(
					"report",
					"confirmed/minimal-set.json follows from the certificate",
					wantRecord?.let { Sha256.hex(it) } ?: "absent",
					record?.let { Sha256.hex(it) } ?: "absent",
				)
			}
			return pass(
				"report",
				"report.html and the confirmed record are what the certificate generates",
			)
		}
	}
}
