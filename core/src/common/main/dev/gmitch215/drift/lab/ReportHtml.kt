package dev.gmitch215.drift.lab

import dev.gmitch215.drift.json.JsonArray
import dev.gmitch215.drift.json.JsonBool
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.array
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require
import dev.gmitch215.drift.json.string
import dev.gmitch215.drift.math.FixedPoint

/**
 * One static HTML page from a certificate: no script, no external resource, no timestamp, and
 * the same bytes for the same certificate. Every detail is in the page; `<details>` elements
 * collapse what a first read does not need.
 */
object ReportHtml {
	private const val STYLE = "body{font-family:sans-serif;max-width:60rem;margin:2rem auto;" +
		"padding:0 1rem;line-height:1.45}table{border-collapse:collapse;width:100%}" +
		"th,td{border:1px solid #888;padding:.25rem .5rem;text-align:left;vertical-align:top}" +
		"code{word-break:break-all}summary{cursor:pointer;font-weight:bold;margin:.5rem 0}" +
		".verdict{border-left:.4rem solid #888;padding-left:1rem}"

	/** Throws [JsonException] when [certificate] lacks a field the page shows. */
	fun of(certificate: JsonObject): String {
		val verdict = certificate.require("verdict").obj()
		val out = StringBuilder()
		fun line(text: String) = out.append(text).append('\n')
		line("<!DOCTYPE html>")
		line("<html lang=\"en\">")
		line("<head>")
		line("<meta charset=\"utf-8\">")
		line("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
		line("<title>${esc(certificate.text("name"))} - Drift certificate</title>")
		line("<style>$STYLE</style>")
		line("</head>")
		line("<body>")
		line("<h1>${esc(certificate.text("name"))}</h1>")
		line("<div class=\"verdict\">")
		line("<h2>${esc(verdict.text("label"))}</h2>")
		line("<p>${esc(verdict.text("statement"))}</p>")
		line("<p>${esc(Verify.NOT_RERUN)}.</p>")
		line("</div>")
		line(
			"<p>Command: <code>${esc(certificate.text("command"))}</code>. A trial fails on: " +
				"<code>${esc(certificate.text("failWhen"))}</code>.</p>",
		)
		out.append(causal(verdict, certificate.require("conditions")))
		out.append(experiments(certificate.require("experiments").array()))
		out.append(arms(certificate.require("experiments").array()))
		out.append(posteriors(certificate.require("posteriors").array()))
		out.append(rules(certificate.require("experiments").array()))
		out.append(notMirrored(certificate.require("notMirrored").array()))
		out.append(chain(certificate))
		out.append(honesty(certificate.require("honesty").array()))
		line("</body>")
		line("</html>")
		return out.toString()
	}

	private fun esc(s: String): String {
		val out = StringBuilder()
		for (c in s) {
			when (c) {
				'&' -> out.append("&amp;")
				'<' -> out.append("&lt;")
				'>' -> out.append("&gt;")
				'"' -> out.append("&quot;")
				'\'' -> out.append("&#39;")
				else -> out.append(c)
			}
		}
		return out.toString()
	}

	private fun JsonObject.text(key: String): String = require(key).string()

	private fun JsonObject.num(key: String): Long = require(key).long()

	private fun strings(value: JsonValue?): List<String> =
		(value as? JsonArray)?.items?.map { it.string() }.orEmpty()

	private fun list(items: List<String>): String =
		if (items.isEmpty()) "none" else items.joinToString(", ") { "<code>${esc(it)}</code>" }

	private fun section(title: String, open: Boolean, body: String): String =
		"<details${if (open) " open" else ""}>\n<summary>${esc(title)}</summary>\n$body</details>\n"

	private fun causal(verdict: JsonObject, conditions: JsonValue): String {
		val body = StringBuilder()
		body.append("<p>Minimal causal set: ${list(strings(verdict["minimalSet"]))}.</p>\n")
		body.append(
			"<p>Attributes that differ between the arms: " +
				"${list(strings(verdict["members"]))}.</p>\n",
		)
		body.append("<p>Not isolated: ${list(strings(verdict["notIsolated"]))}.</p>\n")
		body.append("<p>Mechanism: ${esc(verdict.text("mechanism"))}.</p>\n")
		val candidates = strings(verdict["candidates"])
		if (candidates.isNotEmpty()) body.append("<p>Candidates left: ${list(candidates)}.</p>\n")
		(verdict["next"] as? JsonString)?.let {
			body.append("<p>Best next experiment: ${esc(it.value)}</p>\n")
		}
		(verdict["reason"] as? JsonString)?.let {
			body.append("<p>Why it is stuck: ${esc(it.value)}.</p>\n")
		}
		if (conditions is JsonObject) {
			val agreement = conditions.require("agreement").obj()
			val search = conditions.require("minimalSet").obj()
			val experiment = (agreement["experiment"] as? JsonString)?.value ?: "none"
			val agrees = if ((agreement["agrees"] as? JsonBool)?.value == true) {
				"agrees"
			} else {
				"does not agree"
			}
			body.append(
				"<p>Agreement: ${esc(agreement.text("kind"))} experiment ${esc(experiment)}, " +
					"$agrees.</p>\n",
			)
			body.append(
				"<p>Minimal-set search: ${search.num("tested")} subsets tested, " +
					"${search.num("unresolved")} unresolved.</p>\n",
			)
		}
		return section("Cause and conditions", true, body.toString())
	}

	private fun counts(a: JsonObject) = "${a.num("failures")} of ${a.num("trials")}"

	private fun p(value: JsonValue?): String {
		val p = value as? JsonObject ?: return "none"
		return "${FixedPoint.format(p.num("micro"))} (${p.num("numerator")} / " +
			"${p.num("denominator")})"
	}

	private fun experiments(items: List<JsonValue>): String {
		val body = StringBuilder(
			"<table>\n<tr><th>Experiment</th><th>Role</th><th>Flipped</th>" +
			"<th>Control failures</th><th>Treatment failures</th><th>Exact one-sided p</th>" +
			"<th>Outcome</th></tr>\n",
		)
		for (item in items) {
			val e = item.obj()
			val outcome = (e["outcome"] as? JsonString)?.value ?: "none"
			val control = e.require("control").obj()
			val treatment = e.require("treatment").obj()
			body.append(
				"<tr><td>${esc(e.text("id"))}</td><td>${esc(e.text("role"))}</td>" +
					"<td>${list(strings(e["flips"]))}</td>" +
					"<td>${esc(counts(control))} (${esc(control.text("label"))})</td>" +
					"<td>${esc(counts(treatment))} (${esc(treatment.text("label"))})</td>" +
					"<td>${esc(p(e["p"]))}</td><td>${esc(outcome)}</td></tr>\n",
			)
		}
		body.append("</table>\n")
		return section("Experiments, raw counts", true, body.toString())
	}

	private fun arms(items: List<JsonValue>): String {
		val body = StringBuilder()
		for (item in items) {
			val e = item.obj()
			val diff = e["diff"] as? JsonObject
			body.append("<h3>${esc(e.text("id"))}</h3>\n")
			body.append("<p>Control: ${esc(e.require("control").obj().text("label"))}. ")
			body.append("Treatment: ${esc(e.require("treatment").obj().text("label"))}.</p>\n")
			if (diff == null) {
				body.append("<p>No capsule difference recorded.</p>\n")
			} else {
				body.append("<p>Capsule difference (${esc(diff.text("source"))}): ")
				body.append("${list(strings(diff["changed"]))}. ")
				body.append("Ignored: ${list(strings(diff["ignored"]))}. ")
				body.append("Probes that differ: ${list(strings(diff["probes"]))}.</p>\n")
			}
		}
		return section("Arms and their capsule differences", false, body.toString())
	}

	private fun posteriors(items: List<JsonValue>): String {
		val body = StringBuilder()
		for (item in items) {
			val step = item.obj()
			val belief = step.require("belief").obj()
			body.append("<h3>${esc(step.text("after"))}</h3>\n<table>\n")
			body.append("<tr><th>Hypothesis</th><th>Probability</th></tr>\n")
			for (m in belief.require("masses").array()) {
				val mass = m.obj()
				body.append(
					"<tr><td>${esc(mass.text("id"))}</td>" +
						"<td>${FixedPoint.format(mass.num("micro"))}</td></tr>\n",
				)
			}
			body.append("</table>\n")
			body.append("<p>Entropy ${FixedPoint.format(belief.num("entropy"))} bits.</p>\n")
		}
		return section("Posterior sequence", false, body.toString())
	}

	private fun rules(items: List<JsonValue>): String {
		val body = StringBuilder()
		for (item in items) {
			val e = item.obj()
			val rule = e["rule"] as? JsonObject ?: continue
			body.append("<h3>${esc(e.text("id"))}</h3>\n")
			body.append("<p>sha256 <code>${esc(e.text("ruleSha256"))}</code></p>\n<ul>\n")
			for (key in listOf("test", "supported", "refuted", "inconclusive")) {
				body.append("<li>${esc(key)}: ${esc(rule.text(key))}</li>\n")
			}
			body.append("</ul>\n")
		}
		if (body.isEmpty()) body.append("<p>No experiment carries a preregistered rule.</p>\n")
		return section("Preregistered rules", false, body.toString())
	}

	private fun notMirrored(items: List<JsonValue>): String {
		val body = StringBuilder(
			"<p>The containers do not mirror these attributes of the original environment.</p>\n",
		)
		if (items.isEmpty()) {
			body.append("<p>None recorded.</p>\n")
		} else {
			body.append("<ul>\n")
			for (item in items) {
				val n = item.obj()
				body.append(
					"<li><code>${esc(n.text("path"))}</code>: ${esc(n.text("reason"))}</li>\n",
				)
			}
			body.append("</ul>\n")
		}
		return section("What was not mirrored", false, body.toString())
	}

	private fun chain(certificate: JsonObject): String {
		val chain = certificate.require("chain").obj()
		val budget = certificate.require("budget").obj()
		val body = "<p>Observation chain head " +
			"<code>${esc(chain.text("observationsHead"))}</code></p>\n" +
			"<p>Results chain head <code>${esc(chain.text("resultsHead"))}</code> over " +
			"${chain.num("results")} results</p>\n" +
			"<p>Spent ${budget.num("trials")} trials in ${budget.num("seconds")} seconds, " +
			"within ${budget.num("maxTrials")} trials and " +
			"${budget.num("maxMinutes")} minutes.</p>\n"
		return section("Chain and budget", false, body)
	}

	private fun honesty(items: List<JsonValue>): String {
		val body = StringBuilder("<ul>\n")
		for (item in items) body.append("<li>${esc(item.string())}</li>\n")
		body.append("<li>${esc(Verify.NOT_RERUN)}.</li>\n</ul>\n")
		return section("What this certificate does not claim", false, body.toString())
	}
}
