package dev.gmitch215.drift.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRunTest {
    private val java = File(System.getProperty("java.home"), "bin/java").path

    @Test
    fun streamingRunDeliversTheOutputAndTheExitCode() {
        val seen = StringBuilder()
        val result = systemHost().run(listOf(java, "-version")) { seen.append(it) }
        assertEquals(CommandResult(0, ""), result)
        assertTrue("version" in seen, "$seen")
    }

    @Test
    fun streamingRunKeepsAFailingExitCodeAndMergesStandardError() {
        val seen = StringBuilder()
        val result = systemHost().run(listOf(java, "-cp", ".", "NoSuchClassForDrift")) {
            seen.append(it)
        }
        assertEquals(1, result?.exitCode)
        assertTrue("NoSuchClassForDrift" in seen, "$seen")
    }

    @Test
    fun streamingRunOfAMissingProgramIsNull() {
        assertNull(systemHost().run(listOf("/no/such/program-for-drift")) { })
    }
}
