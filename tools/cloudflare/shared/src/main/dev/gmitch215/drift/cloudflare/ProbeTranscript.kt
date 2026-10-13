package dev.gmitch215.drift.cloudflare

import dev.gmitch215.drift.host.Capability
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.probe.kotlin.AtlasTranscript
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog

class WorkerHost(override val platform: String) : Host {
    override val capabilities: Set<Capability> = emptySet()
}

/** [refused] maps a probe id to why it cannot run here; it is recorded as unavailable, not run. */
fun probeTranscript(target: String, refused: Map<String, String> = emptyMap()): String {
    val host = WorkerHost(target)
    return AtlasTranscript.render(
        target,
        KotlinCatalog.all.map {
            val reason = refused[it.id]
            if (reason == null) {
                ProbeRunner.run(host, it.probe)
            } else {
                ProbeResult(it.id, ProbeStatus.UNAVAILABLE, "refused: $reason\n")
            }
        },
    )
}
