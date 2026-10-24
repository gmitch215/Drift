package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.CgroupDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CgroupCountersTest {
    private val cpu = "usage_usec 5\nnr_periods 40\nnr_throttled 7\nthrottled_usec 900\n"
    private val memory = "low 0\nhigh 0\nmax 3\noom 2\noom_kill 1\n"

    private fun host(
        path: String? = "0::/",
        cpuStat: String? = cpu,
        events: String? = memory,
        os: String = "linux",
    ): FakeHost {
        val dir = "/sys/fs/cgroup" + (path?.removePrefix("0::")?.trimEnd('/') ?: "")
        return FakeHost(
            os = os,
            files = listOfNotNull(
                path?.let { "/proc/self/cgroup" to "12:cpu:/legacy\n$it\n" },
                cpuStat?.let { "$dir/cpu.stat" to it },
                events?.let { "$dir/memory.events" to it },
            ).toMap(),
        )
    }

    @Test
    fun readsTheCountersOfTheCgroupTheProcessIsIn() {
        assertEquals(CgroupReading(7, 3, 2, 1), host().cgroupReading())
        assertEquals(
            CgroupReading(7, 3, 2, 1),
            host(path = "0::/system.slice/app.service").cgroupReading(),
        )
    }

    @Test
    fun aMissingFileLeavesOnlyItsCountersAbsent() {
        assertEquals(CgroupReading(null, 3, 2, 1), host(cpuStat = null).cgroupReading())
        assertEquals(CgroupReading(7, null, null, null), host(events = null).cgroupReading())
        assertEquals(
            CgroupReading(null, 3, 2, 1),
            host(cpuStat = "usage_usec 5\nnr_periods 40\n").cgroupReading(),
        )
    }

    @Test
    fun anUnreadableOrAbsentCgroupGivesNoReading() {
        assertNull(host(cpuStat = null, events = null).cgroupReading())
        assertNull(host(path = null).cgroupReading())
        assertNull(host(cpuStat = "garbage\nnr_throttled x\n", events = "").cgroupReading())
        assertNull(host(os = "macos").cgroupReading())
        assertNull(FakeHost(files = mapOf("/proc/self/cgroup" to "1:cpu:/old\n")).cgroupReading())
        assertNull(host(path = "0::/../../escape").cgroupReading())
        assertNull(host(path = "0::relative").cgroupReading())
    }

    @Test
    fun theDeltaIsWhatGrewAndAbsentWhenEitherEndIsMissing() {
        val before = CgroupReading(7, 3, 2, 1)
        assertEquals(
            CgroupDelta(5, 0, 1, 0),
            CgroupReading(12, 3, 3, 1).since(before),
        )
        assertEquals(CgroupDelta(1, null, 0, null), CgroupReading(8, null, 2, null).since(before))
        assertNull(CgroupReading(7, 3, 2, 1).since(null))
        assertNull(CgroupReading(null, null, null, null).since(before))
        assertNull(CgroupReading(1, 0, 0, 0).since(before))
    }
}
