package dev.gmitch215.drift.scan.probe.kotlin

internal val integerProbes = listOf(
	KotlinProbe(
		id = "kotlin.int.overflow",
		question = "What do Int and Long arithmetic and shifts do at the edges?",
		source = """
			val one = "1".toInt()
			val m1 = "-1".toInt()
			val min = Int.MIN_VALUE
			line("max+1", Int.MAX_VALUE + one)
			line("min-1", min - one)
			line("negate-min", -min)
			line("min/-1", min / m1)
			line("min%-1", min % m1)
			line("long-min/-1", Long.MIN_VALUE / m1)
			line("long-min%-1", Long.MIN_VALUE % m1)
			line("max*max", Int.MAX_VALUE * Int.MAX_VALUE)
			line("long-max+1", Long.MAX_VALUE + one)
			line("shl-32", one shl 32)
			line("shl-33", one shl 33)
			line("shl-neg1", one shl m1)
			line("long-shl-64", one.toLong() shl 64)
			line("long-shl-65", one.toLong() shl 65)
			line("ushr-28", m1 ushr 28)
			line("shr-neg", -8 shr one)
			line("long-ushr", -8L ushr one)
			line("byte-200", 200.toByte())
			line("short-70000", 70000.toShort())
			line("long-max-to-int", Long.MAX_VALUE.toInt())
			line("long-min-to-int", Long.MIN_VALUE.toInt())
		""",
	) {
		val one = "1".toInt()
		val m1 = "-1".toInt()
		val min = Int.MIN_VALUE
		line("max+1", Int.MAX_VALUE + one)
		line("min-1", min - one)
		line("negate-min", -min)
		line("min/-1", min / m1)
		line("min%-1", min % m1)
		line("long-min/-1", Long.MIN_VALUE / m1)
		line("long-min%-1", Long.MIN_VALUE % m1)
		line("max*max", Int.MAX_VALUE * Int.MAX_VALUE)
		line("long-max+1", Long.MAX_VALUE + one)
		line("shl-32", one shl 32)
		line("shl-33", one shl 33)
		line("shl-neg1", one shl m1)
		line("long-shl-64", one.toLong() shl 64)
		line("long-shl-65", one.toLong() shl 65)
		line("ushr-28", m1 ushr 28)
		line("shr-neg", -8 shr one)
		line("long-ushr", -8L ushr one)
		line("byte-200", 200.toByte())
		line("short-70000", 70000.toShort())
		line("long-max-to-int", Long.MAX_VALUE.toInt())
		line("long-min-to-int", Long.MIN_VALUE.toInt())
	},
	KotlinProbe(
		id = "kotlin.int.division-modulo",
		question = "What do / % rem mod and floorDiv give for negative operands?",
		source = """
			val a = "-7".toInt()
			val b = "7".toInt()
			line("-7/2", a / 2)
			line("-7%2", a % 2)
			line("-7.mod(2)", a.mod(2))
			line("-7.floorDiv(2)", a.floorDiv(2))
			line("7/-2", b / -2)
			line("7%-2", b % -2)
			line("7.mod(-2)", b.mod(-2))
			line("7.floorDiv(-2)", b.floorDiv(-2))
			line("long-7%3", -7L % 3L)
			line("-7.5%2", -7.5 % 2.0)
			line("7.5%-2", 7.5 % -2.0)
			line("7.5.mod(-2)", 7.5.mod(-2.0))
			line("-7.5.mod(2)", (-7.5).mod(2.0))
			line("5%0.0", 5.0 % "0".toDouble())
			line("-0.0%5", -0.0 % 5.0)
			line("5%inf", 5.0 % Double.POSITIVE_INFINITY)
			line("inf%2", Double.POSITIVE_INFINITY % 2.0)
			line("1/0.0", 1.0 / "0".toDouble())
			line("-1/0.0", -1.0 / "0".toDouble())
			line("float-7.5%2", -7.5f % 2f)
			line("7/2", b / 2)
			line("7/2.0", b / 2.0)
		""",
	) {
		val a = "-7".toInt()
		val b = "7".toInt()
		line("-7/2", a / 2)
		line("-7%2", a % 2)
		line("-7.mod(2)", a.mod(2))
		line("-7.floorDiv(2)", a.floorDiv(2))
		line("7/-2", b / -2)
		line("7%-2", b % -2)
		line("7.mod(-2)", b.mod(-2))
		line("7.floorDiv(-2)", b.floorDiv(-2))
		line("long-7%3", -7L % 3L)
		line("-7.5%2", -7.5 % 2.0)
		line("7.5%-2", 7.5 % -2.0)
		line("7.5.mod(-2)", 7.5.mod(-2.0))
		line("-7.5.mod(2)", (-7.5).mod(2.0))
		line("5%0.0", 5.0 % "0".toDouble())
		line("-0.0%5", -0.0 % 5.0)
		line("5%inf", 5.0 % Double.POSITIVE_INFINITY)
		line("inf%2", Double.POSITIVE_INFINITY % 2.0)
		line("1/0.0", 1.0 / "0".toDouble())
		line("-1/0.0", -1.0 / "0".toDouble())
		line("float-7.5%2", -7.5f % 2f)
		line("7/2", b / 2)
		line("7/2.0", b / 2.0)
	},
	KotlinProbe(
		id = "kotlin.int.radix",
		question = "How do toString(radix) and parsing with a radix behave?",
		source = """
			line("255-bin", 255.toString(2))
			line("neg255-hex", (-255).toString(16))
			line("int-min-hex", Int.MIN_VALUE.toString(16))
			line("long-min-bin", Long.MIN_VALUE.toString(2))
			line("long-min-36", Long.MIN_VALUE.toString(36))
			line("ulong-max-36", ULong.MAX_VALUE.toString(36))
			line("uint-bin", 255u.toString(2))
			outcome("radix-1") { 255.toString(1) }
			outcome("radix-37") { 255.toString(37) }
			for (s in listOf(
				"ff", "-80000000", "80000000", "+1f", "FF", "0x10", "zz", "-0", "",
			)) line("'" + s + "'", s.toIntOrNull(16))
			line("zz-36", "zz".toInt(36))
			line("arabic-digit", "٣".toIntOrNull())
			line("fullwidth-digit", "３".toIntOrNull())
			line("underscore", "1_000".toIntOrNull())
			line("plus", "+5".toIntOrNull())
			line("space", " 1".toIntOrNull())
			line("int-max+1", "2147483648".toIntOrNull())
			line("int-min", "-2147483648".toIntOrNull())
			line("long-min", "-9223372036854775808".toLongOrNull())
		""",
	) {
		line("255-bin", 255.toString(2))
		line("neg255-hex", (-255).toString(16))
		line("int-min-hex", Int.MIN_VALUE.toString(16))
		line("long-min-bin", Long.MIN_VALUE.toString(2))
		line("long-min-36", Long.MIN_VALUE.toString(36))
		line("ulong-max-36", ULong.MAX_VALUE.toString(36))
		line("uint-bin", 255u.toString(2))
		outcome("radix-1") { 255.toString(1) }
		outcome("radix-37") { 255.toString(37) }
		for (s in listOf("ff", "-80000000", "80000000", "+1f", "FF", "0x10", "zz", "-0", "")) {
			line("'" + s + "'", s.toIntOrNull(16))
		}
		line("zz-36", "zz".toInt(36))
		line("arabic-digit", "٣".toIntOrNull())
		line("fullwidth-digit", "３".toIntOrNull())
		line("underscore", "1_000".toIntOrNull())
		line("plus", "+5".toIntOrNull())
		line("space", " 1".toIntOrNull())
		line("int-max+1", "2147483648".toIntOrNull())
		line("int-min", "-2147483648".toIntOrNull())
		line("long-min", "-9223372036854775808".toLongOrNull())
	},
	KotlinProbe(
		id = "kotlin.long.bit-ops",
		question = "What do Long bit operations give?",
		source = """
			val x = "-1".toLong()
			line("1shl63", 1L shl 63)
			line("-1ushr60", x ushr 60)
			line("-1shr60", x shr 60)
			line("max.inv", Long.MAX_VALUE.inv())
			line("count-ones", 0x0F0F0F0F0F0F0F0FL.countOneBits())
			line("leading-zeros", 255L.countLeadingZeroBits())
			line("trailing-zeros", 256L.countTrailingZeroBits())
			line("highest-one", 1000L.takeHighestOneBit())
			line("lowest-one", 1000L.takeLowestOneBit())
			line("rotl", 0x8000000000000001UL.rotateLeft(1))
			line("rotr", 1L.rotateRight(1))
			line("to-ulong", x.toULong())
			line("min-hex", Long.MIN_VALUE.toString(16))
			line("ff-byte", 0xFFL.toByte())
			line("xor", 0x0FL xor 0xFFL)
			line("and-neg", -256L and 0xFFFFL)
			line("int-rotl", 0x80000001.toInt().rotateLeft(33))
			line("int-ones", (-1).countOneBits())
		""",
	) {
		val x = "-1".toLong()
		line("1shl63", 1L shl 63)
		line("-1ushr60", x ushr 60)
		line("-1shr60", x shr 60)
		line("max.inv", Long.MAX_VALUE.inv())
		line("count-ones", 0x0F0F0F0F0F0F0F0FL.countOneBits())
		line("leading-zeros", 255L.countLeadingZeroBits())
		line("trailing-zeros", 256L.countTrailingZeroBits())
		line("highest-one", 1000L.takeHighestOneBit())
		line("lowest-one", 1000L.takeLowestOneBit())
		line("rotl", 0x8000000000000001UL.rotateLeft(1))
		line("rotr", 1L.rotateRight(1))
		line("to-ulong", x.toULong())
		line("min-hex", Long.MIN_VALUE.toString(16))
		line("ff-byte", 0xFFL.toByte())
		line("xor", 0x0FL xor 0xFFL)
		line("and-neg", -256L and 0xFFFFL)
		line("int-rotl", 0x80000001.toInt().rotateLeft(33))
		line("int-ones", (-1).countOneBits())
	},
	KotlinProbe(
		id = "kotlin.numbers.type-checks",
		question = "Can a boxed number be mistaken for another numeric type?",
		source = """
			val d: Any = 1.0
			val i: Any = 1
			val l: Any = 1L
			val f: Any = 1.5f
			line("1.0-is-Int", d is Int)
			line("1.0-is-Double", d is Double)
			line("1-is-Double", i is Double)
			line("1-is-Long", i is Long)
			line("1.5f-is-Double", f is Double)
			line("1.0-class", d::class.simpleName)
			line("1-class", i::class.simpleName)
			line("1L-class", l::class.simpleName)
			line("1.5f-class", f::class.simpleName)
			line("1==1L", i == l)
			line("1==1.0", i == d)
			line("1==1", i == 1)
			line("1.0-as?-Int", d as? Int)
			line("1.0-toString", d.toString())
			line("1.0-hash", d.hashCode())
			line("1-hash", i.hashCode())
			line("1L-hash", l.hashCode())
		""",
	) {
		val d: Any = 1.0
		val i: Any = 1
		val l: Any = 1L
		val f: Any = 1.5f
		line("1.0-is-Int", d is Int)
		line("1.0-is-Double", d is Double)
		line("1-is-Double", i is Double)
		line("1-is-Long", i is Long)
		line("1.5f-is-Double", f is Double)
		line("1.0-class", d::class.simpleName)
		line("1-class", i::class.simpleName)
		line("1L-class", l::class.simpleName)
		line("1.5f-class", f::class.simpleName)
		line("1==1L", i == l)
		line("1==1.0", i == d)
		line("1==1", i == 1)
		line("1.0-as?-Int", d as? Int)
		line("1.0-toString", d.toString())
		line("1.0-hash", d.hashCode())
		line("1-hash", i.hashCode())
		line("1L-hash", l.hashCode())
	},
)
