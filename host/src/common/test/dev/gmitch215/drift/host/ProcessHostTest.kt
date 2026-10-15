package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessHostTest {
    private val ran = mutableListOf<List<String>>()

    private fun host(
        platform: String,
        output: String? = "A=1\nB=2\n",
        nativeEnv: () -> Map<String, String>? = { null },
    ) = ProcessHost(
        platform,
        "os",
        "arch",
        { path -> if (path == "/f") "text" else null },
        { argv, _, onOutput ->
            ran += argv
            output?.let {
                onOutput(it)
                CommandResult(0, "")
            }
        },
        { listOf(Attribute("extra", "1", "test", Stability.STATIC)) },
        nativeEnv,
    )

    @Test
    fun envRunsEnvOnUnixAndSetThroughCmdOnWindows() {
        assertEquals(mapOf("A" to "1", "B" to "2"), host("linux").env())
        assertEquals(listOf("env"), ran.single())
        ran.clear()
        assertEquals(mapOf("A" to "1", "B" to "2"), host("mingw").env())
        assertEquals(listOf("cmd", "/c", "set"), ran.single())
    }

    @Test
    fun envPrefersTheNativeBlockAndFallsBackToEmptyWhenNothingRuns() {
        assertEquals(mapOf("N" to "1"), host("mingw", nativeEnv = { mapOf("N" to "1") }).env())
        assertEquals(emptyMap(), host("linux", output = null).env())
        assertEquals(1, ran.size)
    }

    @Test
    fun factsAreNonEmptyOnlyForTheKnownPlatformsAndMingwAddsTheExtras() {
        assertEquals(emptyList(), host("wasm").facts())
        assertTrue(host("mingw").facts().any { it.path == "extra" })
        assertTrue(host("linux").facts().none { it.path == "extra" })
        assertTrue(host("macos").facts().none { it.path == "extra" })
    }

    @Test
    fun readsFilesThroughTheCallbackAndCollectsRunOutput() {
        val h = host("linux")
        assertEquals("text", h.readText("/f"))
        assertNull(h.readText("/missing"))
        assertEquals(CommandResult(0, "A=1\nB=2\n"), h.run(listOf("x")))
        assertEquals("os", h.os)
        assertEquals("arch", h.arch)
        assertEquals(Capability.entries.toSet(), h.capabilities)
    }
}
