package dev.gmitch215.drift.model

import dev.gmitch215.drift.json.JsonInt
import dev.gmitch215.drift.json.JsonObject
import dev.gmitch215.drift.json.JsonValue
import dev.gmitch215.drift.json.long
import dev.gmitch215.drift.json.obj
import dev.gmitch215.drift.json.require

/**
 * How much the counters of the cgroup that held the run grew while it ran: `nr_throttled` of
 * `cpu.stat`, and `max`, `oom` and `oom_kill` of `memory.events`. A field is null when the file
 * or the counter was not readable, so absent never reads as zero.
 */
data class CgroupDelta(
    val throttled: Long? = null,
    val memoryMax: Long? = null,
    val oom: Long? = null,
    val oomKill: Long? = null,
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            throttled?.let { put("throttled", JsonInt(it)) }
            memoryMax?.let { put("memoryMax", JsonInt(it)) }
            oom?.let { put("oom", JsonInt(it)) }
            oomKill?.let { put("oomKill", JsonInt(it)) }
        },
    )

    companion object {
        fun fromJson(json: JsonValue): CgroupDelta {
            val o = json.obj()
            return CgroupDelta(
                o.fields["throttled"]?.long(),
                o.fields["memoryMax"]?.long(),
                o.fields["oom"]?.long(),
                o.fields["oomKill"]?.long(),
            )
        }
    }
}

/**
 * One recorded execution of a command. `userMs`, `systemMs`, `peakKb` and `cgroup` are null when
 * the host could not measure them; the JSON leaves the key out, so absent never reads as zero.
 */
data class Sample(
    val exitCode: Int,
    val wallMs: Long,
    val userMs: Long? = null,
    val systemMs: Long? = null,
    val peakKb: Long? = null,
    val cgroup: CgroupDelta? = null,
) {
    val passed: Boolean get() = exitCode == 0

    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("exitCode", JsonInt(exitCode.toLong()))
            put("wallMs", JsonInt(wallMs))
            userMs?.let { put("userMs", JsonInt(it)) }
            systemMs?.let { put("systemMs", JsonInt(it)) }
            peakKb?.let { put("peakKb", JsonInt(it)) }
            cgroup?.let { put("cgroup", it.toJson()) }
        },
    )

    companion object {
        fun fromJson(json: JsonValue): Sample {
            val o = json.obj()
            return Sample(
                o.require("exitCode").long().toInt(),
                o.require("wallMs").long(),
                o.fields["userMs"]?.long(),
                o.fields["systemMs"]?.long(),
                o.fields["peakKb"]?.long(),
                o.fields["cgroup"]?.let(CgroupDelta::fromJson),
            )
        }
    }
}
