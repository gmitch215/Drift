package dev.gmitch215.drift.know

/** [ConstraintReport.pending] lists `pending-scanner` rules: visible debt, not a failure. */
data class ConstraintReport(
	val rules: Int,
	val violations: List<Violation>,
	val pending: List<String>,
) {
	val ok: Boolean get() = violations.isEmpty()

	val pendingCount: Int get() = pending.size
}

object Constraints {
	/** The gate over a whole rule set: per-rule checks, unique ids and fixture evaluation. */
	fun check(rules: List<Rule>): ConstraintReport {
		val violations = mutableListOf<Violation>()
		val seen = mutableSetOf<String>()
		for (rule in rules) {
			if (!seen.add(rule.id)) violations += Violation(rule.id, "id", "duplicate id")
			violations += RuleCompiler.validate(rule)
			violations += fixtures(rule)
		}
		return ConstraintReport(
			rules.size,
			violations,
			rules.filter { it.status == Status.PENDING_SCANNER }.map { it.id }.sorted(),
		)
	}

	/** Every positive fixture must match and every negative one must not (unknown passes). */
	fun fixtures(rule: Rule): List<Violation> {
		val out = mutableListOf<Violation>()
		rule.fixtures.positive.forEachIndexed { i, f ->
			val v = Detect.match(rule, f.a, f.b).verdict
			if (v != Verdict.MATCH) {
				out += Violation(rule.id, "fixtures.positive[$i]", "'${f.name}' gave $v")
			}
		}
		rule.fixtures.negative.forEachIndexed { i, f ->
			val v = Detect.match(rule, f.a, f.b).verdict
			if (v == Verdict.MATCH) {
				out += Violation(rule.id, "fixtures.negative[$i]", "'${f.name}' gave MATCH")
			}
		}
		return out
	}
}
