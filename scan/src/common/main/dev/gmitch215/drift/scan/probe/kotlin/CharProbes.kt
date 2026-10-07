package dev.gmitch215.drift.scan.probe.kotlin

private fun units(vararg codes: Int): String = codes.map { Char(it) }.toCharArray().concatToString()

internal val charProbes = listOf(
	KotlinProbe(
		id = "kotlin.char.bmp-categories",
		question = "How many BMP characters does each Char predicate and category accept?",
		source = """
			val counts = IntArray(CharCategory.entries.size)
			var letters = 0
			var digits = 0
			var upper = 0
			var lower = 0
			var title = 0
			var white = 0
			var digitSum = 0
			for (code in 0..0xFFFF) {
				val ch = Char(code)
				counts[ch.category.ordinal]++
				if (ch.isLetter()) letters++
				if (ch.isDigit()) digits++
				if (ch.isUpperCase()) upper++
				if (ch.isLowerCase()) lower++
				if (ch.isTitleCase()) title++
				if (ch.isWhitespace()) white++
				digitSum += ch.digitToIntOrNull() ?: 0
			}
			line("letters", letters)
			line("digits", digits)
			line("upper", upper)
			line("lower", lower)
			line("title", title)
			line("whitespace", white)
			line("digit-value-sum", digitSum)
			val all = CharCategory.entries
			line("categories", all.joinToString(" ") { it.name + "=" + counts[it.ordinal] })
		""",
	) {
		val counts = IntArray(CharCategory.entries.size)
		var letters = 0
		var digits = 0
		var upper = 0
		var lower = 0
		var title = 0
		var white = 0
		var digitSum = 0
		for (code in 0..0xFFFF) {
			val ch = Char(code)
			counts[ch.category.ordinal]++
			if (ch.isLetter()) letters++
			if (ch.isDigit()) digits++
			if (ch.isUpperCase()) upper++
			if (ch.isLowerCase()) lower++
			if (ch.isTitleCase()) title++
			if (ch.isWhitespace()) white++
			digitSum += ch.digitToIntOrNull() ?: 0
		}
		line("letters", letters)
		line("digits", digits)
		line("upper", upper)
		line("lower", lower)
		line("title", title)
		line("whitespace", white)
		line("digit-value-sum", digitSum)
		line(
			"categories",
			CharCategory.entries.joinToString(" ") { it.name + "=" + counts[it.ordinal] },
		)
	},
	KotlinProbe(
		id = "kotlin.char.bmp-case-mapping",
		question = "Do uppercase, lowercase and titlecase map every BMP character the same way?",
		source = """
			var up = 0
			var low = 0
			var tit = 0
			var upChanged = 0
			var lowChanged = 0
			var titChanged = 0
			var upString = 0
			var lowString = 0
			for (code in 0..0xFFFF) {
				val ch = Char(code)
				val u = ch.uppercaseChar().code
				val l = ch.lowercaseChar().code
				val t = ch.titlecaseChar().code
				up = up * 31 + u
				low = low * 31 + l
				tit = tit * 31 + t
				if (u != code) upChanged++
				if (l != code) lowChanged++
				if (t != code) titChanged++
				upString += ch.toString().uppercase().length
				lowString += ch.toString().lowercase().length
			}
			line("upper-checksum", up)
			line("lower-checksum", low)
			line("title-checksum", tit)
			line("upper-changed", upChanged)
			line("lower-changed", lowChanged)
			line("title-changed", titChanged)
			line("upper-string-length-sum", upString)
			line("lower-string-length-sum", lowString)
		""",
	) {
		var up = 0
		var low = 0
		var tit = 0
		var upChanged = 0
		var lowChanged = 0
		var titChanged = 0
		var upString = 0
		var lowString = 0
		for (code in 0..0xFFFF) {
			val ch = Char(code)
			val u = ch.uppercaseChar().code
			val l = ch.lowercaseChar().code
			val t = ch.titlecaseChar().code
			up = up * 31 + u
			low = low * 31 + l
			tit = tit * 31 + t
			if (u != code) upChanged++
			if (l != code) lowChanged++
			if (t != code) titChanged++
			upString += ch.toString().uppercase().length
			lowString += ch.toString().lowercase().length
		}
		line("upper-checksum", up)
		line("lower-checksum", low)
		line("title-checksum", tit)
		line("upper-changed", upChanged)
		line("lower-changed", lowChanged)
		line("title-changed", titChanged)
		line("upper-string-length-sum", upString)
		line("lower-string-length-sum", lowString)
	},
	KotlinProbe(
		id = "kotlin.char.whitespace-set",
		question = "Which BMP code points does isWhitespace accept?",
		source = """
			val white = (0..0xFFFF).filter { Char(it).isWhitespace() }
			val spaces = (0..0xFFFF).filter { Char(it).category == CharCategory.SPACE_SEPARATOR }
			val control = (0..0xFFFF).filter { Char(it).isISOControl() }
			line("whitespace", white.map { it.toString(16) })
			line("space-separators", spaces.map { it.toString(16) })
			line("iso-control-count", control.size)
			line("trim-strips-nbsp", ("x" + Char(0xA0)).trim().length)
			line("trim-strips-zwsp", ("x" + Char(0x200B)).trim().length)
			line("trim-strips-bom", ("x" + Char(0xFEFF)).trim().length)
			line("trim-strips-nel", ("x" + Char(0x85)).trim().length)
			line("blank-ideographic", Char(0x3000).toString().isBlank())
		""",
	) {
		val white = (0..0xFFFF).filter { Char(it).isWhitespace() }
		val spaces = (0..0xFFFF).filter { Char(it).category == CharCategory.SPACE_SEPARATOR }
		val control = (0..0xFFFF).filter { Char(it).isISOControl() }
		line("whitespace", white.map { it.toString(16) })
		line("space-separators", spaces.map { it.toString(16) })
		line("iso-control-count", control.size)
		line("trim-strips-nbsp", ("x" + Char(0xA0)).trim().length)
		line("trim-strips-zwsp", ("x" + Char(0x200B)).trim().length)
		line("trim-strips-bom", ("x" + Char(0xFEFF)).trim().length)
		line("trim-strips-nel", ("x" + Char(0x85)).trim().length)
		line("blank-ideographic", Char(0x3000).toString().isBlank())
	},
	KotlinProbe(
		id = "kotlin.char.case-special",
		question = "Which special case mappings (sharp s, dotted I, sigma, ligatures) apply?",
		source = """
			line("upper-sharp-s", codes("ß".uppercase()))
			line("upper-char-sharp-s", Char(0xDF).uppercaseChar().code.toString(16))
			line("lower-dotted-I", codes("İ".lowercase()))
			line("lower-char-dotted-I", Char(0x130).lowercaseChar().code.toString(16))
			line("upper-dotless-i", codes("ı".uppercase()))
			line("upper-i", codes("i".uppercase()))
			line("lower-I", codes("I".lowercase()))
			line("upper-n-apostrophe", codes("ŉ".uppercase()))
			line("upper-j-caron", codes("ǰ".uppercase()))
			line("upper-iota-tonos", codes("ΐ".uppercase()))
			line("upper-ligature-fi", codes("ﬁ".uppercase()))
			line("upper-greek-iota-sub", codes("ᾳ".uppercase()))
			line("lower-final-sigma", codes("ΑΣ".lowercase()))
			line("lower-sigma-space", codes("ΑΣ ".lowercase()))
			line("lower-sigma-middle", codes("ΑΣΑ".lowercase()))
			line("lower-sigma-alone", codes("Σ".lowercase()))
			line("title-dz", codes("ǆ".replaceFirstChar { it.titlecase() }))
			line("upper-dz", codes("ǆ".uppercase()))
			line("lower-DZ", codes("Ǆ".lowercase()))
			line("lower-capital-sharp-s", codes("ẞ".lowercase()))
			line("upper-roman-8", codes("ⅷ".uppercase()))
			line("upper-circled-a", codes("ⓐ".uppercase()))
			line("upper-ypogegrammeni", codes("ͅ".uppercase()))
			line("upper-kelvin-lower", codes("K".lowercase()))
			line("upper-micro", codes("µ".uppercase()))
		""",
	) {
		line("upper-sharp-s", codes("ß".uppercase()))
		line("upper-char-sharp-s", Char(0xDF).uppercaseChar().code.toString(16))
		line("lower-dotted-I", codes("İ".lowercase()))
		line("lower-char-dotted-I", Char(0x130).lowercaseChar().code.toString(16))
		line("upper-dotless-i", codes("ı".uppercase()))
		line("upper-i", codes("i".uppercase()))
		line("lower-I", codes("I".lowercase()))
		line("upper-n-apostrophe", codes("ŉ".uppercase()))
		line("upper-j-caron", codes("ǰ".uppercase()))
		line("upper-iota-tonos", codes("ΐ".uppercase()))
		line("upper-ligature-fi", codes("ﬁ".uppercase()))
		line("upper-greek-iota-sub", codes("ᾳ".uppercase()))
		line("lower-final-sigma", codes("ΑΣ".lowercase()))
		line("lower-sigma-space", codes("ΑΣ ".lowercase()))
		line("lower-sigma-middle", codes("ΑΣΑ".lowercase()))
		line("lower-sigma-alone", codes("Σ".lowercase()))
		line("title-dz", codes("ǆ".replaceFirstChar { it.titlecase() }))
		line("upper-dz", codes("ǆ".uppercase()))
		line("lower-DZ", codes("Ǆ".lowercase()))
		line("lower-capital-sharp-s", codes("ẞ".lowercase()))
		line("upper-roman-8", codes("ⅷ".uppercase()))
		line("upper-circled-a", codes("ⓐ".uppercase()))
		line("upper-ypogegrammeni", codes("ͅ".uppercase()))
		line("upper-kelvin-lower", codes("K".lowercase()))
		line("upper-micro", codes("µ".uppercase()))
	},
	KotlinProbe(
		id = "kotlin.string.supplementary",
		question = "How does a string with a supplementary character behave as UTF-16 units?",
		source = """
			val emoji = units(0xD83D, 0xDE00)
			val deseret = units(0xD801, 0xDC00)
			line("length", emoji.length)
			line("count", emoji.count())
			line("units", codes(emoji))
			line("reversed", codes(emoji.reversed()))
			line("first-unit", codes(emoji.substring(0, 1)))
			line("lone-high-upper", codes(units(0xD83D).uppercase()))
			line("deseret-lower", codes(deseret.lowercase()))
			line("deseret-upper-of-lower", codes(units(0xD801, 0xDC28).uppercase()))
			line("deseret-is-letter", deseret.all { it.isLetter() })
			line("high-surrogate", emoji[0].isHighSurrogate())
			line("category-high", emoji[0].category)
			line("compare-emoji-vs-ffff", emoji.compareTo(units(0xFFFF)))
			line("compare-emoji-vs-e000", emoji.compareTo(units(0xE000)))
			line("equals-after-copy", emoji == units(0xD83D, 0xDE00))
			line("padStart", codes(emoji.padStart(3, '.')))
		""",
	) {
		val emoji = units(0xD83D, 0xDE00)
		val deseret = units(0xD801, 0xDC00)
		line("length", emoji.length)
		line("count", emoji.count())
		line("units", codes(emoji))
		line("reversed", codes(emoji.reversed()))
		line("first-unit", codes(emoji.substring(0, 1)))
		line("lone-high-upper", codes(units(0xD83D).uppercase()))
		line("deseret-lower", codes(deseret.lowercase()))
		line("deseret-upper-of-lower", codes(units(0xD801, 0xDC28).uppercase()))
		line("deseret-is-letter", deseret.all { it.isLetter() })
		line("high-surrogate", emoji[0].isHighSurrogate())
		line("category-high", emoji[0].category)
		line("compare-emoji-vs-ffff", emoji.compareTo(units(0xFFFF)))
		line("compare-emoji-vs-e000", emoji.compareTo(units(0xE000)))
		line("equals-after-copy", emoji == units(0xD83D, 0xDE00))
		line("padStart", codes(emoji.padStart(3, '.')))
	},
)
