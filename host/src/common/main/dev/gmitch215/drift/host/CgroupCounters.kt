package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.CgroupDelta

private const val ROOT = "/sys/fs/cgroup"

/**
 * The counters of the cgroup v2 that holds this process at one moment: `nr_throttled` of
 * `cpu.stat` and `max`, `oom` and `oom_kill` of `memory.events`. A field is null when its file
 * or counter is missing.
 */
data class CgroupReading(
    val throttled: Long?,
    val memoryMax: Long?,
    val oom: Long?,
    val oomKill: Long?,
) {
    /** What grew since [before]; a counter that is missing at either end or went down is null. */
    fun since(before: CgroupReading?): CgroupDelta? {
        if (before == null) return null
        fun grew(now: Long?, then: Long?) =
            if (now != null && then != null && now >= then) now - then else null

        val delta = CgroupDelta(
            grew(throttled, before.throttled),
            grew(memoryMax, before.memoryMax),
            grew(oom, before.oom),
            grew(oomKill, before.oomKill),
        )
        return delta.takeIf { it != CgroupDelta() }
    }
}

private fun counters(text: String?): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (line in text.orEmpty().lines()) {
        val parts = line.trim().split(' ')
        if (parts.size == 2) parts[1].toLongOrNull()?.let { out[parts[0]] = it }
    }
    return out
}

/** Null off Linux, without cgroup v2, or when none of the four counters can be read. */
fun Host.cgroupReading(): CgroupReading? {
    if (os != "linux") return null
    val path = readText("/proc/self/cgroup").orEmpty().lines()
        .firstOrNull { it.startsWith("0::") }?.removePrefix("0::")?.trim()
    if (path == null || !path.startsWith("/") || ".." in path.split('/')) return null
    val dir = ROOT + path.trimEnd('/')
    val cpu = counters(readText("$dir/cpu.stat"))
    val memory = counters(readText("$dir/memory.events"))
    val reading = CgroupReading(
        cpu["nr_throttled"],
        memory["max"],
        memory["oom"],
        memory["oom_kill"],
    )
    return reading.takeIf { it != CgroupReading(null, null, null, null) }
}
