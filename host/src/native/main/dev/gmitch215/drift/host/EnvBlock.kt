package dev.gmitch215.drift.host

internal fun parseEnvBlock(block: CharArray): Map<String, String> {
	val result = HashMap<String, String>()
	var start = 0
	while (start < block.size) {
		var end = start
		while (end < block.size && block[end] != '\u0000') end++
		if (end == start) break
		val entry = block.concatToString(start, end)
		start = end + 1
		if (entry[0] == '=') continue
		val eq = entry.indexOf('=')
		if (eq < 0) result[entry] = "" else result[entry.substring(0, eq)] = entry.substring(eq + 1)
	}
	return result.entries.sortedBy { it.key }.associate { it.key to it.value }
}
