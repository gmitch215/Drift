package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRunTest {
    private val host = systemHost()

    @Test
    fun streamingRunDeliversTheOutputAndTheExitCode() {
        if (host.os == "windows") return
        val seen = mutableListOf<String>()
        val script = "printf 'one\\ntwo\\nthree'; exit 7"
        val result = host.run(listOf("sh", "-c", script)) { seen += it }
        assertEquals(CommandResult(7, ""), result)
        assertEquals("one\ntwo\nthree", seen.joinToString(""))
    }

    @Test
    fun streamingRunMergesStandardErrorAndKeepsMultibyteText() {
        if (host.os == "windows") return
        val seen = StringBuilder()
        val script = "printf 'caf\\303\\251\\n' >&2"
        val result = host.run(listOf("sh", "-c", script)) { seen.append(it) }
        assertEquals(0, result?.exitCode)
        assertEquals("café\n", seen.toString())
    }
}
