package dev.gmitch215.drift.scan.probe

private fun c(code: Int): Char = Char(code)

private fun s(vararg codes: Int): String = codes.map { Char(it) }.toCharArray().concatToString()

internal val textProbes = listOf(
	Probe("text.case-mapping", Family.TEXT) {
		line("upper-sharp-s", s(0xDF).uppercase().map { it.code })
		line("lower-dotted-I", s(0x130).lowercase().map { it.code })
		line("upper-ligature-fi", s(0xFB01).uppercase().map { it.code })
		line("lower-final-sigma", s(0x391, 0x3A3).lowercase().map { it.code })
		line("title-dz", s(0x1C6).replaceFirstChar { it.titlecase() }.map { it.code })
		line("upper-turkish-i", "i".uppercase())
	},
	Probe("text.char-classes", Family.TEXT) {
		val codes = listOf(
			0x41, 0x20, 0xA0, 0x2003, 0x200B, 0xFEFF, 0x660, 0xB2, 0x2160, 0x1C5, 0x3A9,
		)
		for (code in codes) {
			val ch = c(code)
			val flags = "L=${ch.isLetter()} D=${ch.isDigit()} " +
				"W=${ch.isWhitespace()} U=${ch.isUpperCase()}"
			line("U+${code.toString(16)}", flags)
		}
	},
	Probe("text.supplementary", Family.TEXT) {
		val emoji = s(0xD83D, 0xDE00)
		line("length", emoji.length)
		line("units", emoji.map { it.code })
		line("reversed", emoji.reversed().map { it.code })
		line("lone-surrogate-upper", s(0xD83D).uppercase().map { it.code })
	},
	Probe("text.compare", Family.TEXT) {
		line("a-vs-B", "a".compareTo("B"))
		line("a-vs-B-ignore-case", "a".compareTo("B", ignoreCase = true))
		line("precomposed-vs-combining", s(0xE9) == s(0x65, 0x301))
		line("sorted", listOf("b", "B", "a", s(0xE4), "Z", "10", "9").sorted())
		val ci = listOf("b", "B", "a", "A").sortedWith(String.CASE_INSENSITIVE_ORDER)
		line("sorted-ignore-case", ci)
		line("equals-ignore-case-sharp-s", s(0xDF).equals("SS", ignoreCase = true))
	},
	Probe("text.regex", Family.TEXT) {
		line("digit-arabic", Regex("\\d").matches(s(0x663)))
		line("word-accent", Regex("\\w+").find("h${s(0xE9)}llo")?.value)
		val sharp = Regex("stra${s(0xDF)}e", RegexOption.IGNORE_CASE)
		line("ignore-case-sharp-s", sharp.matches("STRASSE"))
		line("replace-group", Regex("(a)(b)?").replace("ac", "[\$1|\$2]"))
		line("lazy", Regex("a+?").find("aaa")?.value)
		line("dot-newline", Regex(".").matches("\n"))
		line("anchors", Regex("^b$", RegexOption.MULTILINE).findAll("a\nb\nc").count())
		line("split-empty", "a,,b,".split(","))
		line("split-regex-limit", Regex("\\s+").split(" a  b c ", 3))
	},
	Probe("text.hashcode", Family.TEXT) {
		line("string", "hello".hashCode())
		line("empty", "".hashCode())
		line("list", listOf(1, 2, 3).hashCode())
		line("map", mapOf("a" to 1).hashCode())
		line("double", 1.5.hashCode())
		line("neg-zero", (-0.0).hashCode())
		line("long", Long.MAX_VALUE.hashCode())
		line("bool", true.hashCode())
		line("pair", Pair(1, "x").hashCode())
	},
	Probe("text.exception-messages", Family.TEXT) {
		attempt("toInt") { "x".toInt() }
		attempt("div-zero") { 1 / "0".toInt() }
		attempt("index") { listOf(1)[3] }
		attempt("substring") { "abc".substring(5) }
		attempt("first-empty") { emptyList<Int>().first() }
		attempt("require") { require(false) { "custom" } }
		attempt("check-null") { checkNotNull(null as Int?) }
		attempt("cast") { (1 as Any) as String }
		attempt("map-getValue") { mapOf(1 to 2).getValue(3) }
		attempt("repeat-negative") { "a".repeat(-1) }
	},
	Probe("text.default-tostring", Family.TEXT) {
		line("list-doubles", listOf(1.0, 2.5f, 1e10))
		line("array", arrayOf(1, 2).contentToString())
		line("pair", Pair(1, null))
		line("char-plus", 'a' + 1)
		line("long-shift", 1L shl 40)
		line("unit", Unit)
		line("range", 1..3 step 2)
		line("nested-map", mapOf("k" to listOf(1, 2)))
	},
)
