package dev.gmitch215.drift.scan.probe

import dev.gmitch215.drift.host.Capability

private val FILES = setOf(Capability.FILES)

private fun cgroupCpu(text: String?): String {
    val parts = text?.trim()?.split(' ') ?: return "n/a"
    val quota = parts.getOrNull(0) ?: return "n/a"
    val period = parts.getOrNull(1)?.toLongOrNull() ?: return "n/a"
    if (quota == "max") return "unlimited"
    val q = quota.toLongOrNull() ?: return "n/a"
    return "limited cpus=${q / period}.${(q % period) * 100 / period}"
}

internal val resourceProbes = listOf(
    Probe("resources.cgroup-cpu", Family.RESOURCES, FILES) {
        line("cpu-max", cgroupCpu(host.readText("/sys/fs/cgroup/cpu.max")))
    },
    Probe("resources.cgroup-memory", Family.RESOURCES, FILES) {
        val raw = host.readText("/sys/fs/cgroup/memory.max")?.trim()
        line(
            "memory-max",
            if (raw ==
                null
            ) {
                "n/a"
            } else if (raw == "max") {
                "unlimited"
            } else {
                "limited bytes=$raw"
            },
        )
    },
    Probe("resources.missing-file", Family.RESOURCES, FILES) {
        line("read-missing", host.readText("/nonexistent/drift-probe"))
    },
    Probe("resources.locale-env", Family.RESOURCES, setOf(Capability.ENV)) {
        val env = host.env()
        for (name in listOf("LANG", "LC_ALL", "LC_CTYPE", "TZ")) line(name, env[name])
    },
    Probe("resources.fs-tmp-fsync", Family.RESOURCES, setOf(Capability.WRITE_FILES)) {
        val samples = host.syncNanos("/tmp", FSYNC_ROUNDS)?.takeIf { it.isNotEmpty() }
            ?: error("no write and fsync in /tmp")
        line("fs.tmp.fsync", fsyncBucket(samples.sorted()[samples.size / 2]))
    },
)

private const val FSYNC_ROUNDS = 25

internal fun fsyncBucket(nanos: Long): String = when {
    nanos < MILLI -> "<1ms"
    nanos < 10 * MILLI -> "1-10ms"
    nanos < 100 * MILLI -> "10-100ms"
    nanos < 1_000 * MILLI -> "100ms-1s"
    else -> ">1s"
}

private const val MILLI = 1_000_000L
