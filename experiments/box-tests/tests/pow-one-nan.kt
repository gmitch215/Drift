// finding 1^nan (Atlas kotlin.math.pow-special-cases); no issue filed
// quote (Double.pow): "`b.pow(NaN)` is `NaN`"
// (kotlin master, libraries/stdlib/common/src/kotlin/MathH.kt:460)
// https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/pow.html

import kotlin.math.pow

fun box(): String {
    val r = 1.0.pow(Double.NaN)
    return if (r.isNaN()) "OK" else "Fail 1.0.pow(NaN): got $r, expected NaN"
}
