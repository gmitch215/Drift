@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.thermalState
import platform.darwin.sysctlbyname
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

private const val NAME_MAX_LENGTH = 256

private fun sysctlString(name: String): String? = memScoped {
	val size = alloc<ULongVar>()
	size.value = NAME_MAX_LENGTH.convert()
	val buf = allocArray<ByteVar>(NAME_MAX_LENGTH)
	if (sysctlbyname(name, buf, size.ptr, null, 0.convert()) != 0) null else buf.toKString()
}

private fun thermal(state: NSProcessInfoThermalState): String = when (state) {
	NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> "nominal"
	NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> "fair"
	NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> "serious"
	NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> "critical"
}

private object IosHost : Host {
	private val info = NSProcessInfo.processInfo

	override val platform = "ios"
	override val capabilities = setOf(Capability.FILES, Capability.ENV)
	override val os = "ios"
	override val arch = Platform.cpuArchitecture.name.lowercase()

	override fun env(): Map<String, String> =
		info.environment.entries.associate { it.key.toString() to it.value.toString() }

	override fun readText(path: String): String? =
		NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

	override fun run(argv: List<String>): CommandResult? = null

	override fun facts(): List<Attribute> {
		val model = env()["SIMULATOR_MODEL_IDENTIFIER"] ?: sysctlString("hw.machine")
		return listOfNotNull(
			fact("os.version", info.operatingSystemVersionString),
			fact("os.build", sysctlString("kern.osversion"), "sysctl"),
			fact("hw.model", model, "sysctl"),
			fact("hw.memory.total", info.physicalMemory),
			fact("hw.cpu.logical", info.processorCount),
			fact("hw.thermal-state", thermal(info.thermalState), kind = Stability.VOLATILE),
			fact("hw.power-save", info.lowPowerModeEnabled, kind = Stability.VOLATILE),
		)
	}
}

private fun fact(
	path: String,
	value: Any?,
	from: String = "foundation",
	kind: Stability = Stability.STATIC,
): Attribute? {
	val text = value?.toString()?.takeIf { it.isNotBlank() } ?: return null
	return Attribute(path, text, from, kind)
}

actual fun systemHost(): Host = IosHost
