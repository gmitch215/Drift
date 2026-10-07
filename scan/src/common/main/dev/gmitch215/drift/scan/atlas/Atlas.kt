package dev.gmitch215.drift.scan.atlas

import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.probe.kotlin.AtlasTranscript
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog

object Atlas {
	/** The transcript of the Kotlin probe family on this target, in the committed format. */
	fun record(host: Host, target: String = host.platform): String =
		AtlasTranscript.render(target, ProbeRunner.run(host, KotlinCatalog.all.map { it.probe }))
}
