package dev.gmitch215.drift.scan.probe.kotlin

import dev.gmitch215.drift.scan.probe.bits
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.math.tanh

internal val mathProbes = listOf(
	KotlinProbe(
		id = "kotlin.math.transcendental-bits",
		question = "Which last bits do the kotlin.math functions return?",
		source = """
			line("sin1", bits(sin(1.0)))
			line("cos1", bits(cos(1.0)))
			line("tan1", bits(tan(1.0)))
			line("exp1", bits(exp(1.0)))
			line("ln10", bits(ln(10.0)))
			line("log10-1000", bits(log10(1000.0)))
			line("log2-10", bits(log2(10.0)))
			line("atan2", bits(atan2(1.0, 2.0)))
			line("atan1", bits(atan(1.0)))
			line("asin-half", bits(asin(0.5)))
			line("acos-half", bits(acos(0.5)))
			line("sinh1", bits(sinh(1.0)))
			line("cosh1", bits(cosh(1.0)))
			line("tanh1", bits(tanh(1.0)))
			line("cbrt2", bits(cbrt(2.0)))
			line("cbrt27", bits(cbrt(27.0)))
			line("expm1-tiny", bits(expm1(1e-10)))
			line("ln1p-tiny", bits(ln1p(1e-10)))
			line("sin-pi", bits(sin(PI)))
			line("sin-1e22", bits(sin(1e22)))
			line("cos-1e22", bits(cos(1e22)))
			line("exp-709", bits(exp(709.0)))
			line("exp-neg745", bits(exp(-745.0)))
		""",
	) {
		line("sin1", bits(sin(1.0)))
		line("cos1", bits(cos(1.0)))
		line("tan1", bits(tan(1.0)))
		line("exp1", bits(exp(1.0)))
		line("ln10", bits(ln(10.0)))
		line("log10-1000", bits(log10(1000.0)))
		line("log2-10", bits(log2(10.0)))
		line("atan2", bits(atan2(1.0, 2.0)))
		line("atan1", bits(atan(1.0)))
		line("asin-half", bits(asin(0.5)))
		line("acos-half", bits(acos(0.5)))
		line("sinh1", bits(sinh(1.0)))
		line("cosh1", bits(cosh(1.0)))
		line("tanh1", bits(tanh(1.0)))
		line("cbrt2", bits(cbrt(2.0)))
		line("cbrt27", bits(cbrt(27.0)))
		line("expm1-tiny", bits(expm1(1e-10)))
		line("ln1p-tiny", bits(ln1p(1e-10)))
		line("sin-pi", bits(sin(PI)))
		line("sin-1e22", bits(sin(1e22)))
		line("cos-1e22", bits(cos(1e22)))
		line("exp-709", bits(exp(709.0)))
		line("exp-neg745", bits(exp(-745.0)))
	},
	KotlinProbe(
		id = "kotlin.math.pow-special-cases",
		question = "What does pow return for NaN, infinities, zeros and huge exponents?",
		source = """
			val nan = Double.NaN
			val inf = Double.POSITIVE_INFINITY
			line("nan^0", nan.pow(0.0))
			line("1^nan", 1.0.pow(nan))
			line("1^inf", 1.0.pow(inf))
			line("-1^inf", (-1.0).pow(inf))
			line("2^nan", 2.0.pow(nan))
			line("0^-1", 0.0.pow(-1.0))
			line("-0^-1", (-0.0).pow(-1.0))
			line("-0^-2", (-0.0).pow(-2.0))
			line("-8^third", (-8.0).pow(1.0 / 3.0))
			line("2^1024", 2.0.pow(1024.0))
			line("2^-1075", 2.0.pow(-1075.0))
			line("2^-1074", 2.0.pow(-1074.0))
			line("10^-5", bits(10.0.pow(-5.0)))
			line("2^half", bits(2.0.pow(0.5)))
			line("2^int-10", 2.0.pow(10))
			line("1.1^int-100", bits(1.1.pow(100)))
			line("int-pow-nan", nan.pow(0))
			line("inf^0", inf.pow(0.0))
			line("inf^-1", inf.pow(-1.0))
		""",
	) {
		val nan = Double.NaN
		val inf = Double.POSITIVE_INFINITY
		line("nan^0", nan.pow(0.0))
		line("1^nan", 1.0.pow(nan))
		line("1^inf", 1.0.pow(inf))
		line("-1^inf", (-1.0).pow(inf))
		line("2^nan", 2.0.pow(nan))
		line("0^-1", 0.0.pow(-1.0))
		line("-0^-1", (-0.0).pow(-1.0))
		line("-0^-2", (-0.0).pow(-2.0))
		line("-8^third", (-8.0).pow(1.0 / 3.0))
		line("2^1024", 2.0.pow(1024.0))
		line("2^-1075", 2.0.pow(-1075.0))
		line("2^-1074", 2.0.pow(-1074.0))
		line("10^-5", bits(10.0.pow(-5.0)))
		line("2^half", bits(2.0.pow(0.5)))
		line("2^int-10", 2.0.pow(10))
		line("1.1^int-100", bits(1.1.pow(100)))
		line("int-pow-nan", nan.pow(0))
		line("inf^0", inf.pow(0.0))
		line("inf^-1", inf.pow(-1.0))
	},
	KotlinProbe(
		id = "kotlin.math.rounding",
		question = "How do round and roundToInt treat ties and values just below a tie?",
		source = """
			for ((name, d) in listOf(
				"0.5" to 0.5, "1.5" to 1.5, "2.5" to 2.5, "-0.5" to -0.5, "-1.5" to -1.5,
				"-2.5" to -2.5, "0.49999999999999994" to 0.49999999999999994,
				"2.4999999999999996" to 2.4999999999999996,
				"4503599627370497" to 4503599627370497.0,
			)) {
				val r = round(d)
				line(name, "round=" + r + " int=" + d.roundToInt() + " long=" + d.roundToLong())
			}
			outcome("roundToInt-nan") { Double.NaN.roundToInt() }
			line("roundToInt-1e10", 1e10.roundToInt())
			line("roundToInt-int-max-half", 2147483647.5.roundToInt())
			line("float-0.5", 0.5f.roundToInt())
			line("float-2.5", 2.5f.roundToInt())
			line("float-neg-2.5", (-2.5f).roundToInt())
			line("float-8388609", 8388609f.roundToInt())
		""",
	) {
		for ((name, d) in listOf(
			"0.5" to 0.5, "1.5" to 1.5, "2.5" to 2.5, "-0.5" to -0.5, "-1.5" to -1.5,
			"-2.5" to -2.5, "0.49999999999999994" to 0.49999999999999994,
			"2.4999999999999996" to 2.4999999999999996,
			"4503599627370497" to 4503599627370497.0,
		)) {
			val r = round(d)
			line(name, "round=" + r + " int=" + d.roundToInt() + " long=" + d.roundToLong())
		}
		outcome("roundToInt-nan") { Double.NaN.roundToInt() }
		line("roundToInt-1e10", 1e10.roundToInt())
		line("roundToInt-int-max-half", 2147483647.5.roundToInt())
		line("float-0.5", 0.5f.roundToInt())
		line("float-2.5", 2.5f.roundToInt())
		line("float-neg-2.5", (-2.5f).roundToInt())
		line("float-8388609", 8388609f.roundToInt())
	},
)
