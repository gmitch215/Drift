package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Stability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostFactsTest {
	private val sysctlOutput = """
		machdep.cpu.brand_string: Apple M2 Pro
		hw.model: Mac14,9
		hw.memsize: 17179869184
		hw.physicalcpu: 12
		hw.logicalcpu: 12
		hw.perflevel0.physicalcpu: 8
		hw.perflevel1.physicalcpu: 4
		hw.l1dcachesize: 65536
		hw.l1icachesize: 131072
		hw.l2cachesize: 4194304
		vm.swapusage: total = 23552.00M  used = 23181.62M  free = 370.38M  (encrypted)
		sysctl: unknown oid 'hw.cpufrequency_max'
	""".trimIndent()

	private val cpuinfo = """
		processor	: 0
		model name	: Intel(R) Xeon(R) Gold 6248 CPU @ 2.50GHz
		vendor_id	: GenuineIntel

		processor	: 1
		model name	: Intel(R) Xeon(R) Gold 6248 CPU @ 2.50GHz
		vendor_id	: GenuineIntel
	""".trimIndent()

	private val linux = FakeHost(
		files = mapOf(
			"/proc/cpuinfo" to cpuinfo,
			"/proc/meminfo" to
				"MemTotal: 8024556 kB\nMemAvailable: 7410164 kB\nSwapTotal: 1048572 kB\n",
			"/proc/uptime" to "63.79 489.71\n",
			"/proc/loadavg" to "0.27 0.10 0.03 5/250 10\n",
			"/sys/devices/system/cpu/cpu0/cache/index0/level" to "1\n",
			"/sys/devices/system/cpu/cpu0/cache/index0/type" to "Data\n",
			"/sys/devices/system/cpu/cpu0/cache/index0/size" to "32K\n",
			"/sys/devices/system/cpu/cpu0/cache/index1/level" to "3\n",
			"/sys/devices/system/cpu/cpu0/cache/index1/type" to "Unified\n",
			"/sys/devices/system/cpu/cpu0/cache/index1/size" to "27M\n",
			"/sys/class/thermal/thermal_zone0/temp" to "45500\n",
			"/sys/block/nvme0n1/size" to "1000215216\n",
			"/sys/block/nvme0n1/device/model" to "Samsung SSD\n",
			"/sys/block/nbd0/size" to "0\n",
			"/sys/class/net/eth0/mtu" to "1500\n",
			"/sys/class/net/eth0/speed" to "10000\n",
			"/sys/class/net/wlan0/speed" to "-1\n",
		),
		commands = mapOf(
			listOf("ls", "/sys/block") to CommandResult(0, "loop0\nnbd0\nnvme0n1\n"),
			listOf("ls", "/sys/class/net") to CommandResult(0, "bonding_masters\neth0\nwlan0\n"),
		),
	)

	private fun facts(host: Host) = linuxFacts(host).associate { it.path to it.value }

	@Test
	fun linuxReadsCpuMemoryDisksAndNetworks() {
		val f = facts(linux)
		assertEquals("Intel(R) Xeon(R) Gold 6248 CPU @ 2.50GHz", f["hw.cpu.model"])
		assertEquals("GenuineIntel", f["hw.cpu.vendor"])
		assertEquals("2", f["hw.cpu.logical"])
		assertEquals((8024556L * 1024).toString(), f["hw.memory.total"])
		assertEquals((7410164L * 1024).toString(), f["hw.memory.available"])
		assertEquals((1048572L * 1024).toString(), f["hw.swap.total"])
		assertEquals("63", f["hw.uptime.seconds"])
		assertEquals("250", f["hw.threads.count"])
		assertEquals("32768", f["hw.cpu.cache.l1d"])
		assertEquals((27L * 1024 * 1024).toString(), f["hw.cpu.cache.l3"])
		assertEquals("45", f["hw.sensors.thermal-zone0"])
		assertEquals((1000215216L * 512).toString(), f["hw.disk.nvme0n1.size"])
		assertEquals("Samsung SSD", f["hw.disk.nvme0n1.model"])
		assertNull(f["hw.disk.nbd0.size"])
		assertNull(f["hw.disk.loop0.size"])
		assertEquals("1500", f["hw.net.eth0.mtu"])
		assertEquals("10000", f["hw.net.eth0.speed-mbps"])
		assertNull(f["hw.net.wlan0.speed-mbps"])
		assertNull(f["hw.net.bonding_masters.mtu"])
	}

	@Test
	fun linuxMarksReadingsVolatileAndIdentityStatic() {
		val kinds = linuxFacts(linux).associate { it.path to it.stability }
		assertEquals(Stability.STATIC, kinds["hw.memory.total"])
		assertEquals(Stability.STATIC, kinds["hw.cpu.model"])
		for (path in listOf("hw.memory.available", "hw.uptime.seconds", "hw.threads.count")) {
			assertEquals(Stability.VOLATILE, kinds[path], path)
		}
		assertEquals(Stability.VOLATILE, kinds["hw.sensors.thermal-zone0"])
	}

	@Test
	fun linuxWithNothingReadableYieldsNothing() {
		assertEquals(emptyList(), linuxFacts(FakeHost()))
		assertEquals(emptyList(), linuxFacts(FakeHost(canRun = false)))
		val failing = FakeHost(
			commands = mapOf(listOf("ls", "/sys/block") to CommandResult(2, "ls: cannot access")),
		)
		assertEquals(emptyList(), linuxFacts(failing))
	}

	@Test
	fun linuxArmCpuWithoutModelNameStillReportsLogicalCount() {
		val cpuinfo = "processor\t: 0\nBogoMIPS\t: 48\n\nprocessor\t: 1\n"
		val arm = FakeHost(files = mapOf("/proc/cpuinfo" to cpuinfo))
		val f = facts(arm)
		assertEquals("2", f["hw.cpu.logical"])
		assertNull(f["hw.cpu.model"])
	}

	@Test
	fun macosParsesSysctlIncludingUnknownKeys() {
		val host = FakeHost(
			os = "macos",
			commands = mapOf(
				listOf(
					"sysctl", "machdep.cpu.brand_string", "hw.model", "hw.memsize",
					"hw.physicalcpu",
					"hw.logicalcpu", "hw.perflevel0.physicalcpu", "hw.perflevel1.physicalcpu",
					"hw.cpufrequency_max", "hw.l1dcachesize", "hw.l1icachesize", "hw.l2cachesize",
					"hw.l3cachesize", "vm.swapusage",
				) to CommandResult(1, sysctlOutput),
			),
		)
		val f = macosFacts(host).associate { it.path to it.value }
		assertEquals("Apple M2 Pro", f["hw.cpu.model"])
		assertEquals("Mac14,9", f["hw.model"])
		assertEquals("17179869184", f["hw.memory.total"])
		assertEquals("12", f["hw.cpu.physical"])
		assertEquals("8", f["hw.cpu.performance-cores"])
		assertEquals("4", f["hw.cpu.efficiency-cores"])
		assertEquals("65536", f["hw.cpu.cache.l1d"])
		assertEquals("4194304", f["hw.cpu.cache.l2"])
		assertEquals((23552L * 1024 * 1024).toString(), f["hw.swap.total"])
		assertNull(f["hw.cpu.max-freq-hz"])
		assertNull(f["hw.cpu.cache.l3"])
	}

	@Test
	fun macosWithoutSysctlYieldsNothing() {
		assertEquals(emptyList(), macosFacts(FakeHost(canRun = false)))
		assertEquals(emptyList(), macosFacts(FakeHost()))
	}

	@Test
	fun windowsReadsTheProcessorRegistryKey() {
		val key = "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"
		val output = "\r\n$key\r\n" +
			"    ~MHz    REG_DWORD    0xbb8\r\n" +
			"    VendorIdentifier    REG_SZ    GenuineIntel\r\n" +
			"    ProcessorNameString    REG_SZ    Intel(R) Core(TM) i7-8700 CPU @ 3.20GHz\r\n"
		val host = FakeHost(
			os = "windows",
			commands = mapOf(listOf("reg", "query", key) to CommandResult(0, output)),
		)
		val f = windowsFacts(host).associate { it.path to it.value }
		assertEquals("Intel(R) Core(TM) i7-8700 CPU @ 3.20GHz", f["hw.cpu.model"])
		assertEquals("GenuineIntel", f["hw.cpu.vendor"])
		assertEquals("3000", f["hw.cpu.max-freq-mhz"])
		assertEquals(emptyList(), windowsFacts(FakeHost(os = "windows")))
	}

	@Test
	fun hostsWithoutFactsReturnAnEmptyList() {
		assertTrue(FakeHost().facts().isEmpty())
		assertTrue(systemHost().facts().all { it.path.isNotBlank() && it.value.isNotBlank() })
	}
}
