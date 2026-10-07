package dev.gmitch215.drift.cli.serve

internal fun swap(value: Int) = ((value and 0xff) shl 8) or ((value shr 8) and 0xff)

/** Text for 4 (IPv4) or 16 (IPv6) address bytes, with the longest zero run folded to `::`. */
internal fun addressText(bytes: List<Int>): String {
	if (bytes.size == 4) return bytes.joinToString(".")
	val groups = (0 until 8).map { (bytes[it * 2] shl 8) or bytes[it * 2 + 1] }
	var best = -1
	var bestLength = 1
	var at = 0
	while (at < 8) {
		if (groups[at] != 0) {
			at++
			continue
		}
		var end = at
		while (end < 8 && groups[end] == 0) end++
		if (end - at > bestLength) {
			best = at
			bestLength = end - at
		}
		at = end
	}
	if (best < 0) return groups.joinToString(":") { it.toString(16) }
	val head = groups.take(best).joinToString(":") { it.toString(16) }
	val tail = groups.drop(best + bestLength).joinToString(":") { it.toString(16) }
	return "$head::$tail"
}
