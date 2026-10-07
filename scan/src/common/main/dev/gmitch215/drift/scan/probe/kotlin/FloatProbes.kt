package dev.gmitch215.drift.scan.probe.kotlin

import dev.gmitch215.drift.scan.probe.bits
import kotlin.math.sqrt

internal val floatProbes = listOf(
	KotlinProbe(
		id = "kotlin.double.tostring-boundaries",
		question = "Which of these Doubles print in plain form and which in E notation?",
		source = """
			for ((name, d) in listOf(
				"1e-4" to 1e-4, "1e-3" to 1e-3, "9.999e-4" to 9.999e-4, "9999999" to 9999999.0,
				"1e7" to 1e7, "12345678" to 12345678.0, "1e21" to 1e21, "1e22" to 1e22,
				"1e-7" to 1e-7, "100" to 100.0, "0.5" to 0.5,
			)) line(name, d)
		""",
	) {
		for ((name, d) in listOf(
			"1e-4" to 1e-4, "1e-3" to 1e-3, "9.999e-4" to 9.999e-4, "9999999" to 9999999.0,
			"1e7" to 1e7, "12345678" to 12345678.0, "1e21" to 1e21, "1e22" to 1e22,
			"1e-7" to 1e-7, "100" to 100.0, "0.5" to 0.5,
		)) {
			line(name, d)
		}
	},
	KotlinProbe(
		id = "kotlin.double.extremes",
		question = "What do the extreme and special Doubles print?",
		source = """
			line("min-value", Double.MIN_VALUE)
			line("max-value", Double.MAX_VALUE)
			line("0.1+0.2", 0.1 + 0.2)
			line("neg-zero", -0.0)
			line("nan", Double.NaN)
			line("inf", Double.POSITIVE_INFINITY)
			line("neg-inf", Double.NEGATIVE_INFINITY)
			line("overflow", Double.MAX_VALUE * 2)
			line("underflow", Double.MIN_VALUE / 2)
			line("min-normal", 2.2250738585072014E-308)
			line("2^53+1", 9007199254740993.0)
		""",
	) {
		line("min-value", Double.MIN_VALUE)
		line("max-value", Double.MAX_VALUE)
		line("0.1+0.2", 0.1 + 0.2)
		line("neg-zero", -0.0)
		line("nan", Double.NaN)
		line("inf", Double.POSITIVE_INFINITY)
		line("neg-inf", Double.NEGATIVE_INFINITY)
		line("overflow", Double.MAX_VALUE * 2)
		line("underflow", Double.MIN_VALUE / 2)
		line("min-normal", 2.2250738585072014E-308)
		line("2^53+1", 9007199254740993.0)
	},
	KotlinProbe(
		id = "kotlin.double.shortest-digits",
		question = "Does Double.toString print the shortest digits that round-trip?",
		source = """
			for ((name, d) in listOf(
				"2e23" to 2e23, "1e23" to 1e23, "8.41e21" to 8.41e21, "5e-324" to 5e-324,
				"4.35" to 4.35, "0.3" to 0.3, "1/3" to 1.0 / 3.0, "2/3" to 2.0 / 3.0,
				"1e16/3" to 1e16 / 3.0, "0.1f" to 0.1f.toDouble(), "sqrt2" to sqrt(2.0),
				"1.1*1.1" to 1.1 * 1.1, "123456.789e-6" to 123456.789e-6,
			)) line(name, d)
		""",
	) {
		for ((name, d) in listOf(
			"2e23" to 2e23, "1e23" to 1e23, "8.41e21" to 8.41e21, "5e-324" to 5e-324,
			"4.35" to 4.35, "0.3" to 0.3, "1/3" to 1.0 / 3.0, "2/3" to 2.0 / 3.0,
			"1e16/3" to 1e16 / 3.0, "0.1f" to 0.1f.toDouble(), "sqrt2" to sqrt(2.0),
			"1.1*1.1" to 1.1 * 1.1, "123456.789e-6" to 123456.789e-6,
		)) {
			line(name, d)
		}
	},
	KotlinProbe(
		id = "kotlin.float.tostring",
		question = "What do these Floats print, and what is their Double widening?",
		source = """
			for ((name, f) in listOf(
				"0.1" to 0.1f, "1e7" to 1e7f, "1e10" to 1e10f, "16777217" to 16777217f,
				"min" to Float.MIN_VALUE, "max" to Float.MAX_VALUE, "1/3" to 1f / 3f,
				"1e-3" to 1e-3f, "1e-4" to 1e-4f, "9999999" to 9999999f, "0.1+0.2" to 0.1f + 0.2f,
			)) line(name, f)
			line("widen-0.1", 0.1f.toDouble())
			line("widen-sum", (0.1f + 0.2f).toDouble())
		""",
	) {
		for ((name, f) in listOf(
			"0.1" to 0.1f, "1e7" to 1e7f, "1e10" to 1e10f, "16777217" to 16777217f,
			"min" to Float.MIN_VALUE, "max" to Float.MAX_VALUE, "1/3" to 1f / 3f,
			"1e-3" to 1e-3f, "1e-4" to 1e-4f, "9999999" to 9999999f, "0.1+0.2" to 0.1f + 0.2f,
		)) {
			line(name, f)
		}
		line("widen-0.1", 0.1f.toDouble())
		line("widen-sum", (0.1f + 0.2f).toDouble())
	},
	KotlinProbe(
		id = "kotlin.double.parse",
		question = "Which of these strings does toDoubleOrNull accept, and as what value?",
		source = """
			for (s in listOf(
				"1e3", "1.", ".5", "NaN", "Infinity", "-Infinity", "+Infinity", "0x1p3", "1d",
				"1f", " 2 ", "1,5", "1_0", "1e400", "-1e400", "1e-400", "+.5e-2", "nan", "inf",
				"1e", "e1", "", "-", "00012", "1E+2", "0x10", "0.1e1", "1.0e+",
			)) line("'" + s + "'", s.toDoubleOrNull())
			line("float-above-max", "3.4028236e38".toFloatOrNull())
			line("float-1e39", "1e39".toFloatOrNull())
		""",
	) {
		for (s in listOf(
			"1e3", "1.", ".5", "NaN", "Infinity", "-Infinity", "+Infinity", "0x1p3", "1d",
			"1f", " 2 ", "1,5", "1_0", "1e400", "-1e400", "1e-400", "+.5e-2", "nan", "inf",
			"1e", "e1", "", "-", "00012", "1E+2", "0x10", "0.1e1", "1.0e+",
		)) {
			line("'" + s + "'", s.toDoubleOrNull())
		}
		line("float-above-max", "3.4028236e38".toFloatOrNull())
		line("float-1e39", "1e39".toFloatOrNull())
	},
	KotlinProbe(
		id = "kotlin.numbers.float-conversions",
		question = "How do out-of-range floating point conversions behave?",
		source = """
			line("nan-int", Double.NaN.toInt())
			line("1e20-int", 1e20.toInt())
			line("-1e20-int", (-1e20).toInt())
			line("1e20-long", 1e20.toLong())
			line("trunc", 3.99.toInt().toString() + " " + (-3.99).toInt())
			line("float-3e9-int", 3e9f.toInt())
			line("1e40-float", 1e40.toFloat())
			line("1e-50-float", 1e-50.toFloat())
			line("long-max-double", Long.MAX_VALUE.toDouble())
			line("long-max-float", Long.MAX_VALUE.toFloat())
			line("long-max-roundtrip", Long.MAX_VALUE.toDouble().toLong())
			line("int-max-float", Int.MAX_VALUE.toFloat())
			line("ulong-max-double", ULong.MAX_VALUE.toDouble())
			line("neg-uint", (-1.0).toUInt())
			line("1e20-ulong", 1e20.toULong())
			line("nan-uint", Double.NaN.toUInt())
		""",
	) {
		line("nan-int", Double.NaN.toInt())
		line("1e20-int", 1e20.toInt())
		line("-1e20-int", (-1e20).toInt())
		line("1e20-long", 1e20.toLong())
		line("trunc", 3.99.toInt().toString() + " " + (-3.99).toInt())
		line("float-3e9-int", 3e9f.toInt())
		line("1e40-float", 1e40.toFloat())
		line("1e-50-float", 1e-50.toFloat())
		line("long-max-double", Long.MAX_VALUE.toDouble())
		line("long-max-float", Long.MAX_VALUE.toFloat())
		line("long-max-roundtrip", Long.MAX_VALUE.toDouble().toLong())
		line("int-max-float", Int.MAX_VALUE.toFloat())
		line("ulong-max-double", ULong.MAX_VALUE.toDouble())
		line("neg-uint", (-1.0).toUInt())
		line("1e20-ulong", 1e20.toULong())
		line("nan-uint", Double.NaN.toUInt())
	},
	KotlinProbe(
		id = "kotlin.double.nan-bits",
		question = "Which bit patterns do NaN values have?",
		source = """
			val zero = "0".toDouble()
			line("nan-raw", bits(Double.NaN))
			line("0/0-raw", bits(0.0 / zero))
			line("neg-nan-raw", bits(-Double.NaN))
			line("sqrt-neg-raw", bits(sqrt(-1.0)))
			line("inf-minus-inf-raw", bits(Double.POSITIVE_INFINITY - Double.POSITIVE_INFINITY))
			line("payload-raw", bits(Double.fromBits(0x7ff0000000000001)))
			val payload = Double.fromBits(0x7ff0000000000001)
			line("payload-bits", payload.toBits().toULong().toString(16))
			line("payload-to-float", bits(Double.fromBits(0x7ff8000000000123).toFloat()))
			line("float-nan-raw", bits(Float.NaN))
			line("float-payload-raw", bits(Float.fromBits(0x7f800001)))
			line("neg-zero-raw", bits(-0.0))
		""",
	) {
		val zero = "0".toDouble()
		line("nan-raw", bits(Double.NaN))
		line("0/0-raw", bits(0.0 / zero))
		line("neg-nan-raw", bits(-Double.NaN))
		line("sqrt-neg-raw", bits(sqrt(-1.0)))
		line("inf-minus-inf-raw", bits(Double.POSITIVE_INFINITY - Double.POSITIVE_INFINITY))
		line("payload-raw", bits(Double.fromBits(0x7ff0000000000001)))
		val payload = Double.fromBits(0x7ff0000000000001)
		line("payload-bits", payload.toBits().toULong().toString(16))
		line("payload-to-float", bits(Double.fromBits(0x7ff8000000000123).toFloat()))
		line("float-nan-raw", bits(Float.NaN))
		line("float-payload-raw", bits(Float.fromBits(0x7f800001)))
		line("neg-zero-raw", bits(-0.0))
	},
	KotlinProbe(
		id = "kotlin.double.equality-order",
		question = "How do NaN and signed zero compare, statically and as Any?",
		source = """
			val nan = "NaN".toDouble()
			val a: Any = nan
			val b: Any = "NaN".toDouble()
			val z: Any = 0.0
			val nz: Any = -0.0
			val fz: Any = 0.0f
			val fnz: Any = -0.0f
			line("nan==nan", nan == "NaN".toDouble())
			line("any-nan==any-nan", a == b)
			line("0.0==-0.0", 0.0 == -0.0)
			line("any-0.0==any-neg-0.0", z == nz)
			line("equals", 0.0.equals(-0.0))
			line("compareTo", 0.0.compareTo(-0.0))
			line("nan-compareTo-inf", nan.compareTo(Double.POSITIVE_INFINITY))
			line("minOf", minOf(0.0, -0.0))
			line("maxOf", maxOf(-0.0, 0.0))
			line("min-nan", minOf(1.0, nan))
			line("distinct", listOf(0.0, -0.0).distinct())
			line("set-nan", setOf(nan, nan).size)
			line("list-nan", listOf(nan) == listOf(nan))
			line("nan>1", nan > 1.0)
			line("float-any-eq", fz == fnz)
		""",
	) {
		val nan = "NaN".toDouble()
		val a: Any = nan
		val b: Any = "NaN".toDouble()
		val z: Any = 0.0
		val nz: Any = -0.0
		val fz: Any = 0.0f
		val fnz: Any = -0.0f
		line("nan==nan", nan == "NaN".toDouble())
		line("any-nan==any-nan", a == b)
		line("0.0==-0.0", 0.0 == -0.0)
		line("any-0.0==any-neg-0.0", z == nz)
		line("equals", 0.0.equals(-0.0))
		line("compareTo", 0.0.compareTo(-0.0))
		line("nan-compareTo-inf", nan.compareTo(Double.POSITIVE_INFINITY))
		line("minOf", minOf(0.0, -0.0))
		line("maxOf", maxOf(-0.0, 0.0))
		line("min-nan", minOf(1.0, nan))
		line("distinct", listOf(0.0, -0.0).distinct())
		line("set-nan", setOf(nan, nan).size)
		line("list-nan", listOf(nan) == listOf(nan))
		line("nan>1", nan > 1.0)
		line("float-any-eq", fz == fnz)
	},
)
