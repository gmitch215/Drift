package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import oshi.SystemInfo
import oshi.hardware.CentralProcessor.ProcessorCache

internal fun oshiFacts(): List<Attribute> {
	val out = mutableListOf<Attribute>()

	fun put(path: String, value: Any?, kind: Stability = Stability.STATIC) {
		val text = value?.toString()?.trim()
		if (!text.isNullOrEmpty()) out += Attribute(path, text, "oshi", kind)
	}

	fun section(block: () -> Unit) {
		runCatching(block)
	}

	val info = runCatching { SystemInfo() }.getOrNull() ?: return out
	val hardware = info.hardware
	section {
		val cpu = hardware.processor
		val id = cpu.processorIdentifier
		put("hw.cpu.model", id.name)
		put("hw.cpu.vendor", id.vendor)
		put("hw.cpu.microarchitecture", id.microarchitecture)
		put("hw.cpu.physical", cpu.physicalProcessorCount)
		put("hw.cpu.logical", cpu.logicalProcessorCount)
		put("hw.cpu.max-freq-hz", cpu.maxFreq.takeIf { it > 0 })
		val seen = mutableMapOf<String, Int>()
		for (cache in cpu.processorCaches.sortedByDescending { it.cacheSize }) {
			val name = cacheName(cache)
			val n = seen.merge(name, 1, Int::plus)!! - 1
			put(if (n == 0) name else "$name.$n", cache.cacheSize)
		}
	}
	section {
		val memory = hardware.memory
		put("hw.memory.total", memory.total)
		put("hw.memory.available", memory.available, Stability.VOLATILE)
		put("hw.swap.total", memory.virtualMemory.swapTotal)
	}
	section {
		val celsius = hardware.sensors.cpuTemperature.takeIf { it > 0 }
		put("hw.sensors.cpu-temperature", celsius, Stability.VOLATILE)
	}
	section {
		for (disk in hardware.diskStores) {
			put("hw.disk.${disk.name}.model", disk.model)
			put("hw.disk.${disk.name}.size", disk.size)
		}
	}
	section {
		for (nic in hardware.networkIFs) {
			put("hw.net.${nic.name}.mtu", nic.mtu)
			put("hw.net.${nic.name}.speed-bps", nic.speed.takeIf { it > 0 })
		}
	}
	section {
		val os = info.operatingSystem
		put("hw.processes.count", os.processCount, Stability.VOLATILE)
		put("hw.threads.count", os.threadCount, Stability.VOLATILE)
		put("hw.uptime.seconds", os.systemUptime, Stability.VOLATILE)
		put("os.build", os.versionInfo.buildNumber)
	}
	return out
}

private fun cacheName(cache: ProcessorCache): String {
	val kind = when (cache.type) {
		ProcessorCache.Type.DATA -> "d"
		ProcessorCache.Type.INSTRUCTION -> "i"
		else -> ""
	}
	return "hw.cpu.cache.l${cache.level}$kind"
}
