package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.host.Host
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.scan.atlas.Atlas
import dev.gmitch215.drift.scan.atlas.Comparison
import dev.gmitch215.drift.scan.atlas.Dataset
import dev.gmitch215.drift.scan.atlas.Transcripts

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

object Devices {
	fun label(platform: String): String = when (platform) {
		"jvm" -> "this JVM"
		"wasm" -> "this browser (wasm)"
		else -> "this device ($platform)"
	}

	fun of(label: String, transcript: String, dataset: Dataset): Device =
		Device(label, transcript, Comparison(dataset, Transcripts.read(label, transcript)))

	fun measure(dataset: Dataset, host: Host = systemHost()): Device =
		of(label(host.platform), Atlas.record(host), dataset)
}
