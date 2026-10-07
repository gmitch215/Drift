package dev.gmitch215.drift.scan.probe

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

internal fun bits(d: Double): String = d.toRawBits().toULong().toString(16)

internal fun bits(f: Float): String = f.toRawBits().toUInt().toString(16)

internal val numericProbes = listOf(
	Probe("numeric.double-tostring", Family.NUMERIC) {
		val values = listOf(
			0.1, 100.0, 1e7, 1e21, 1e-7, 123456789.0, 0.1 + 0.2, Double.MIN_VALUE, Double.MAX_VALUE,
			Double.NaN, -0.0, Double.POSITIVE_INFINITY,
		)
		for (d in values) line("$d ${bits(d)}")
	},
	Probe("numeric.float-tostring", Family.NUMERIC) {
		val values =
			listOf(0.1f, 1e7f, 1e10f, 16777217f, Float.MIN_VALUE, Float.MAX_VALUE, 3.4028235e38f)
		for (f in values) line("$f ${bits(f)}")
	},
	Probe("numeric.int-overflow", Family.NUMERIC) {
		line("int-max-plus-one", Int.MAX_VALUE + 1)
		line("long-min-div-minus-one", Long.MIN_VALUE / -1)
		line("int-min-negate", -Int.MIN_VALUE)
		line("shl-33", 1 shl 33)
		line("long-shl-65", 1L shl 65)
		line("byte-wrap", (200).toByte())
		line("neg-rem", -7 % 3)
		line("floor-mod", (-7).mod(3))
	},
	Probe("numeric.float-to-int", Family.NUMERIC) {
		line("nan-int", Double.NaN.toInt())
		line("big-int", 1e20.toInt())
		line("neg-big-long", (-1e30).toLong())
		line("trunc-pos", 3.99.toInt())
		line("trunc-neg", (-3.99).toInt())
		line("inf-long", Double.POSITIVE_INFINITY.toLong())
		line("float-big-int", 3e9f.toInt())
	},
	Probe("numeric.math-bits", Family.NUMERIC) {
		line("sin1", bits(sin(1.0)))
		line("cos1", bits(cos(1.0)))
		line("tan1", bits(tan(1.0)))
		line("exp1", bits(exp(1.0)))
		line("ln10", bits(ln(10.0)))
		line("log10-1000", bits(log10(1000.0)))
		line("pow-2-half", bits(2.0.pow(0.5)))
		line("pow-10-neg5", bits(10.0.pow(-5.0)))
		line("sqrt2", bits(sqrt(2.0)))
		line("atan2", bits(atan2(1.0, 2.0)))
		line("sinh1", bits(sinh(1.0)))
		line("tanh1", bits(tanh(1.0)))
		line("cbrt2", bits(cbrt(2.0)))
		line("sin-pi", bits(sin(PI)))
		line("sin-1e22", bits(sin(1e22)))
	},
	Probe("numeric.parse", Family.NUMERIC) {
		val ints = listOf("42", "+5", "-0", " 1", "1_000", "0x10", "1e3", "")
		for (s in ints) line("int '$s'", s.toIntOrNull())
		val doubles =
			listOf("1e3", "1.", ".5", "NaN", "Infinity", "-Infinity", "0x1p3", "1d", " 2 ", "1,5")
		for (s in doubles) line("double '$s'", s.toDoubleOrNull())
	},
	Probe("numeric.rounding", Family.NUMERIC) {
		for (d in listOf(0.5, 1.5, 2.5, -0.5, -1.5, 2.4999999999999996)) line("round $d", round(d))
		for (d in listOf(0.5, 1.5, 2.5, -2.5)) line("roundToInt $d", d.roundToInt())
	},
)
