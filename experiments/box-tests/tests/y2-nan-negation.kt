// finding Y2; KT-88082 (Fixed), test idea taken into KT-87211
// relation: negating a NaN constant and a runtime NaN must give the same raw bits
// https://youtrack.jetbrains.com/issue/KT-88082 (URL pattern, not fetched)

fun box(): String {
    val runtimeNaN = "NaN".toDouble()
    val constBits = (-Double.NaN).toRawBits()
    val runtimeBits = (-runtimeNaN).toRawBits()
    if (constBits != runtimeBits) {
        return "Fail Double: constant ${constBits.toULong().toString(16)}, " +
            "runtime ${runtimeBits.toULong().toString(16)}"
    }

    val runtimeFloatNaN = "NaN".toFloat()
    val constFloatBits = (-Float.NaN).toRawBits()
    val runtimeFloatBits = (-runtimeFloatNaN).toRawBits()
    if (constFloatBits != runtimeFloatBits) {
        return "Fail Float: constant ${constFloatBits.toUInt().toString(16)}, " +
            "runtime ${runtimeFloatBits.toUInt().toString(16)}"
    }
    return "OK"
}
