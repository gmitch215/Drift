// finding K1 (draft, not filed); related KT-62763 (Fixed, 2.0.0) and KT-39891 (Fixed, 1.6.20-M1)
// quote (Long.toString): "Returns a string representation of the object."
// https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/to-string.html

inline fun wrap(block: () -> Any?): String = "ok:" + block()

fun templateMatchesToString(v: Long): String? {
    val got = wrap { "$v" }
    val want = "ok:" + v.toString()
    return if (got == want) null else "wrap template gave $got, toString gave $want for $v"
}

fun box(): String {
    templateMatchesToString(9007199254740993L)?.let { return "Fail 2^53+1: $it" }
    templateMatchesToString(Long.MAX_VALUE)?.let { return "Fail Long.MAX_VALUE: $it" }
    return "OK"
}
