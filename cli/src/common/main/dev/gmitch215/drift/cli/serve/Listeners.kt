package dev.gmitch215.drift.cli.serve

import kotlin.io.encoding.Base64

internal class Listeners<S>(val sockets: List<S>, val addresses: List<String>, val port: Int)

/** Opens IPv4 first, then IPv6 on the port IPv4 got; no IPv6 leaves IPv4 alone. */
internal fun <S> openListeners(
    bind: Bind,
    port: Int,
    open: (v6: Boolean, all: Boolean, port: Int) -> S,
    portOf: (S) -> Int,
): Listeners<S> {
    val all = bind == Bind.ALL
    val first = try {
        open(false, all, port)
    } catch (e: ServeFailure) {
        throw ServeFailure(
            "cannot listen on port $port: ${e.message}; pass --port 0 to take a free port",
        )
    }
    val real = portOf(first)
    val sockets = mutableListOf(first)
    val addresses = mutableListOf((if (all) "0.0.0.0" else "127.0.0.1") + ":$real")
    try {
        sockets += open(true, all, real)
        addresses += (if (all) "[::]" else "[::1]") + ":$real"
    } catch (_: ServeFailure) {
        // no IPv6 loopback here; IPv4 alone still serves localhost
    }
    return Listeners(sockets, addresses, real)
}

/** Calls [send] with the offset and the bytes left until [length] went out; false on a failure. */
internal fun sendAll(length: Int, send: (at: Int, left: Int) -> Int): Boolean {
    var at = 0
    while (at < length) {
        val n = send(at, length - at)
        if (n <= 0) return false
        at += n
    }
    return true
}

/** The decoded body of the first `-----BEGIN [label]-----` block of [text], or null. */
internal fun pemBlock(text: String, label: String): ByteArray? {
    val begin = "-----BEGIN $label-----"
    val start = text.indexOf(begin)
    if (start < 0) return null
    val end = text.indexOf("-----END $label-----", start)
    if (end < 0) return null
    val body = text.substring(start + begin.length, end).filterNot { it.isWhitespace() }
    return Base64.decode(body)
}

/** The shell line that starts [argv] in the background and prints its pid. */
internal fun backgroundLine(argv: List<String>): String =
    argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } + " >/dev/null 2>&1 & echo \$!"

/** Whether a `/proc/<pid>/stat` line says the process is a zombie. */
internal fun isZombie(stat: String): Boolean = stat.substringAfterLast(") ").firstOrNull() == 'Z'
