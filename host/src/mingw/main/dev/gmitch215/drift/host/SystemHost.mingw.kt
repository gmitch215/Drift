@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.posix._pclose
import platform.posix._popen
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GlobalMemoryStatusEx
import platform.windows.MEMORYSTATUSEX

private fun memory(): List<Attribute> = memScoped {
	val status = alloc<MEMORYSTATUSEX>()
	status.dwLength = sizeOf<MEMORYSTATUSEX>().convert()
	if (GlobalMemoryStatusEx(status.ptr) == 0) {
		emptyList()
	} else {
		listOf(
			Attribute("hw.memory.total", status.ullTotalPhys.toString(), "windows"),
			Attribute(
				"hw.memory.available",
				status.ullAvailPhys.toString(),
				"windows",
				Stability.VOLATILE,
			),
		)
	}
}

private fun environment(): Map<String, String>? {
	val block = GetEnvironmentStringsW() ?: return null
	try {
		val chars = ArrayList<Char>()
		var i = 0
		while (!(block[i] == 0.toUShort() && block[i + 1] == 0.toUShort())) {
			chars += block[i].toInt().toChar()
			i++
		}
		return parseEnvBlock(chars.toCharArray())
	} finally {
		FreeEnvironmentStringsW(block)
	}
}

actual fun systemHost(): Host = NativeHost(
	"mingw",
	{ _popen(it, "r") },
	{ _pclose(it) },
	::memory,
	::environment,
)
