// finding Y1; filed as KT-90534
// quote: "Enables case-insensitive matching. Case comparison is Unicode-aware."
// https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-regex-option/

fun box(): String {
    val sigma = "σ"
    val inputs = listOf("final sigma" to "ς", "capital sigma" to "Σ")
    val patterns = listOf(sigma, "^$sigma", "$sigma\$", "[$sigma]", "($sigma)", "$sigma+")
    for (p in patterns) {
        val re = Regex(p, RegexOption.IGNORE_CASE)
        val baseline = re.matches(sigma)
        for ((label, input) in inputs) {
            val got = re.matches(input)
            if (got != baseline) {
                return "Fail pattern $p on $label: got $got, " +
                    "expected $baseline (as for sigma)"
            }
        }
    }
    return "OK"
}
