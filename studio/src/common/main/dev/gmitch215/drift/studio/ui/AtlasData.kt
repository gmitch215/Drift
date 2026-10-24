package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.scan.atlas.Atlas
import dev.gmitch215.drift.scan.atlas.Comparison
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Transcripts
import dev.gmitch215.drift.scan.probe.ProbeRunner
import dev.gmitch215.drift.scan.probe.kotlin.AtlasTranscript
import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

object AtlasData {
    val dataset: Dataset by lazy {
        val names = AtlasFixtures.names.filter { it.endsWith(".txt") }.sorted()
        Dataset.build(names.map { Transcripts.read(it, AtlasFixtures.text(it)) })
    }
}

/**
 * One device transcript and its comparison with the dataset; [Device.transcript] feeds `atlas
 * build`.
 */
class Device(val label: String, val transcript: String, val comparison: Comparison) {
    val version: String get() = comparison.device.version
    val measured: Int get() = comparison.measured
}

/** Runs the Kotlin probes where Studio is; [progress] gets the probes done and the total. */
fun interface DeviceMeasure {
    suspend fun measure(dataset: Dataset, progress: (Int, Int) -> Unit): Device
}

object Devices {
    fun id(host: Host, runtime: String? = null): String = Atlas.deviceLabel(host, runtime)

    /** The engine, its major version and the OS family read from a browser's user agent. */
    fun browser(userAgent: String): String {
        val engine = listOf(
            "Firefox/" to "firefox",
            "Chrome/" to "chromium",
            "Version/" to "webkit",
        ).firstOrNull { (token, _) -> token in userAgent }
        val major = engine?.let { (token, _) ->
            userAgent.substringAfter(token).takeWhile { it.isDigit() }
        }.orEmpty()
        val os = listOf(
            "Android" to "android",
            "iPhone" to "ios",
            "iPad" to "ios",
            "Windows" to "windows",
            "Mac OS X" to "macos",
            "CrOS" to "chromeos",
            "Linux" to "linux",
        ).firstOrNull { (token, _) -> token in userAgent }?.second
        return listOfNotNull(engine?.second, major.ifEmpty { null }, os).joinToString("-")
    }

    fun of(label: String, transcript: String, dataset: Dataset): Device =
        Device(label, transcript, Comparison(dataset, Transcripts.read(label, transcript)))

    fun measurer(runtime: String? = null) = DeviceMeasure { dataset, progress ->
        measure(dataset, runtime = runtime, progress = progress)
    }

    /**
     * Each probe runs off the caller's thread where there is one, and [progress] is called on the
     * caller's thread after each; the yield lets a single-threaded caller draw in between.
     */
    suspend fun measure(
        dataset: Dataset,
        host: Host = systemHost(),
        runtime: String? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Device {
        val id = id(host, runtime)
        val probes = KotlinCatalog.all.map { it.probe }
        val results = probes.mapIndexed { i, probe ->
            val result = withContext(Dispatchers.Default) { ProbeRunner.run(host, probe) }
            progress(i + 1, probes.size)
            yield()
            result
        }
        return of(id, AtlasTranscript.render(id, results), dataset)
    }
}
