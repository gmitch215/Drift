package dev.gmitch215.drift.redact

import dev.gmitch215.drift.model.Attribute

object Redactor {
	const val MASK = "<redacted>"

	private val secretName =
		Regex("(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH|COOKIE)", RegexOption.IGNORE_CASE)
	private val secretValues = listOf(
		Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
		Regex("github_pat_[A-Za-z0-9_]{20,}"),
		Regex("AKIA[0-9A-Z]{16}"),
		Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
		Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
		Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
		Regex("://[^/\\s:@]+:[^/\\s@]+@"),
	)

	fun redactText(text: String): String = secretValues.fold(text) { acc, re ->
		re.replace(acc, MASK)
	}

	fun redact(attribute: Attribute): Attribute {
		val name = attribute.path.substringAfter("env.", "")
		val masked = if (name.isNotEmpty() &&
			secretName.containsMatchIn(name)
		) {
			MASK
		} else {
			redactText(attribute.value)
		}
		return if (masked == attribute.value) attribute else attribute.copy(value = masked)
	}
}
