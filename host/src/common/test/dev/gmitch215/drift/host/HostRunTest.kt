package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class HostRunTest {
    @Test
    fun streamingRunHandsTheOutputToTheCallbackAndEmptiesTheResult() {
        val host = FakeHost(commands = mapOf(listOf("t") to CommandResult(4, "one\ntwo\n")))
        val seen = mutableListOf<String>()
        val result = host.run(listOf("t")) { seen += it }
        assertEquals(CommandResult(4, ""), result)
        assertEquals(listOf("one\ntwo\n"), seen)
    }

    @Test
    fun streamingRunSkipsTheCallbackForNoOutputAndReportsAMissingCommand() {
        val host = FakeHost(commands = mapOf(listOf("quiet") to CommandResult(0, "")))
        val seen = mutableListOf<String>()
        assertEquals(0, host.run(listOf("quiet")) { seen += it }?.exitCode)
        assertEquals(emptyList(), seen)
        assertNull(FakeHost(canRun = false).run(listOf("quiet")) { seen += it })
    }

    @Test
    fun streamingRunKeepsOutputAfterANulByte() {
        val host = systemHost()
        if (Capability.PROCESS !in host.capabilities || host.os == "windows") return
        val seen = StringBuilder()
        val result = host.run(listOf("sh", "-c", "printf 'a\\000b\\n'")) { seen.append(it) }
        assertEquals(0, result?.exitCode)
        assertEquals("a\u0000b\n", seen.toString())
    }

    @Test
    fun runReturnsWhenTheChildExitsEvenIfAGrandchildKeepsStdoutOpen() {
        val host = systemHost()
        if (Capability.PROCESS !in host.capabilities || host.os == "windows") return
        val seen = StringBuilder()
        val mark = TimeSource.Monotonic.markNow()
        val result = host.run(listOf("sh", "-c", "(sleep 30 &); echo done")) { seen.append(it) }
        assertEquals(0, result?.exitCode)
        assertEquals("done\n", seen.toString())
        assertTrue(mark.elapsedNow().inWholeSeconds < 10, "run waited for the grandchild")
    }

    @Test
    fun runPastItsDeadlineKillsTheChildAndReportsTheTimeoutCode() {
        val host = systemHost()
        if (Capability.PROCESS !in host.capabilities || host.os == "windows") return
        val mark = TimeSource.Monotonic.markNow()
        val result = host.run(listOf("sleep", "30"), timeoutMillis = 300) { }
        assertEquals(124, result?.exitCode)
        assertTrue(mark.elapsedNow().inWholeSeconds < 10, "run waited for the child")
    }

    @Test
    fun runReportsNullForAProgramThatCannotStart() {
        val host = systemHost()
        if (Capability.PROCESS !in host.capabilities || host.os == "windows") return
        assertNull(host.run(listOf("drift-no-such-program")) { })
        assertNull(host.run(listOf("/drift/no/such/program")) { })
    }

    @Test
    fun lineSplitterJoinsPiecesAndKeepsMultibyteText() {
        val seen = mutableListOf<String>()
        val lines = LineSplitter { seen += it }
        val bytes = "caf\u00e9\n".encodeToByteArray()
        lines.feed(bytes.copyOfRange(0, 4))
        lines.feed(bytes.copyOfRange(4, bytes.size))
        lines.feed("tail".encodeToByteArray())
        lines.flush()
        lines.flush()
        assertEquals(listOf("caf\u00e9\n", "tail"), seen)
    }

    @Test
    fun waitStatusCodeReadsTheExitByteOrAddsTheSignalTo128() {
        assertEquals(0, waitStatusCode(0))
        assertEquals(7, waitStatusCode(7 shl 8))
        assertEquals(255, waitStatusCode(255 shl 8))
        assertEquals(137, waitStatusCode(9))
        assertEquals(139, waitStatusCode(11))
    }

    @Test
    fun findProgramChecksADirectPathOrEveryPathEntry() {
        val seen = mutableListOf<String>()
        val executable = { name: String ->
            seen.add(name)
            name == "/b/tool"
        }
        assertTrue(findProgram("tool", "/a:/b", executable))
        assertEquals(listOf("/a/tool", "/b/tool"), seen)
        assertTrue(findProgram("/b/tool", "", executable))
        assertTrue(!findProgram("/c/tool", "/b", executable))
        assertTrue(!findProgram("tool", "/a:/c", executable))
        assertTrue(!findProgram(null, "/b", executable))
        assertTrue(!findProgram("", "/b", executable))
        seen.clear()
        assertTrue(!findProgram("tool", ":/c", executable))
        assertEquals(listOf("./tool", "/c/tool"), seen)
    }
}
