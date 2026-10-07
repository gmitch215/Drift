package dev.gmitch215.drift.case

/** A reason a case directory cannot be read or does not check out; never thrown. */
sealed interface CaseProblem {
	val path: String

	fun message(): String
}

data class MissingFile(override val path: String) : CaseProblem {
	override fun message() = "missing case file: $path"
}

data class MalformedFile(override val path: String, val detail: String) : CaseProblem {
	override fun message() = "malformed case file $path: $detail"
}

/** [UnsupportedSchema.found] is null when the file has no readable schema number. */
data class UnsupportedSchema(override val path: String, val found: Long?, val supported: Long) :
	CaseProblem {
	override fun message() = "unsupported case schema ${found ?: "(none)"} in $path; " +
		"this build reads schema $supported"
}

data class HashMismatch(override val path: String, val expected: String, val actual: String) :
	CaseProblem {
	override fun message() = "$path was changed: sha256 is $actual, the manifest has $expected"
}

/**
 * The first observation whose link to the chain does not hold; [ChainBroken.index] counts from 0.
 */
data class ChainBroken(override val path: String, val index: Int, val detail: String) :
	CaseProblem {
	override fun message() = "observation chain broken at entry $index ($path): $detail"
}

sealed interface CaseRead {
	data class Loaded(val case: CaseFile) : CaseRead

	data class Failed(val problem: CaseProblem) : CaseRead
}

sealed interface CaseWrite {
	data class Written(val files: Int) : CaseWrite

	data class Failed(val path: String) : CaseWrite
}
