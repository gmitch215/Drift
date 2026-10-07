package dev.gmitch215.drift.bench

enum class Problem {
	MISSING_FIELD,
	BAD_VALUE,
	BAD_TYPE,
	BAD_DIMENSION,
	UNKNOWN_PLACEHOLDER,
	DUPLICATE_ID,
	TOO_FEW_VARIANTS,
	INCONSISTENT,
	NO_SALT,
}

class TemplateException(val problem: Problem, val where: String, detail: String) :
	Exception("$where: $detail ($problem)")

class SealedException(id: String) :
	Exception("$id is in the sealed test split and is never run, fit or scored here")

object Dimensions {
	val all = listOf(
		"os",
		"kernel",
		"cpu",
		"cpu-limit",
		"memory",
		"runtime",
		"compiler",
		"build",
		"userland",
		"env",
		"locale",
		"limits",
		"network",
		"browser",
	)

	val runtimes = mapOf(
		"sh" to ("prog.sh" to "sh"),
		"python3" to ("prog.py" to "python3"),
		"node" to ("prog.js" to "node"),
		"java" to ("Prog.java" to "java"),
	)

	val directions = listOf("lower", "higher", "changed", "added", "removed")
}
