package dev.gmitch215.drift.scan.atlas

internal const val WIDTH = 100

private const val HEX_DIGITS = "0123456789abcdef"

/** Printable ASCII only: any other UTF-16 unit becomes \uXXXX, so output is the same everywhere. */
internal fun ascii(text: String): String = buildString {
	for (c in text) {
		if (c.code in 0x20..0x7e) {
			append(c)
		} else {
			append("\\u")
			for (shift in intArrayOf(12, 8, 4, 0)) append(HEX_DIGITS[(c.code shr shift) and 15])
		}
	}
}

internal fun wrap(text: String, width: Int = WIDTH, indent: String = ""): List<String> {
	val out = mutableListOf<String>()
	var line = StringBuilder()
	for (word in text.split(' ').filter { it.isNotEmpty() }) {
		if (line.isNotEmpty() && indent.length + line.length + 1 + word.length > width) {
			out += indent + line
			line = StringBuilder()
		}
		if (line.isNotEmpty()) line.append(' ')
		line.append(word)
	}
	if (line.isNotEmpty()) out += indent + line
	return out
}

/** Indexes at which the variants are not all equal; a missing line differs from any line. */
internal fun differingLines(variants: List<List<String>>): List<Int> {
	val size = variants.maxOfOrNull { it.size } ?: 0
	return (0 until size).filter { i -> variants.map { it.getOrNull(i) }.distinct().size > 1 }
}

internal fun pad(text: String, width: Int): String =
	text + " ".repeat(maxOf(0, width - text.length))
