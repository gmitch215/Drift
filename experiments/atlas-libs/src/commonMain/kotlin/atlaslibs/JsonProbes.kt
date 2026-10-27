package atlaslibs

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

@Serializable
data class Plain(val a: Int = 1, val b: String? = null, val c: List<Int> = emptyList())

@Serializable
data class Wide(val l: Long, val d: Double, val f: Float)

@Serializable
enum class Color { RED, GREEN }

@Serializable
data class WithEnum(val color: Color = Color.RED, val n: Int = 5)

private val lenient = Json { allowSpecialFloatingPointValues = true }

val jsonProbes = listOf(
    Probe("json.numbers") {
        val doubles = listOf(
            0.1 + 0.2, 1e21, 1e-7, 5e-324, 1.7976931348623157e308, 100.0, 1.0E10, -0.0,
            123456789012345680.0, 9007199254740993.0, 4.35, 0.3, 2.2250738585072014E-308,
            1e22, 1e23, 8.41e21, 5e-5, 0.001, 0.0001, 1234567.0, 12345678912345.0,
            4.9e-324, 1.0E-5, 9.999999999999999e22, 2e-323, 123456789.123456789,
        )
        for (d in doubles) {
            attempt("encode-double[${d.toRawBits().toString(16)}]") {
                Json.encodeToString(Double.serializer(), d)
            }
            attempt("primitive-content[${d.toRawBits().toString(16)}]") { JsonPrimitive(d).content }
        }
        val floats = listOf(
            0.1f, 16777216f, 3.4028235E38f, 1.17549435E-38f, 1.4E-45f, 1e10f, 1e-5f, 123456.79f,
            1.0E7f,
        )
        for (f in floats) {
            attempt("encode-float[${f.toRawBits().toString(16)}]") {
                Json.encodeToString(Float.serializer(), f)
            }
        }
        attempt("encode-long-max") { Json.encodeToString(Long.serializer(), Long.MAX_VALUE) }
        attempt("encode-long-min") { Json.encodeToString(Long.serializer(), Long.MIN_VALUE) }
        attempt("encode-long-2^53+1") { Json.encodeToString(Long.serializer(), (1L shl 53) + 1) }
        attempt("decode-long[9007199254740993]") {
            Json.decodeFromString(Long.serializer(), "9007199254740993")
        }
        attempt("decode-long[9223372036854775807]") {
            Json.decodeFromString(Long.serializer(), "9223372036854775807")
        }
        attempt("decode-long[9223372036854775808]") {
            Json.decodeFromString(Long.serializer(), "9223372036854775808")
        }
        attempt("decode-long[-9223372036854775808]") {
            Json.decodeFromString(Long.serializer(), "-9223372036854775808")
        }
        attempt("decode-long[1.0]") { Json.decodeFromString(Long.serializer(), "1.0") }
        attempt("decode-long[1e2]") { Json.decodeFromString(Long.serializer(), "1e2") }
        attempt("decode-int[2147483648]") { Json.decodeFromString(Int.serializer(), "2147483648") }
        attempt("decode-double[9007199254740993]") {
            Json.decodeFromString(Double.serializer(), "9007199254740993").toRawBits().toString(16)
        }
        attempt("decode-double[1e400]") {
            Json.decodeFromString(Double.serializer(), "1e400").toRawBits().toString(16)
        }
        attempt("decode-double[-1e400]") {
            Json.decodeFromString(Double.serializer(), "-1e400").toRawBits().toString(16)
        }
        attempt("decode-double[1e-400]") {
            Json.decodeFromString(Double.serializer(), "1e-400").toRawBits().toString(16)
        }
        attempt("decode-double[0.1e1]") {
            Json.decodeFromString(Double.serializer(), "0.1e1").toRawBits().toString(16)
        }
        attempt("decode-float[1e39]") {
            Json.decodeFromString(Float.serializer(), "1e39").toRawBits().toString(16)
        }
        attempt("decode-double[01]") {
            Json.decodeFromString(Double.serializer(), "01").toRawBits().toString(16)
        }
        attempt("decode-double[-0]") {
            Json.decodeFromString(Double.serializer(), "-0").toRawBits().toString(16)
        }
        attempt("wide-roundtrip") {
            val w = Wide(Long.MAX_VALUE, 1e21, 0.1f)
            val s = Json.encodeToString(w)
            s + " | " + Json.decodeFromString<Wide>(s).let {
                "${it.l} ${it.d.toRawBits().toString(16)} ${it.f.toRawBits().toString(16)}"
            }
        }
    },
    Probe("json.special-floats") {
        attempt("encode-nan-default") { Json.encodeToString(Double.serializer(), Double.NaN) }
        attempt("encode-inf-default") {
            Json.encodeToString(Double.serializer(), Double.POSITIVE_INFINITY)
        }
        attempt("encode-float-nan-default") { Json.encodeToString(Float.serializer(), Float.NaN) }
        attempt("encode-nan-allowed") { lenient.encodeToString(Double.serializer(), Double.NaN) }
        attempt("encode-inf-allowed") {
            lenient.encodeToString(Double.serializer(), Double.POSITIVE_INFINITY)
        }
        attempt("encode-neginf-allowed") {
            lenient.encodeToString(Double.serializer(), Double.NEGATIVE_INFINITY)
        }
        attempt("encode-float-inf-allowed") {
            lenient.encodeToString(Float.serializer(), Float.POSITIVE_INFINITY)
        }
        attempt("decode-nan-bare-default") { Json.decodeFromString(Double.serializer(), "NaN") }
        attempt("decode-nan-bare-allowed") { lenient.decodeFromString(Double.serializer(), "NaN") }
        attempt("decode-nan-quoted-default") {
            Json.decodeFromString(Double.serializer(), "\"NaN\"")
        }
        attempt("decode-nan-quoted-allowed") {
            lenient.decodeFromString(Double.serializer(), "\"NaN\"")
        }
        attempt("decode-inf-bare-allowed") {
            lenient.decodeFromString(Double.serializer(), "Infinity")
        }
        attempt("decode-neginf-bare-allowed") {
            lenient.decodeFromString(Double.serializer(), "-Infinity")
        }
        attempt("decode-inf-bare-default") {
            Json.decodeFromString(Double.serializer(), "Infinity")
        }
        attempt("decode-inf-quoted-default") {
            Json.decodeFromString(Double.serializer(), "\"Infinity\"")
        }
        attempt("decode-nan-lowercase-allowed") {
            lenient.decodeFromString(Double.serializer(), "nan")
        }
        val texts = listOf(
            "nan", "NaN", "inf", "Infinity", "-Infinity", "1e400", "0x10", "1_0", " 1", "1 ",
        )
        for (t in texts) {
            attempt("stdlib-toDouble[$t]") { t.toDouble().toRawBits().toString(16) }
        }
        attempt("primitive-nan") { JsonPrimitive(Double.NaN).content }
        attempt("primitive-nan-double") { JsonPrimitive(Double.NaN).double }
        attempt("primitive-inf") { JsonPrimitive(Double.POSITIVE_INFINITY).content }
        attempt("primitive-string-nan-double") { JsonPrimitive("NaN").double }
        attempt("primitive-string-inf-double") { JsonPrimitive("Infinity").double }
        attempt("element-parse-nan") { Json.parseToJsonElement("NaN") }
        attempt("element-parse-nan-allowed") { lenient.parseToJsonElement("[NaN, Infinity]") }
        attempt("element-tostring-nan") {
            JsonObject(mapOf("x" to JsonPrimitive(Double.NaN))).toString()
        }
        attempt("encode-element-nan-default") {
            Json.encodeToString(JsonElement.serializer(), JsonPrimitive(Double.NaN))
        }
        attempt("encode-negzero") { Json.encodeToString(Double.serializer(), -0.0) }
    },
    Probe("json.unicode") {
        val strings = listOf(
            "\u0000", "\u001f", "\u007f", "\u0080", "\u2028", "\ud83d\ude00", "\ud800", "\udc00",
            "a\ud800b", "\u00e9", "\"\\/", "\b\u000c\n\r\t", "\ufeff", "\uffff",
        )
        for (s in strings) {
            attempt("encode[${s.codes()}]") { Json.encodeToString(String.serializer(), s) }
        }
        val raws = listOf(
            "\"\\ud83d\\ude00\"", "\"\\uD800\"", "\"\\udc00\"", "\"\\ud83d\\u0041\"",
            "\"\\u00e9\"", "\"\\u00E9\"", "\"\\/\"", "\"\\q\"", "\"\\u12\"", "\"\\u12G4\"",
            "\"\u0000\"", "\"\n\"", "\"\t\"", "\"\u007f\"", "\"\ud800\"", "\"\\x41\"", "'a'",
            "\"\\0\"",
        )
        for (s in raws) {
            attempt("decode[${s.codes()}]") {
                Json.decodeFromString(String.serializer(), s).codes()
            }
        }
        attempt("decode-lenient-single-quote") {
            Json { isLenient = true }.decodeFromString(String.serializer(), "'a'")
        }
        attempt("decode-lenient-bare") {
            Json { isLenient = true }.decodeFromString(String.serializer(), "abc")
        }
        attempt("decode-bom") { Json.decodeFromString(String.serializer(), "\ufeff\"a\"") }
        attempt("key-escape") {
            Json.encodeToString(
                MapSerializer(String.serializer(), Int.serializer()),
                mapOf("\u0001\"" to 1),
            )
        }
        attempt("decode-key-escape") {
            Json.decodeFromString(
                MapSerializer(String.serializer(), Int.serializer()),
                "{\"\\u0041\\n\":1}",
            ).keys.map { it.codes() }
        }
    },
    Probe("json.duplicate-keys") {
        attempt("object-dup") {
            Json.decodeFromString(JsonObject.serializer(), "{\"a\":1,\"a\":2}")
        }
        attempt("element-dup-order") { Json.parseToJsonElement("{\"b\":1,\"a\":2,\"b\":3}") }
        attempt("class-dup") { Json.decodeFromString<Plain>("{\"a\":1,\"a\":2}") }
        attempt("class-dup-ignore") {
            Json { ignoreUnknownKeys = true }.decodeFromString<Plain>("{\"x\":1,\"x\":2,\"a\":3}")
        }
        attempt("map-dup") {
            Json.decodeFromString(
                MapSerializer(String.serializer(), Int.serializer()),
                "{\"a\":1,\"a\":2}",
            )
        }
        attempt("map-int-key-dup") {
            Json.decodeFromString(
                MapSerializer(Int.serializer(), Int.serializer()),
                "{\"1\":1,\"01\":2}",
            )
        }
        attempt("object-order-large") {
            Json.parseToJsonElement(
                "{" + (20 downTo 1).joinToString(",") { "\"k$it\":$it" } + "}",
            ).toString()
        }
        attempt("empty-key") { Json.parseToJsonElement("{\"\":1}") }
        attempt("trailing-comma") { Json.parseToJsonElement("[1,]") }
        attempt("trailing-comma-allowed") {
            Json { allowTrailingComma = true }.parseToJsonElement("[1,]")
        }
        attempt("comments") { Json.parseToJsonElement("[1/*x*/]") }
        attempt("comments-allowed") {
            Json { allowComments = true }.parseToJsonElement("[1/*x*/,2//y\n]")
        }
        attempt("deep-nesting") {
            Json.parseToJsonElement("[".repeat(1000) + "]".repeat(1000)).toString().length
        }
    },
    Probe("json.element-accessors") {
        attempt("string-1.5-int") { JsonPrimitive("1.5").int }
        attempt("string-1-int") { JsonPrimitive("1").int }
        attempt("double-1.5-long") { JsonPrimitive(1.5).long }
        attempt("double-1.5-intOrNull") { JsonPrimitive(1.5).intOrNull }
        attempt("string-1e2-int") { JsonPrimitive("1e2").int }
        attempt("string-1.0-int") { JsonPrimitive("1.0").int }
        attempt("double-2.0-int") { JsonPrimitive(2.0).int }
        attempt("double-2.0-intOrNull") { JsonPrimitive(2.0).intOrNull }
        attempt("string-long-max+1-longOrNull") { JsonPrimitive("9223372036854775808").longOrNull }
        attempt("string-long-max+1-double") { JsonPrimitive("9223372036854775808").double }
        attempt("long-2^53+1-double") { JsonPrimitive((1L shl 53) + 1).double }
        attempt("long-2^53+1-content") { JsonPrimitive((1L shl 53) + 1).content }
        attempt("string-hex-int") { JsonPrimitive("0x10").intOrNull }
        attempt("string-padded-int") { JsonPrimitive(" 1 ").intOrNull }
        attempt("string-plus-int") { JsonPrimitive("+1").intOrNull }
        attempt("string-empty-int") { JsonPrimitive("").intOrNull }
        attempt("bool-TRUE") { JsonPrimitive("TRUE").booleanOrNull }
        attempt("bool-true") { JsonPrimitive("true").booleanOrNull }
        attempt("bool-quoted-parse") {
            Json.parseToJsonElement("\"true\"").let { (it as JsonPrimitive).booleanOrNull }
        }
        attempt("null-content") { JsonNull.content }
        attempt("null-isString") { JsonNull.isString }
        attempt("float-1e39") { JsonPrimitive("1e39").floatOrNull }
        attempt("float-from-double") { JsonPrimitive(0.1).float }
        attempt("double-nan-string") { JsonPrimitive("nan").doubleOrNull }
        attempt("double-1e400") { JsonPrimitive("1e400").doubleOrNull }
        attempt("double-big-literal-content") {
            Json.parseToJsonElement("1E2").let { (it as JsonPrimitive).content }
        }
        attempt("double-big-literal-double") {
            Json.parseToJsonElement("1E2").let { (it as JsonPrimitive).double }
        }
        attempt("double-big-literal-int") {
            Json.parseToJsonElement("1E2").let { (it as JsonPrimitive).intOrNull }
        }
        attempt("parse-number-content") {
            Json.parseToJsonElement("12345678901234567890").let { (it as JsonPrimitive).content }
        }
        attempt("parse-number-long") {
            Json.parseToJsonElement("12345678901234567890").let { (it as JsonPrimitive).longOrNull }
        }
        attempt("parse-number-double") {
            Json.parseToJsonElement("12345678901234567890").let { (it as JsonPrimitive).double }
        }
        attempt("parse-neg-zero-content") {
            Json.parseToJsonElement("-0").let { (it as JsonPrimitive).content }
        }
        attempt("parse-neg-zero-double") {
            Json.parseToJsonElement("-0").let { (it as JsonPrimitive).double }
        }
        attempt("parse-neg-zero-int") {
            Json.parseToJsonElement("-0").let { (it as JsonPrimitive).int }
        }
        attempt("parse-1.0-long") {
            Json.parseToJsonElement("1.0").let { (it as JsonPrimitive).longOrNull }
        }
        attempt("equals-1-1.0") { JsonPrimitive(1) == JsonPrimitive(1.0) }
        attempt("equals-0.0--0.0") { JsonPrimitive(0.0) == JsonPrimitive(-0.0) }
        attempt("hash-equal-strings") {
            JsonPrimitive("1").hashCode() == JsonPrimitive(1).hashCode()
        }
    },
    Probe("json.defaults-nulls") {
        val explicit = Json { encodeDefaults = true }
        val noNulls = Json { explicitNulls = false }
        attempt("encode-default") { Json.encodeToString(Plain()) }
        attempt("encode-defaults-on") { explicit.encodeToString(Plain()) }
        attempt("encode-explicit-nulls-off") { noNulls.encodeToString(Plain(b = null)) }
        attempt("encode-explicit-nulls-on") { explicit.encodeToString(Plain(b = null)) }
        attempt("decode-missing") { Json.decodeFromString<Plain>("{}") }
        attempt("decode-null-nullable") { Json.decodeFromString<Plain>("{\"b\":null}") }
        attempt("decode-null-nonnull") { Json.decodeFromString<Plain>("{\"a\":null}") }
        attempt("decode-null-nonnull-coerce") {
            Json { coerceInputValues = true }.decodeFromString<Plain>("{\"a\":null}")
        }
        attempt("decode-unknown-key") { Json.decodeFromString<Plain>("{\"z\":1}") }
        attempt("decode-unknown-key-ignore") {
            Json { ignoreUnknownKeys = true }.decodeFromString<Plain>("{\"z\":{\"q\":[1,2,{}]}}")
        }
        attempt("decode-unknown-enum") { Json.decodeFromString<WithEnum>("{\"color\":\"BLUE\"}") }
        attempt("decode-unknown-enum-coerce") {
            Json { coerceInputValues = true }.decodeFromString<WithEnum>("{\"color\":\"BLUE\"}")
        }
        attempt("decode-enum-case") {
            Json { decodeEnumsCaseInsensitive = true }
                .decodeFromString<WithEnum>("{\"color\":\"green\"}")
        }
        attempt("decode-wrong-type") { Json.decodeFromString<Plain>("{\"a\":\"1\"}") }
        attempt("decode-wrong-type-lenient") {
            Json { isLenient = true }.decodeFromString<Plain>("{\"a\":\"1\"}")
        }
        attempt("decode-string-from-number") { Json.decodeFromString<Plain>("{\"b\":1}") }
        attempt("decode-string-from-number-lenient") {
            Json { isLenient = true }.decodeFromString<Plain>("{\"b\":1}")
        }
        attempt("decode-list-null-elem") {
            Json.decodeFromString(ListSerializer(Int.serializer()), "[1,null]")
        }
        attempt("map-double-key") {
            Json.encodeToString(
                MapSerializer(Double.serializer(), Int.serializer()),
                mapOf(1.0 to 1, 1e21 to 2, 0.1 to 3),
            )
        }
        attempt("map-long-key") {
            Json.encodeToString(
                MapSerializer(Long.serializer(), Int.serializer()),
                mapOf(Long.MAX_VALUE to 1),
            )
        }
        attempt("map-float-key") {
            Json.encodeToString(
                MapSerializer(Float.serializer(), Int.serializer()),
                mapOf(0.1f to 1, 1e10f to 2),
            )
        }
        attempt("pretty-print") {
            Json { prettyPrint = true }.encodeToString(Plain(c = listOf(1, 2)))
        }
        attempt("pretty-print-empty") {
            Json { prettyPrint = true }.encodeToString(Plain(c = emptyList()))
        }
    },
)
