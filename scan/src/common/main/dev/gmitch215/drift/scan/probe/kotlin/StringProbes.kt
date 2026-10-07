package dev.gmitch215.drift.scan.probe.kotlin

private data class Point(val x: Int, val y: String)

internal val stringProbes = listOf(
	KotlinProbe(
		id = "kotlin.string.hashcode",
		question = "Which hashCode values are stable across targets?",
		source = """
			data class Point(val x: Int, val y: String)
			line("hello", "hello".hashCode())
			line("empty", "".hashCode())
			line("unicode", "é中".hashCode())
			line("long", "abcdefghijklmnopqrstuvwxyz".repeat(8).hashCode())
			line("list", listOf(1, 2, 3).hashCode())
			line("set", setOf(1, 2, 3).hashCode())
			line("map", mapOf("a" to 1, "b" to 2).hashCode())
			line("array", arrayOf(1, 2, 3).contentHashCode())
			line("int-array", intArrayOf(1, 2, 3).contentHashCode())
			line("pair", Pair(1, "x").hashCode())
			line("triple", Triple(1, 2, 3).hashCode())
			line("data-class", Point(1, "x").hashCode())
			line("double-1.5", 1.5.hashCode())
			line("double-neg-0", (-0.0).hashCode())
			line("double-nan", Double.NaN.hashCode())
			line("float-1.5", 1.5f.hashCode())
			line("long-max", Long.MAX_VALUE.hashCode())
			line("boolean", true.hashCode())
			line("char", 'a'.hashCode())
			line("null-list", listOf(null).hashCode())
			line("ulong", ULong.MAX_VALUE.hashCode())
		""",
	) {
		line("hello", "hello".hashCode())
		line("empty", "".hashCode())
		line("unicode", "é中".hashCode())
		line("long", "abcdefghijklmnopqrstuvwxyz".repeat(8).hashCode())
		line("list", listOf(1, 2, 3).hashCode())
		line("set", setOf(1, 2, 3).hashCode())
		line("map", mapOf("a" to 1, "b" to 2).hashCode())
		line("array", arrayOf(1, 2, 3).contentHashCode())
		line("int-array", intArrayOf(1, 2, 3).contentHashCode())
		line("pair", Pair(1, "x").hashCode())
		line("triple", Triple(1, 2, 3).hashCode())
		line("data-class", Point(1, "x").hashCode())
		line("double-1.5", 1.5.hashCode())
		line("double-neg-0", (-0.0).hashCode())
		line("double-nan", Double.NaN.hashCode())
		line("float-1.5", 1.5f.hashCode())
		line("long-max", Long.MAX_VALUE.hashCode())
		line("boolean", true.hashCode())
		line("char", 'a'.hashCode())
		line("null-list", listOf(null).hashCode())
		line("ulong", ULong.MAX_VALUE.hashCode())
	},
	KotlinProbe(
		id = "kotlin.string.split-replace-lines",
		question = "What do split, replace and lines return at the edges?",
		source = """
			line("split-trailing", "a,,b,".split(","))
			line("split-limit", "a,b,c".split(",", limit = 2))
			line("split-empty-delim", "abc".split(""))
			line("split-empty-input", "".split(","))
			line("split-regex-empty", "abc".split(Regex("")))
			line("split-regex-limit", Regex("\\s+").split(" a  b c ", 3))
			line("split-ignore-case", "aXbxc".split("x", ignoreCase = true))
			line("replace-empty", "abc".replace("", "-"))
			line("replace-ignore-case", "Aa".replace("a", "b", ignoreCase = true))
			line("replace-first", "aaa".replaceFirst("a", "b"))
			line("lines", "a\r\nb\rc\nd e\u0085f".lines())
			line("lines-trailing", "a\n".lines())
			line("trimIndent", "  a\n   b".trimIndent())
			line("substringBefore-missing", "abc".substringBefore("x", "none"))
			line("removeSurrounding", "[a]".removeSurrounding("[", "]"))
			outcome("take-neg") { "abc".take(-1) }
			line("drop-over", "abc".drop(5))
			line("indexOf-empty", "abc".indexOf("", 10))
			line("lastIndexOf-empty", "abc".lastIndexOf(""))
			line("contains-empty", "".contains(""))
			line("startsWith-offset", "abc".startsWith("b", 1))
			line("commonPrefix", "abcd".commonPrefixWith("abxy"))
			line("compare-length", "ab".compareTo("abc"))
		""",
	) {
		line("split-trailing", "a,,b,".split(","))
		line("split-limit", "a,b,c".split(",", limit = 2))
		line("split-empty-delim", "abc".split(""))
		line("split-empty-input", "".split(","))
		line("split-regex-empty", "abc".split(Regex("")))
		line("split-regex-limit", Regex("\\s+").split(" a  b c ", 3))
		line("split-ignore-case", "aXbxc".split("x", ignoreCase = true))
		line("replace-empty", "abc".replace("", "-"))
		line("replace-ignore-case", "Aa".replace("a", "b", ignoreCase = true))
		line("replace-first", "aaa".replaceFirst("a", "b"))
		line("lines", "a\r\nb\rc\nd e\u0085f".lines())
		line("lines-trailing", "a\n".lines())
		line("trimIndent", "  a\n   b".trimIndent())
		line("substringBefore-missing", "abc".substringBefore("x", "none"))
		line("removeSurrounding", "[a]".removeSurrounding("[", "]"))
		outcome("take-neg") { "abc".take(-1) }
		line("drop-over", "abc".drop(5))
		line("indexOf-empty", "abc".indexOf("", 10))
		line("lastIndexOf-empty", "abc".lastIndexOf(""))
		line("contains-empty", "".contains(""))
		line("startsWith-offset", "abc".startsWith("b", 1))
		line("commonPrefix", "abcd".commonPrefixWith("abxy"))
		line("compare-length", "ab".compareTo("abc"))
	},
	KotlinProbe(
		id = "kotlin.string.compare-order",
		question = "In which order do strings compare, sort and match ignoring case?",
		source = """
			line("a-vs-B", "a".compareTo("B"))
			line("a-vs-B-ignore-case", "a".compareTo("B", ignoreCase = true))
			line("sorted", listOf("b", "B", "a", "ä", "Z", "10", "9", "_", " ").sorted())
			val mixed = listOf("b", "B", "a", "A")
			line("sorted-ignore-case", mixed.sortedWith(String.CASE_INSENSITIVE_ORDER))
			line("composed-vs-decomposed", "é" == "é")
			line("composed-vs-decomposed-order", "é".compareTo("é"))
			line("sharp-s-vs-SS", "ß".equals("SS", ignoreCase = true))
			line("dotted-I-vs-i", "İ".equals("i", ignoreCase = true))
			line("dotless-i-vs-I", "ı".equals("I", ignoreCase = true))
			line("kelvin-vs-k", "K".equals("k", ignoreCase = true))
			line("dz-forms", "Ǆ".equals("ǆ", ignoreCase = true))
			line("final-sigma", "ς".equals("Σ", ignoreCase = true))
			line("char-ignore-case", 'ß'.equals('ẞ', ignoreCase = true))
			line("compare-ignore-case-sigma", "ς".compareTo("σ", ignoreCase = true))
			line("compare-ignore-case-sharp-s", "ß".compareTo("ss", ignoreCase = true))
			line("compare-ignore-case-upper-lower", "[".compareTo("a", ignoreCase = true))
			line("maxOf-strings", maxOf("a", "B"))
			line("nbsp-vs-space", " " == " ")
			line("regionMatches", "xAbC".regionMatches(1, "aBc", 0, 3, ignoreCase = true))
		""",
	) {
		line("a-vs-B", "a".compareTo("B"))
		line("a-vs-B-ignore-case", "a".compareTo("B", ignoreCase = true))
		line("sorted", listOf("b", "B", "a", "ä", "Z", "10", "9", "_", " ").sorted())
		line(
			"sorted-ignore-case",
			listOf("b", "B", "a", "A").sortedWith(String.CASE_INSENSITIVE_ORDER),
		)
		line("composed-vs-decomposed", "é" == "é")
		line("composed-vs-decomposed-order", "é".compareTo("é"))
		line("sharp-s-vs-SS", "ß".equals("SS", ignoreCase = true))
		line("dotted-I-vs-i", "İ".equals("i", ignoreCase = true))
		line("dotless-i-vs-I", "ı".equals("I", ignoreCase = true))
		line("kelvin-vs-k", "K".equals("k", ignoreCase = true))
		line("dz-forms", "Ǆ".equals("ǆ", ignoreCase = true))
		line("final-sigma", "ς".equals("Σ", ignoreCase = true))
		line("char-ignore-case", 'ß'.equals('ẞ', ignoreCase = true))
		line("compare-ignore-case-sigma", "ς".compareTo("σ", ignoreCase = true))
		line("compare-ignore-case-sharp-s", "ß".compareTo("ss", ignoreCase = true))
		line("compare-ignore-case-upper-lower", "[".compareTo("a", ignoreCase = true))
		line("maxOf-strings", maxOf("a", "B"))
		line("nbsp-vs-space", " " == " ")
		line("regionMatches", "xAbC".regionMatches(1, "aBc", 0, 3, ignoreCase = true))
	},
	KotlinProbe(
		id = "kotlin.string.padding",
		question = "How do padStart, padEnd and repeat treat short, negative and wide input?",
		source = """
			line("pad-zero", "5".padStart(3, '0'))
			line("pad-shorter", "abc".padStart(2))
			line("pad-end", "x".padEnd(3, '.'))
			failure("pad-negative") { "a".padStart(-1) }
			line("pad-hex", 255.toString(16).padStart(4, '0'))
			line("pad-double", 1.5.toString().padStart(8))
			line("pad-wide", "中".padStart(3, '*'))
			line("repeat-zero", "ab".repeat(0))
			failure("repeat-negative") { "a".repeat(-1) }
			line("repeat-char", '-'.toString().repeat(5))
			line("chunked", "abcdefg".chunked(3))
			line("windowed", "abcd".windowed(3))
			line("center", "ab".padStart(5, '*').padEnd(7, '*'))
		""",
	) {
		line("pad-zero", "5".padStart(3, '0'))
		line("pad-shorter", "abc".padStart(2))
		line("pad-end", "x".padEnd(3, '.'))
		failure("pad-negative") { "a".padStart(-1) }
		line("pad-hex", 255.toString(16).padStart(4, '0'))
		line("pad-double", 1.5.toString().padStart(8))
		line("pad-wide", "中".padStart(3, '*'))
		line("repeat-zero", "ab".repeat(0))
		failure("repeat-negative") { "a".repeat(-1) }
		line("repeat-char", '-'.toString().repeat(5))
		line("chunked", "abcdefg".chunked(3))
		line("windowed", "abcd".windowed(3))
		line("center", "ab".padStart(5, '*').padEnd(7, '*'))
	},
)
