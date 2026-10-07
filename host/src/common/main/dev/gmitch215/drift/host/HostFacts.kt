package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability

private const val KIB = 1024L
private const val MIB = KIB * 1024
private const val GIB = MIB * 1024
private const val SECTOR = 512L
private const val MAX_INDEX = 16
private val SKIPPED_DISKS = Regex("^(loop|ram|zram|dm-|sr|fd)\\d*")

private class Facts(val source: String) {
	val list = mutableListOf<Attribute>()

	fun put(path: String, value: Any?, kind: Stability = Stability.STATIC, from: String = source) {
		val text = value?.toString()?.trim()
		if (!text.isNullOrEmpty()) list += Attribute(path, text, from, kind)
	}
}

private fun colonFields(text: String?, separator: Char = ':'): Map<String, String> {
	val result = LinkedHashMap<String, String>()
	for (line in text.orEmpty().replace("\r", "").lines()) {
		val at = line.indexOf(separator)
		if (at > 0) result.getOrPut(line.substring(0, at).trim()) { line.substring(at + 1).trim() }
	}
	return result
}

private fun sizeBytes(text: String?): Long? {
	val t = text?.trim().orEmpty()
	val number = t.takeWhile { it.isDigit() }.toLongOrNull() ?: return null
	return when (t.drop(number.toString().length).trim().uppercase()) {
		"", "B" -> number
		"K", "KB" -> number * KIB
		"M", "MB" -> number * MIB
		"G", "GB" -> number * GIB
		else -> null
	}
}

private fun lines(host: Host, vararg argv: String): List<String> {
	val result = host.run(argv.toList()) ?: return emptyList()
	return if (result.exitCode ==
		0
	) {
			result.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
		} else {
			emptyList()
		}
}

internal fun linuxFacts(host: Host): List<Attribute> {
	val f = Facts("proc")
	val cpuinfo = host.readText("/proc/cpuinfo")
	val cpu = colonFields(cpuinfo)
	f.put("hw.cpu.model", cpu["model name"] ?: cpu["Hardware"] ?: cpu["Model"])
	f.put("hw.cpu.vendor", cpu["vendor_id"])
	val logical = cpuinfo?.lines()?.count { it.startsWith("processor") }
	f.put("hw.cpu.logical", logical?.takeIf { it > 0 })

	val mem = colonFields(host.readText("/proc/meminfo"))
	fun kib(key: String) = mem[key]?.let { sizeBytes(it.replace(" ", "")) }
	f.put("hw.memory.total", kib("MemTotal"))
	f.put("hw.memory.available", kib("MemAvailable"), Stability.VOLATILE)
	f.put("hw.swap.total", kib("SwapTotal"))

	host.readText("/proc/uptime")?.trim()?.substringBefore(' ')?.substringBefore('.')
		?.let { f.put("hw.uptime.seconds", it, Stability.VOLATILE) }
	host.readText("/proc/loadavg")?.trim()?.split(' ')?.getOrNull(3)?.substringAfter('/')
		?.let { f.put("hw.threads.count", it, Stability.VOLATILE) }

	for (i in 0 until MAX_INDEX) {
		val dir = "/sys/devices/system/cpu/cpu0/cache/index$i"
		val level = host.readText("$dir/level")?.trim() ?: break
		val type = when (host.readText("$dir/type")?.trim()) {
			"Data" -> "d"
			"Instruction" -> "i"
			else -> ""
		}
		f.put("hw.cpu.cache.l$level$type", sizeBytes(host.readText("$dir/size")), from = "sys")
	}
	for (i in 0 until MAX_INDEX) {
		val temp = host.readText("/sys/class/thermal/thermal_zone$i/temp")?.trim() ?: break
		val celsius = temp.toLongOrNull()?.div(1000)
		f.put("hw.sensors.thermal-zone$i", celsius, Stability.VOLATILE, "sys")
	}
	for (disk in lines(host, "ls", "/sys/block").filterNot { SKIPPED_DISKS.containsMatchIn(it) }) {
		val sectors = host.readText("/sys/block/$disk/size")?.trim()?.toLongOrNull()
		f.put("hw.disk.$disk.size", sectors?.takeIf { it > 0 }?.times(SECTOR), from = "sys")
		f.put("hw.disk.$disk.model", host.readText("/sys/block/$disk/device/model"), from = "sys")
	}
	for (nic in lines(host, "ls", "/sys/class/net")) {
		f.put("hw.net.$nic.mtu", host.readText("/sys/class/net/$nic/mtu"), from = "sys")
		val speed = host.readText("/sys/class/net/$nic/speed")?.trim()?.toLongOrNull()
		f.put("hw.net.$nic.speed-mbps", speed?.takeIf { it > 0 }, from = "sys")
	}
	return f.list
}

private val SYSCTL_KEYS = arrayOf(
	"machdep.cpu.brand_string",
	"hw.model",
	"hw.memsize",
	"hw.physicalcpu",
	"hw.logicalcpu",
	"hw.perflevel0.physicalcpu",
	"hw.perflevel1.physicalcpu",
	"hw.cpufrequency_max",
	"hw.l1dcachesize",
	"hw.l1icachesize",
	"hw.l2cachesize",
	"hw.l3cachesize",
	"vm.swapusage",
)

internal fun macosFacts(host: Host): List<Attribute> {
	val f = Facts("sysctl")
	val result = host.run(listOf("sysctl", *SYSCTL_KEYS)) ?: return f.list
	val sysctl = colonFields(result.output)
	f.put("hw.cpu.model", sysctl["machdep.cpu.brand_string"])
	f.put("hw.model", sysctl["hw.model"])
	f.put("hw.memory.total", sysctl["hw.memsize"])
	f.put("hw.cpu.physical", sysctl["hw.physicalcpu"])
	f.put("hw.cpu.logical", sysctl["hw.logicalcpu"])
	f.put("hw.cpu.performance-cores", sysctl["hw.perflevel0.physicalcpu"])
	f.put("hw.cpu.efficiency-cores", sysctl["hw.perflevel1.physicalcpu"])
	f.put("hw.cpu.max-freq-hz", sysctl["hw.cpufrequency_max"])
	for ((key, name) in mapOf("l1d" to "l1d", "l1i" to "l1i", "l2" to "l2", "l3" to "l3")) {
		f.put("hw.cpu.cache.$name", sysctl["hw.${key}cachesize"])
	}
	val swap = Regex("total = ([0-9.]+)([KMG])").find(sysctl["vm.swapusage"].orEmpty())
	if (swap != null) {
		val unit = mapOf("K" to KIB, "M" to MIB, "G" to GIB).getValue(swap.groupValues[2])
		val bytes = swap.groupValues[1].toDoubleOrNull()?.times(unit)?.toLong()
		f.put("hw.swap.total", bytes, Stability.VOLATILE)
	}
	return f.list
}

internal fun windowsFacts(host: Host): List<Attribute> {
	val f = Facts("reg")
	val key = "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"
	val out = lines(host, "reg", "query", key)
	fun value(name: String, type: String) = out.firstOrNull { it.startsWith(name) && type in it }
		?.substringAfter(type)?.trim()
	f.put("hw.cpu.model", value("ProcessorNameString", "REG_SZ"))
	f.put("hw.cpu.vendor", value("VendorIdentifier", "REG_SZ"))
	f.put("hw.cpu.max-freq-mhz", value("~MHz", "REG_DWORD")?.removePrefix("0x")?.toLongOrNull(16))
	return f.list
}
