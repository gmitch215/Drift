package dev.gmitch215.drift

import dev.gmitch215.drift.lab.TrialGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrialGateTest {
    @Test
    fun theHashIsTheFixedIntegerFunction() {
        assertEquals(
            listOf(1747545881L, 1963196093L, 562409673L, 3660426141L, 1721113111L),
            (0 until 5).map { TrialGate.hash(it, 12345) },
        )
    }

    @Test
    fun aGateArmsAFixedShareOfTrialsAndNamesTheVariable() {
        val gate = TrialGate(500, 987, "BENCH_FLAKE")
        val armed = (0 until 1000).filter { gate.armed(it) }
        assertEquals(533, armed.size)
        assertEquals("BENCH_FLAKE=1", gate.env(armed.first()))
        assertEquals("BENCH_FLAKE=0", gate.env((0 until 1000).first { !gate.armed(it) }))
        assertTrue((0 until 50).all { TrialGate(1000, 1, "X").armed(it) })
        assertTrue((0 until 50).none { TrialGate(0, 1, "X").armed(it) })
    }

    @Test
    fun theTextFormParsesWithDefaults() {
        assertEquals(TrialGate(500, 0, "DRIFT_GATE"), TrialGate.parse("permille=500"))
        assertEquals(
            TrialGate(250, 77, "BENCH_FLAKE"),
            TrialGate.parse("permille=250, salt=77,env=BENCH_FLAKE"),
        )
    }

    @Test
    fun badGatesAreTypedErrors() {
        assertFailsWith<IllegalArgumentException> { TrialGate.parse("salt=1") }
        assertFailsWith<IllegalArgumentException> { TrialGate.parse("permille=x") }
        assertFailsWith<IllegalArgumentException> { TrialGate.parse("permille=5,salt=z") }
        assertFailsWith<IllegalArgumentException> { TrialGate.parse("permille=5,rate=1") }
        assertFailsWith<IllegalArgumentException> { TrialGate.parse("permille=1001") }
        assertFailsWith<IllegalArgumentException> { TrialGate.parse("permille=5,env=a b") }
    }
}
