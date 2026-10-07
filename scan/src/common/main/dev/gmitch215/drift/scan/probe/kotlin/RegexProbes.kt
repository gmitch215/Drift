package dev.gmitch215.drift.scan.probe.kotlin

import dev.gmitch215.drift.scan.probe.ProbeContext

private fun ProbeContext.find(label: String, pattern: String, input: String) {
	outcome(label) { Regex(pattern).containsMatchIn(input) }
}

private fun ProbeContext.option(
	label: String,
	pattern: String,
	option: RegexOption,
	input: String,
) {
	outcome(label) { Regex(pattern, option).containsMatchIn(input) }
}

internal val regexProbes = listOf(
	KotlinProbe(
		id = "kotlin.regex.syntax-support",
		question = "Which pattern constructs compile and match, and which throw?",
		source = """
			fun find(label: String, pattern: String, input: String) =
				outcome(label) { Regex(pattern).containsMatchIn(input) }
			find("lookbehind", "(?<=a)b", "ab")
			find("neg-lookbehind", "(?<!a)b", "ab")
			find("lookbehind-unbounded", "(?<=a+)b", "aab")
			find("lookahead", "a(?=b)", "ab")
			find("possessive-star", "a*+a", "aaa")
			find("possessive-plus", "^a++${'$'}", "aaa")
			find("atomic", "(?>a*)a", "aaa")
			find("named-backref", "(?<n>a)\\k<n>", "aa")
			find("numbered-backref", "(a)\\1", "aa")
			find("inline-ignore-case", "(?i)a", "A")
			find("inline-dotall", "(?s)a.b", "a\nb")
			find("comment-group", "(?#c)a", "a")
			find("unicode-letter", "\\p{L}", "é")
			find("unicode-upper", "\\p{Lu}", "É")
			find("is-alphabetic", "\\p{IsAlphabetic}", "é")
			find("java-lowercase", "\\p{javaLowerCase}", "a")
			find("block-greek", "\\p{InGreek}", "α")
			find("posix-alpha", "\\p{Alpha}", "a")
			find("posix-bracket", "[[:alpha:]]", "a")
			find("horizontal-space", "\\h", " ")
			find("linebreak", "\\R", "\r\n")
			find("grapheme", "^\\X${'$'}", "é")
			find("quote", "\\Q.\\E", ".")
			find("literal-close-brace", "a}", "a}")
			find("literal-open-brace", "a{", "a{")
			find("literal-close-bracket", "]", "]")
			find("class-intersection", "[a-z&&[^b]]", "b")
			find("unicode-escape", "\\u00e9", "é")
			find("hex-braces", "\\x{1F600}", "😀")
			find("control-escape", "\\cA", "\u0001")
			find("end-z", "a\\z", "a\n")
			find("end-Z", "a\\Z", "a\n")
			find("empty-class", "[]", "a")
			find("lone-quantifier-range", "x{,3}", "x{,3}")
			find("unclosed-group", "(", "a")
			find("dangling-quantifier", "*a", "a")
		""",
	) {
		find("lookbehind", "(?<=a)b", "ab")
		find("neg-lookbehind", "(?<!a)b", "ab")
		find("lookbehind-unbounded", "(?<=a+)b", "aab")
		find("lookahead", "a(?=b)", "ab")
		find("possessive-star", "a*+a", "aaa")
		find("possessive-plus", "^a++$", "aaa")
		find("atomic", "(?>a*)a", "aaa")
		find("named-backref", "(?<n>a)\\k<n>", "aa")
		find("numbered-backref", "(a)\\1", "aa")
		find("inline-ignore-case", "(?i)a", "A")
		find("inline-dotall", "(?s)a.b", "a\nb")
		find("comment-group", "(?#c)a", "a")
		find("unicode-letter", "\\p{L}", "é")
		find("unicode-upper", "\\p{Lu}", "É")
		find("is-alphabetic", "\\p{IsAlphabetic}", "é")
		find("java-lowercase", "\\p{javaLowerCase}", "a")
		find("block-greek", "\\p{InGreek}", "α")
		find("posix-alpha", "\\p{Alpha}", "a")
		find("posix-bracket", "[[:alpha:]]", "a")
		find("horizontal-space", "\\h", " ")
		find("linebreak", "\\R", "\r\n")
		find("grapheme", "^\\X$", "é")
		find("quote", "\\Q.\\E", ".")
		find("literal-close-brace", "a}", "a}")
		find("literal-open-brace", "a{", "a{")
		find("literal-close-bracket", "]", "]")
		find("class-intersection", "[a-z&&[^b]]", "b")
		find("unicode-escape", "\\u00e9", "é")
		find("hex-braces", "\\x{1F600}", "😀")
		find("control-escape", "\\cA", "\u0001")
		find("end-z", "a\\z", "a\n")
		find("end-Z", "a\\Z", "a\n")
		find("empty-class", "[]", "a")
		find("lone-quantifier-range", "x{,3}", "x{,3}")
		find("unclosed-group", "(", "a")
		find("dangling-quantifier", "*a", "a")
	},
	KotlinProbe(
		id = "kotlin.regex.unicode-matching",
		question = "What do \\d, \\w, \\s, dot and ignore-case match outside ASCII?",
		source = """
			fun find(label: String, pattern: String, input: String) =
				outcome(label) { Regex(pattern).containsMatchIn(input) }
			fun option(label: String, pattern: String, option: RegexOption, input: String) =
				outcome(label) { Regex(pattern, option).containsMatchIn(input) }
			find("digit-arabic", "^\\d${'$'}", "٣")
			find("digit-fullwidth", "^\\d${'$'}", "３")
			find("word-accent", "^\\w${'$'}", "é")
			find("word-greek", "^\\w${'$'}", "α")
			find("word-underscore", "^\\w${'$'}", "_")
			find("space-nbsp", "^\\s${'$'}", " ")
			find("space-em", "^\\s${'$'}", " ")
			find("space-nel", "^\\s${'$'}", "\u0085")
			find("space-vt", "^\\s${'$'}", "\u000B")
			find("boundary-accent", "a\\b", "aé")
			find("dot-lf", "^.${'$'}", "\n")
			find("dot-cr", "^.${'$'}", "\r")
			find("dot-nel", "^.${'$'}", "\u0085")
			find("dot-line-separator", "^.${'$'}", " ")
			find("dot-emoji", "^.${'$'}", "😀")
			find("two-dots-emoji", "^..${'$'}", "😀")
			find("letter-deseret", "^\\p{L}${'$'}", "𐐀")
			find("upper-deseret", "^\\p{Lu}${'$'}", "𐐀")
			option("ic-ascii", "^a${'$'}", RegexOption.IGNORE_CASE, "A")
			option("ic-accent", "^é${'$'}", RegexOption.IGNORE_CASE, "É")
			option("ic-sharp-s", "^straße${'$'}", RegexOption.IGNORE_CASE, "STRASSE")
			option("ic-kelvin", "^k${'$'}", RegexOption.IGNORE_CASE, "K")
			option("ic-dz", "^ǆ${'$'}", RegexOption.IGNORE_CASE, "ǅ")
			option("ic-final-sigma", "^σ${'$'}", RegexOption.IGNORE_CASE, "ς")
			option("ic-dotted-i", "^i${'$'}", RegexOption.IGNORE_CASE, "İ")
			option("ic-dotless-i", "^ı${'$'}", RegexOption.IGNORE_CASE, "I")
			option("ic-deseret", "^𐐨${'$'}", RegexOption.IGNORE_CASE, "𐐀")
		""",
	) {
		find("digit-arabic", "^\\d$", "٣")
		find("digit-fullwidth", "^\\d$", "３")
		find("word-accent", "^\\w$", "é")
		find("word-greek", "^\\w$", "α")
		find("word-underscore", "^\\w$", "_")
		find("space-nbsp", "^\\s$", " ")
		find("space-em", "^\\s$", " ")
		find("space-nel", "^\\s$", "\u0085")
		find("space-vt", "^\\s$", "\u000B")
		find("boundary-accent", "a\\b", "aé")
		find("dot-lf", "^.$", "\n")
		find("dot-cr", "^.$", "\r")
		find("dot-nel", "^.$", "\u0085")
		find("dot-line-separator", "^.$", " ")
		find("dot-emoji", "^.$", "😀")
		find("two-dots-emoji", "^..$", "😀")
		find("letter-deseret", "^\\p{L}$", "𐐀")
		find("upper-deseret", "^\\p{Lu}$", "𐐀")
		option("ic-ascii", "^a$", RegexOption.IGNORE_CASE, "A")
		option("ic-accent", "^é$", RegexOption.IGNORE_CASE, "É")
		option("ic-sharp-s", "^straße$", RegexOption.IGNORE_CASE, "STRASSE")
		option("ic-kelvin", "^k$", RegexOption.IGNORE_CASE, "K")
		option("ic-dz", "^ǆ$", RegexOption.IGNORE_CASE, "ǅ")
		option("ic-final-sigma", "^σ$", RegexOption.IGNORE_CASE, "ς")
		option("ic-dotted-i", "^i$", RegexOption.IGNORE_CASE, "İ")
		option("ic-dotless-i", "^ı$", RegexOption.IGNORE_CASE, "I")
		option("ic-deseret", "^𐐨$", RegexOption.IGNORE_CASE, "𐐀")
	},
	KotlinProbe(
		id = "kotlin.regex.anchors-lines",
		question = "How do ^, $ and line terminators behave, including from a start index?",
		source = """
			fun count(pattern: String, input: String, vararg options: RegexOption) =
				Regex(pattern, options.toSet()).findAll(input).count()
			line("dollar-before-lf", Regex("^a${'$'}").containsMatchIn("a\n"))
			line("dollar-before-crlf", Regex("^a${'$'}").containsMatchIn("a\r\n"))
			line("dollar-before-cr", Regex("^a${'$'}").containsMatchIn("a\r"))
			line("dollar-before-nel", Regex("^a${'$'}").containsMatchIn("a\u0085"))
			line("caret-count-multiline", count("^", "a\nb\n", RegexOption.MULTILINE))
			line("dollar-count-multiline", count("${'$'}", "a\nb\n", RegexOption.MULTILINE))
			line("dollar-count-plain", count("${'$'}", "a\n"))
			line("crlf-multiline", count("^b${'$'}", "a\r\nb\r\nc", RegexOption.MULTILINE))
			line("cr-multiline", count("^b${'$'}", "a\rb", RegexOption.MULTILINE))
			line("nel-multiline", count("^b${'$'}", "a\u0085b", RegexOption.MULTILINE))
			line("ls-multiline", count("^b${'$'}", "a b", RegexOption.MULTILINE))
			line("dot-star-lines", Regex(".*").findAll("a\nb").map { it.value }.toList())
			line("word-boundaries", count("\\b", "ab cd"))
			line("caret-from-index", Regex("^b").find("ab", 1) != null)
			line("lookbehind-from-index", Regex("(?<=a)b").find("ab", 1)?.value)
			line("matchAt-lookbehind", Regex("(?<=a)b").matchAt("ab", 1)?.value)
			line("matchAt-caret", Regex("^b").matchAt("ab", 1)?.value)
		""",
	) {
		fun count(pattern: String, input: String, vararg options: RegexOption) =
			Regex(pattern, options.toSet()).findAll(input).count()
		line("dollar-before-lf", Regex("^a$").containsMatchIn("a\n"))
		line("dollar-before-crlf", Regex("^a$").containsMatchIn("a\r\n"))
		line("dollar-before-cr", Regex("^a$").containsMatchIn("a\r"))
		line("dollar-before-nel", Regex("^a$").containsMatchIn("a\u0085"))
		line("caret-count-multiline", count("^", "a\nb\n", RegexOption.MULTILINE))
		line("dollar-count-multiline", count("$", "a\nb\n", RegexOption.MULTILINE))
		line("dollar-count-plain", count("$", "a\n"))
		line("crlf-multiline", count("^b$", "a\r\nb\r\nc", RegexOption.MULTILINE))
		line("cr-multiline", count("^b$", "a\rb", RegexOption.MULTILINE))
		line("nel-multiline", count("^b$", "a\u0085b", RegexOption.MULTILINE))
		line("ls-multiline", count("^b$", "a b", RegexOption.MULTILINE))
		line("dot-star-lines", Regex(".*").findAll("a\nb").map { it.value }.toList())
		line("word-boundaries", count("\\b", "ab cd"))
		line("caret-from-index", Regex("^b").find("ab", 1) != null)
		line("lookbehind-from-index", Regex("(?<=a)b").find("ab", 1)?.value)
		line("matchAt-lookbehind", Regex("(?<=a)b").matchAt("ab", 1)?.value)
		line("matchAt-caret", Regex("^b").matchAt("ab", 1)?.value)
	},
	KotlinProbe(
		id = "kotlin.regex.groups-replace",
		question = "How do groups, empty matches, alternation and replacement strings behave?",
		source = """
			val repeat = Regex("(?:(a)|b)*").find("ab")!!
			val optional = Regex("(a)|(b)").find("b")!!
			line("group-in-repeat", repeat.groupValues)
			line("group-in-repeat-null", repeat.groups[1]?.value)
			line("optional-group-values", optional.groupValues)
			line("optional-group-null", optional.groups[1])
			line("named-group", Regex("(?<n>\\d+)").find("x42")?.groups?.get("n")?.value)
			line("destructured", Regex("(\\d)(\\d)").find("12")?.destructured?.toList())
			line("alternation-order", Regex("a|ab").find("abc")?.value)
			line("alternation-groups", Regex("(a|ab)(c|bcd)").find("abcd")?.groupValues)
			line("empty-matches", Regex("a*").findAll("baac").map { it.value }.toList())
			line("empty-replace", Regex("a*").replace("baac", "-"))
			line("empty-split", Regex("x*").split("axb"))
			line("replace-group", Regex("(a)(b)?").replace("ac", "[${'$'}1|${'$'}2]"))
			line("replace-named", Regex("(?<n>a)").replace("a", "[\${'$'}{n}]"))
			line("replace-whole", Regex("a").replace("a", "[${'$'}0]"))
			outcome("replace-missing-group") { Regex("a").replace("a", "${'$'}9") }
			outcome("replace-lone-dollar") { Regex("a").replace("a", "${'$'}") }
			outcome("replace-backslash") { Regex("a").replace("a", "\\\\${'$'}") }
			line("escape-replacement", Regex.escapeReplacement("${'$'}1\\"))
			line("replace-lambda", Regex("a").replace("a") { "${'$'}1" })
			line("lazy", Regex("a+?").find("aaa")?.value)
			line("nested-empty-loop", Regex("(a*)*").find("b")?.groupValues)
			line("nested-plus-empty", Regex("(a*)+").find("b")?.groupValues)
		""",
	) {
		val repeat = Regex("(?:(a)|b)*").find("ab")!!
		val optional = Regex("(a)|(b)").find("b")!!
		line("group-in-repeat", repeat.groupValues)
		line("group-in-repeat-null", repeat.groups[1]?.value)
		line("optional-group-values", optional.groupValues)
		line("optional-group-null", optional.groups[1])
		line("named-group", Regex("(?<n>\\d+)").find("x42")?.groups?.get("n")?.value)
		line("destructured", Regex("(\\d)(\\d)").find("12")?.destructured?.toList())
		line("alternation-order", Regex("a|ab").find("abc")?.value)
		line("alternation-groups", Regex("(a|ab)(c|bcd)").find("abcd")?.groupValues)
		line("empty-matches", Regex("a*").findAll("baac").map { it.value }.toList())
		line("empty-replace", Regex("a*").replace("baac", "-"))
		line("empty-split", Regex("x*").split("axb"))
		line("replace-group", Regex("(a)(b)?").replace("ac", "[$1|$2]"))
		line("replace-named", Regex("(?<n>a)").replace("a", "[\${n}]"))
		line("replace-whole", Regex("a").replace("a", "[$0]"))
		outcome("replace-missing-group") { Regex("a").replace("a", "$9") }
		outcome("replace-lone-dollar") { Regex("a").replace("a", "$") }
		outcome("replace-backslash") { Regex("a").replace("a", "\\\\$") }
		line("escape-replacement", Regex.escapeReplacement("$1\\"))
		line("replace-lambda", Regex("a").replace("a") { "$1" })
		line("lazy", Regex("a+?").find("aaa")?.value)
		line("nested-empty-loop", Regex("(a*)*").find("b")?.groupValues)
		line("nested-plus-empty", Regex("(a*)+").find("b")?.groupValues)
	},
)
