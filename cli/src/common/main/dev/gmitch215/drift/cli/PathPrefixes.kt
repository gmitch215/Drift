package dev.gmitch215.drift.cli

/** The leading runs of [path]'s [separator]-split parts, from [first] parts on, shortest first. */
internal fun pathPrefixes(
    path: String,
    separator: Char,
    first: Int,
    parentsOnly: Boolean = false,
): List<String> {
    val parts = path.split(separator)
    val last = if (parentsOnly) parts.size - 1 else parts.size
    return (first..last).map { parts.take(it).joinToString(separator.toString()) }
}
