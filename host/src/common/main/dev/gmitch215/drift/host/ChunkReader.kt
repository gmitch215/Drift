package dev.gmitch215.drift.host

/** Joins what [read] fills into a [chunk]-sized buffer until it returns 0 or less. */
fun readAllBytes(chunk: Int, read: (ByteArray) -> Int): ByteArray {
    val parts = ArrayList<ByteArray>()
    val buffer = ByteArray(chunk)
    while (true) {
        val n = read(buffer)
        if (n <= 0) break
        parts += buffer.copyOf(n)
    }
    val all = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (part in parts) {
        part.copyInto(all, at)
        at += part.size
    }
    return all
}
