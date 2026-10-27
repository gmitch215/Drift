package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FsyncTest {
    @Test
    fun aDirectoryThatDoesNotExistGivesNoSamples() {
        assertNull(systemHost().syncNanos("/drift-no-such-directory", 3))
    }

    @Test
    fun oneTimingComesBackPerRoundInTmp() {
        // null on targets without an fsync (jvm, mingw, wasm, ios, android)
        val samples = systemHost().syncNanos("/tmp", 3) ?: return
        assertEquals(3, samples.size)
        assertTrue(samples.all { it > 0 })
    }
}
