package dev.gmitch215.drift.scan.probe.kotlin

import dev.gmitch215.drift.scan.probe.bits
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.random.nextULong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private class ProbeException(message: String) : Exception(message)

private class Holder {
	lateinit var name: String
}

internal val miscProbes = listOf(
	KotlinProbe(
		id = "kotlin.random.seeded",
		question = "Does Random(seed) give the same numbers on every target?",
		source = """
			val r = Random(42)
			line("ints", List(4) { r.nextInt() })
			line("bounded", List(4) { r.nextInt(10, 20) })
			line("until", List(3) { r.nextInt(7) })
			line("long", r.nextLong())
			line("long-range", r.nextLong(1000000000000L))
			line("double", bits(r.nextDouble()))
			line("double-range", bits(r.nextDouble(5.0, 10.0)))
			line("float", bits(r.nextFloat()))
			line("booleans", List(8) { r.nextBoolean() })
			line("bytes", r.nextBytes(6).toList())
			line("bits", r.nextBits(5))
			line("seed-long-min", Random(Long.MIN_VALUE).nextInt())
			line("seed-zero", Random(0).nextInt())
			line("seed-neg", Random(-1).nextInt())
			line("shuffle", (1..8).toList().shuffled(Random(7)))
			line("random-element", listOf(1, 2, 3, 4, 5).random(Random(3)))
			line("array-shuffle", intArrayOf(1, 2, 3, 4, 5).also { it.shuffle(Random(9)) }.toList())
			line("uint", Random(5).nextUInt())
			line("ulong", Random(5).nextULong())
			line("uint-until", Random(5).nextUInt(10u))
		""",
	) {
		val r = Random(42)
		line("ints", List(4) { r.nextInt() })
		line("bounded", List(4) { r.nextInt(10, 20) })
		line("until", List(3) { r.nextInt(7) })
		line("long", r.nextLong())
		line("long-range", r.nextLong(1000000000000L))
		line("double", bits(r.nextDouble()))
		line("double-range", bits(r.nextDouble(5.0, 10.0)))
		line("float", bits(r.nextFloat()))
		line("booleans", List(8) { r.nextBoolean() })
		line("bytes", r.nextBytes(6).toList())
		line("bits", r.nextBits(5))
		line("seed-long-min", Random(Long.MIN_VALUE).nextInt())
		line("seed-zero", Random(0).nextInt())
		line("seed-neg", Random(-1).nextInt())
		line("shuffle", (1..8).toList().shuffled(Random(7)))
		line("random-element", listOf(1, 2, 3, 4, 5).random(Random(3)))
		line("array-shuffle", intArrayOf(1, 2, 3, 4, 5).also { it.shuffle(Random(9)) }.toList())
		line("uint", Random(5).nextUInt())
		line("ulong", Random(5).nextULong())
		line("uint-until", Random(5).nextUInt(10u))
	},
	KotlinProbe(
		id = "kotlin.time.duration-format",
		question = "How does Duration print, round and parse?",
		source = """
			line("90m", 90.minutes)
			line("1.5s", 1.5.seconds)
			line("1234567ms", 1234567.milliseconds)
			line("infinite", Duration.INFINITE)
			line("neg-infinite", -Duration.INFINITE)
			line("zero", Duration.ZERO)
			line("neg-5s", (-5).seconds)
			line("100ns", 100.nanoseconds)
			line("3d4h", 3.days + 4.hours)
			line("0.5ms", 0.5.milliseconds)
			line("1.5us", 1.5.microseconds)
			line("1.2345678s", 1.2345678.seconds)
			line("2.5ms", 2.5.milliseconds)
			line("1.0005s", 1.0005.seconds)
			line("0.0005s", 0.0005.seconds)
			line("123456789.123s", 123456789.123.seconds)
			line("1e6-days", 1e6.days)
			line("decimals-3", 1.23456.seconds.toString(DurationUnit.SECONDS, 3))
			line("decimals-0", 1.5.seconds.toString(DurationUnit.SECONDS, 0))
			line("decimals-10", 1.5.seconds.toString(DurationUnit.SECONDS, 10))
			line("decimals-minutes", 90.seconds.toString(DurationUnit.MINUTES, 2))
			line("iso", 90.minutes.toIsoString())
			line("iso-fraction", 1.5.seconds.toIsoString())
			line("iso-negative", (-1.5).seconds.toIsoString())
			line("parse-iso", Duration.parseIsoString("PT1H30M"))
			line("parse", Duration.parse("1h 30m 15.5s"))
			outcome("parse-bad") { Duration.parse("abc") }
			line("whole-seconds-negative", (-1500).milliseconds.inWholeSeconds)
			line("to-long-ms", 1.5.seconds.toLong(DurationUnit.MILLISECONDS))
			line("to-double-min", 90.seconds.toDouble(DurationUnit.MINUTES))
			line("components", 3725.seconds.toComponents { h, m, s, ns ->
				"${'$'}h:${'$'}m:${'$'}s.${'$'}ns"
			})
			line("div", 10.seconds / 4)
			line("times", 1.5.seconds * 3)
			line("long-max-ms", Long.MAX_VALUE.milliseconds)
			line("long-max-ns", Long.MAX_VALUE.nanoseconds)
		""",
	) {
		line("90m", 90.minutes)
		line("1.5s", 1.5.seconds)
		line("1234567ms", 1234567.milliseconds)
		line("infinite", Duration.INFINITE)
		line("neg-infinite", -Duration.INFINITE)
		line("zero", Duration.ZERO)
		line("neg-5s", (-5).seconds)
		line("100ns", 100.nanoseconds)
		line("3d4h", 3.days + 4.hours)
		line("0.5ms", 0.5.milliseconds)
		line("1.5us", 1.5.microseconds)
		line("1.2345678s", 1.2345678.seconds)
		line("2.5ms", 2.5.milliseconds)
		line("1.0005s", 1.0005.seconds)
		line("0.0005s", 0.0005.seconds)
		line("123456789.123s", 123456789.123.seconds)
		line("1e6-days", 1e6.days)
		line("decimals-3", 1.23456.seconds.toString(DurationUnit.SECONDS, 3))
		line("decimals-0", 1.5.seconds.toString(DurationUnit.SECONDS, 0))
		line("decimals-10", 1.5.seconds.toString(DurationUnit.SECONDS, 10))
		line("decimals-minutes", 90.seconds.toString(DurationUnit.MINUTES, 2))
		line("iso", 90.minutes.toIsoString())
		line("iso-fraction", 1.5.seconds.toIsoString())
		line("iso-negative", (-1.5).seconds.toIsoString())
		line("parse-iso", Duration.parseIsoString("PT1H30M"))
		line("parse", Duration.parse("1h 30m 15.5s"))
		outcome("parse-bad") { Duration.parse("abc") }
		line("whole-seconds-negative", (-1500).milliseconds.inWholeSeconds)
		line("to-long-ms", 1.5.seconds.toLong(DurationUnit.MILLISECONDS))
		line("to-double-min", 90.seconds.toDouble(DurationUnit.MINUTES))
		line("components", 3725.seconds.toComponents { h, m, s, ns -> "$h:$m:$s.$ns" })
		line("div", 10.seconds / 4)
		line("times", 1.5.seconds * 3)
		line("long-max-ms", Long.MAX_VALUE.milliseconds)
		line("long-max-ns", Long.MAX_VALUE.nanoseconds)
	},
	KotlinProbe(
		id = "kotlin.exceptions.messages",
		question = "Which exception class and message does each common failure produce?",
		source = """
			val zero = "0".toInt()
			failure("toInt") { "x".toInt() }
			failure("toInt-empty") { "".toInt() }
			failure("toLong") { "9999999999999999999".toLong() }
			failure("toDouble") { "x".toDouble() }
			failure("toUInt") { "-1".toUInt() }
			failure("toUByte") { "256".toUByte() }
			failure("int-div-zero") { 1 / zero }
			failure("long-div-zero") { 1L / zero }
			failure("int-mod-zero") { 1 % zero }
			failure("floorDiv-zero") { 1.floorDiv(zero) }
			failure("list-index") { listOf(1)[3] }
			failure("array-index") { arrayOf(1)[5] }
			failure("string-index") { "abc"[10] }
			failure("substring") { "abc".substring(5) }
			failure("substring-range") { "abc".substring(2, 1) }
			failure("subList") { listOf(1, 2).subList(1, 5) }
			failure("removeAt") { mutableListOf(1).removeAt(1) }
			failure("negative-array") { IntArray(zero - 1) }
			failure("negative-list") { List(zero - 1) { 0 } }
			failure("first-empty") { emptyList<Int>().first() }
			failure("last-empty") { emptyList<Int>().last() }
			failure("single-many") { listOf(1, 2).single() }
			failure("removeFirst-empty") { mutableListOf<Int>().removeFirst() }
			failure("require") { require(false) }
			failure("require-message") { require(false) { "custom" } }
			failure("check") { check(false) }
			failure("error") { error("boom") }
			failure("checkNotNull") { checkNotNull(null as Int?) }
			failure("not-null-assert") { (null as String?)!! }
			failure("todo") { TODO() }
			failure("todo-message") { TODO("later") }
			failure("cast") { (1 as Any) as String }
			failure("null-cast") { (null as Any?) as String }
			failure("getValue") { emptyMap<Int, Int>().getValue(1) }
			failure("repeat-negative") { "a".repeat(zero - 1) }
			failure("chunked-zero") { listOf(1).chunked(zero) }
			failure("step-zero") { 1..3 step zero }
			failure("random-empty") { Random(1).nextInt(5, 5) }
			failure("radix") { 255.toString(37) }
			failure("lateinit") { Holder().name }
			failure("iterator-end") { listOf(1).iterator().apply { next() }.next() }
		""",
	) {
		val zero = "0".toInt()
		failure("toInt") { "x".toInt() }
		failure("toInt-empty") { "".toInt() }
		failure("toLong") { "9999999999999999999".toLong() }
		failure("toDouble") { "x".toDouble() }
		failure("toUInt") { "-1".toUInt() }
		failure("toUByte") { "256".toUByte() }
		failure("int-div-zero") { 1 / zero }
		failure("long-div-zero") { 1L / zero }
		failure("int-mod-zero") { 1 % zero }
		failure("floorDiv-zero") { 1.floorDiv(zero) }
		failure("list-index") { listOf(1)[3] }
		failure("array-index") { arrayOf(1)[5] }
		failure("string-index") { "abc"[10] }
		failure("substring") { "abc".substring(5) }
		failure("substring-range") { "abc".substring(2, 1) }
		failure("subList") { listOf(1, 2).subList(1, 5) }
		failure("removeAt") { mutableListOf(1).removeAt(1) }
		failure("negative-array") { IntArray(zero - 1) }
		failure("negative-list") { List(zero - 1) { 0 } }
		failure("first-empty") { emptyList<Int>().first() }
		failure("last-empty") { emptyList<Int>().last() }
		failure("single-many") { listOf(1, 2).single() }
		failure("removeFirst-empty") { mutableListOf<Int>().removeFirst() }
		failure("require") { require(false) }
		failure("require-message") { require(false) { "custom" } }
		failure("check") { check(false) }
		failure("error") { error("boom") }
		failure("checkNotNull") { checkNotNull(null as Int?) }
		failure("not-null-assert") { (null as String?)!! }
		failure("todo") { TODO() }
		failure("todo-message") { TODO("later") }
		failure("cast") { (1 as Any) as String }
		failure("null-cast") { (null as Any?) as String }
		failure("getValue") { emptyMap<Int, Int>().getValue(1) }
		failure("repeat-negative") { "a".repeat(zero - 1) }
		failure("chunked-zero") { listOf(1).chunked(zero) }
		failure("step-zero") { 1..3 step zero }
		failure("random-empty") { Random(1).nextInt(5, 5) }
		failure("radix") { 255.toString(37) }
		failure("lateinit") { Holder().name }
		failure("iterator-end") { listOf(1).iterator().apply { next() }.next() }
	},
	KotlinProbe(
		id = "kotlin.exceptions.tostring-cause",
		question = "What do toString, cause and the stack text of an exception look like?",
		source = """
			class ProbeException(message: String) : Exception(message)
			val e = ProbeException("m")
			line("toString", e.toString())
			line("simple-name", e::class.simpleName)
			line("message", e.message)
			line("bare-toString", Exception().toString())
			line("bare-message", Exception().message)
			line("cause-null", Exception("a").cause)
			line("cause-message", Exception("a", Exception("b")).cause?.message)
			line("cause-only-message", Exception(Exception("b")).message)
			line("illegal-state", IllegalStateException("x").toString())
			line("npe", NullPointerException().toString())
			line("assertion", AssertionError("x").toString())
			line("not-implemented", NotImplementedError().toString())
			line("stack-first-line", e.stackTraceToString().lines().first())
			line("stack-multiline", e.stackTraceToString().lines().size > 1)
			line("caused-by-lines", Exception("a", Exception("b")).stackTraceToString().lines()
				.count { it.startsWith("Caused by: ") })
			line("suppressed", Exception("a").also { it.addSuppressed(Exception("s")) }
				.suppressedExceptions.size)
			line("equality", Exception("a") == Exception("a"))
			line("same-instance", e == e)
		""",
	) {
		val e = ProbeException("m")
		line("toString", e.toString())
		line("simple-name", e::class.simpleName)
		line("message", e.message)
		line("bare-toString", Exception().toString())
		line("bare-message", Exception().message)
		line("cause-null", Exception("a").cause)
		line("cause-message", Exception("a", Exception("b")).cause?.message)
		line("cause-only-message", Exception(Exception("b")).message)
		line("illegal-state", IllegalStateException("x").toString())
		line("npe", NullPointerException().toString())
		line("assertion", AssertionError("x").toString())
		line("not-implemented", NotImplementedError().toString())
		line("stack-first-line", e.stackTraceToString().lines().first())
		line("stack-multiline", e.stackTraceToString().lines().size > 1)
		line(
			"caused-by-lines",
			Exception("a", Exception("b")).stackTraceToString().lines()
				.count { it.startsWith("Caused by: ") },
		)
		line(
			"suppressed",
			Exception("a").also { it.addSuppressed(Exception("s")) }.suppressedExceptions.size,
		)
		line("equality", Exception("a") == Exception("a"))
		line("same-instance", e == e)
	},
)
