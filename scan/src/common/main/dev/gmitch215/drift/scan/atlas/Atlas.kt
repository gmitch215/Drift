package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.probe.kotlin.AtlasTranscript
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog

object Atlas {
    private const val UNKNOWN = "unknown"

    /** The transcript of the Kotlin probe family on this target, in the committed format. */
    fun record(host: Host, target: String = host.platform): String =
        AtlasTranscript.render(target, ProbeRunner.run(host, KotlinCatalog.all.map { it.probe }))

    /**
     * The column label of a device: the platform, the [runtime] name and version when known and
     * the OS family. It carries nothing else about the machine.
     */
    fun deviceLabel(host: Host, runtime: String? = null): String {
        val os = host.os.takeIf { it != UNKNOWN && !host.platform.startsWith(it) }
        return listOfNotNull(host.platform, runtime?.takeIf { it.isNotBlank() }, os)
            .joinToString("-")
    }
}
