package dev.gmitch215.drift.ci

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonString
import dev.gmitch215.drift.json.JsonValue

/** Reads CI service JSON, where durations may be fractional; those numbers become strings. */
object LooseJson {
    private const val MILLIS = 1000L
    private const val FRACTION_DIGITS = 3

    fun parse(text: String): JsonValue = CanonicalJson.parse(quoteDecimals(text))

    fun quoteDecimals(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        out.append(text[i + 1])
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                    i++
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                c == '-' || c in '0'..'9' -> {
                    var j = i + 1
                    while (j < text.length && (text[j] in '0'..'9' || text[j] in ".eE+-")) j++
                    val token = text.substring(i, j)
                    if (token.any { it == '.' || it == 'e' || it == 'E' }) {
                        out.append('"').append(token).append('"')
                    } else {
                        out.append(token)
                    }
                    i = j
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Milliseconds of a seconds value written as an integer or a plain decimal string. */
    fun millis(value: JsonValue?): Long? = when (value) {
        is JsonInt -> value.value.takeIf { it >= 0 }?.times(MILLIS)
        is JsonString -> decimalMillis(value.value)
        else -> null
    }

    private fun decimalMillis(text: String): Long? {
        val whole = text.substringBefore('.')
        val fraction = text.substringAfter('.', "")
        if (whole.isEmpty() || whole.any { it !in '0'..'9' } || whole.length > 12) return null
        if (fraction.any { it !in '0'..'9' }) return null
        val ms = fraction.take(FRACTION_DIGITS).padEnd(FRACTION_DIGITS, '0').toLong()
        return whole.toLong() * MILLIS + ms
    }
}
