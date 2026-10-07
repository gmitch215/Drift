package dev.gmitch215.drift.know

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.model.Dimension

object RuleCompiler {
	/**
	 * Decodes and validates one rule; throws [RuleException] with the first violation. [fileId] is
	 * the rule file name without `.json` and must equal the rule id.
	 */
	fun compile(text: String, fileId: String? = null): Rule {
		val json = try {
			CanonicalJson.parse(text)
		} catch (e: JsonException) {
			throw RuleException(Violation(fileId.orEmpty(), "", e.message.orEmpty()))
		}
		return compile(json, fileId)
	}

	fun compile(json: JsonValue, fileId: String? = null): Rule {
		val rule = try {
			Rule.fromJson(json)
		} catch (e: RuleException) {
			if (e.violation.ruleId.isNotEmpty() || fileId == null) throw e
			throw RuleException(e.violation.copy(ruleId = fileId))
		}
		val problems = validate(rule) + listOfNotNull(
			if (fileId != null && fileId != rule.id) {
				Violation(rule.id, "id", "does not match the file name '$fileId'")
			} else {
				null
			},
		)
		problems.firstOrNull()?.let { throw RuleException(it) }
		return rule
	}

	/** Every per-rule check; an empty list means the rule is well formed. */
	fun validate(rule: Rule): List<Violation> {
		val out = mutableListOf<Violation>()

		fun bad(path: String, reason: String) {
			out += Violation(rule.id, path, reason)
		}

		if (!validId(rule.id)) bad("id", "must be lowercase words joined by '-'")
		for ((field, text) in listOf(
			"title" to rule.title,
			"difference" to rule.difference,
			"mechanism" to rule.mechanism,
		)) {
			if (text.isBlank()) bad(field, "is empty")
		}
		if (rule.mechanism.isNotBlank() && !oneSentence(rule.mechanism)) {
			bad("mechanism", "must be one sentence ending in a period")
		}
		if (rule.applies.isEmpty()) bad("applies", "is empty")
		val dimensions = Dimension.entries.map { it.id }
		rule.applies.forEachIndexed { i, d ->
			if (d !in dimensions) bad("applies[$i]", "'$d' is not a dimension id")
		}
		if (rule.probe != null && rule.probe.isBlank()) bad("probe", "is empty")
		if (rule.symptoms.isEmpty()) bad("symptoms", "is empty")
		rule.symptoms.forEachIndexed { i, s ->
			if (s.text.isBlank()) bad("symptoms[$i].text", "is empty")
		}
		if (rule.provenance.isEmpty()) bad("provenance", "is empty")
		rule.provenance.forEachIndexed { i, s ->
			if (!(s.url.startsWith("https://") || s.url.startsWith("fixture:")) ||
				s.url.substringAfter(':').trimStart('/').isBlank()
			) {
				bad("provenance[$i].url", "must start with https:// or fixture:")
			}
			if (!validDate(s.verified)) bad("provenance[$i].verified", "must be yyyy-mm-dd")
		}
		if (rule.fixtures.positive.isEmpty()) bad("fixtures.positive", "needs at least one fixture")
		if (rule.fixtures.negative.isEmpty()) bad("fixtures.negative", "needs at least one fixture")
		rule.fixtures.positive.forEachIndexed { i, f ->
			if (f.name.isBlank()) bad("fixtures.positive[$i].name", "is empty")
		}
		rule.fixtures.negative.forEachIndexed { i, f ->
			if (f.name.isBlank()) bad("fixtures.negative[$i].name", "is empty")
		}
		for (path in rule.detect.paths().distinct()) {
			val virtual = path.startsWith("probe:") || path.startsWith("run:")
			if (!virtual && Dimension.of(path.removeSuffix("*")) == null) {
				bad("detect", "path '$path' has no known dimension prefix")
			}
		}
		return out
	}

	private fun validId(id: String) = id.split("-").all { word ->
		word.isNotEmpty() && word.all { it in 'a'..'z' || it in '0'..'9' }
	}

	private fun oneSentence(text: String) =
		!text.contains('\n') && text.endsWith(".") && !text.contains(". ")

	private fun validDate(text: String): Boolean {
		if (text.length != 10 || text[4] != '-' || text[7] != '-') return false
		val digits = text.filterIndexed { i, _ -> i != 4 && i != 7 }
		if (!digits.all { it in '0'..'9' }) return false
		return text.substring(5, 7).toInt() in 1..12 && text.substring(8).toInt() in 1..31
	}
}
