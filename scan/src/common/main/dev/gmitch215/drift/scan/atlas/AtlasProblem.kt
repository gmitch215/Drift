package dev.gmitch215.drift.scan.atlas

/** A reason an atlas input cannot be used. `source` is the file path the caller passed. */
sealed interface AtlasProblem {
	fun message(): String
}

data class TruncatedTranscript(val source: String, val detail: String) : AtlasProblem {
	override fun message() = "truncated transcript $source: $detail"
}

data class BadHeader(val source: String, val detail: String) : AtlasProblem {
	override fun message() = "bad transcript header in $source: $detail"
}

data class DuplicateProbe(val source: String, val id: String) : AtlasProblem {
	override fun message() = "transcript $source lists probe $id twice"
}

data class UnknownProbe(val source: String, val ids: List<String>) : AtlasProblem {
	override fun message() = "transcript $source has probes this build does not know: ${list(ids)}"
}

data class MissingProbes(val source: String, val ids: List<String>) : AtlasProblem {
	override fun message() = "transcript $source lacks ${ids.size} probes " +
		"(${list(ids)}); record it again with this build"
}

data class DuplicateColumn(val label: String, val first: String, val second: String) :
	AtlasProblem {
	override fun message() = "$first and $second both record $label"
}

data object NoTranscripts : AtlasProblem {
	override fun message() = "no transcripts given"
}

data class BadDataset(val detail: String) : AtlasProblem {
	override fun message() = "invalid atlas dataset: $detail"
}

data class NoSuchProbe(val id: String) : AtlasProblem {
	override fun message() = "no probe $id in the dataset"
}

data class BadClassification(val detail: String) : AtlasProblem {
	override fun message() = "invalid atlas classification data: $detail"
}

class AtlasException(val problem: AtlasProblem) : Exception(problem.message())

private fun list(ids: List<String>): String =
	ids.take(3).joinToString(", ") + if (ids.size > 3) " and ${ids.size - 3} more" else ""
