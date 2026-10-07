@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

internal class NativeHost(
	override val platform: String,
	private val openPipe: (String) -> CPointer<FILE>?,
	private val closePipe: (CPointer<FILE>) -> Int,
	private val extraFacts: () -> List<Attribute> = { emptyList() },
	private val nativeEnv: () -> Map<String, String>? = { null },
) : Host {
	private val windows = Platform.osFamily == OsFamily.WINDOWS

	override val capabilities = Capability.entries.toSet()
	override val os = Platform.osFamily.name.lowercase()
	override val arch = Platform.cpuArchitecture.name.lowercase()

	override fun env(): Map<String, String> {
		nativeEnv()?.let { return it }
		val result =
			run(if (windows) listOf("cmd", "/c", "set") else listOf("env")) ?: return emptyMap()
		return result.output.lines().filter { '=' in it }.associate {
			it.substringBefore('=') to it.substringAfter('=').trimEnd('\r')
		}
	}

	override fun facts(): List<Attribute> = when (platform) {
		"linux" -> linuxFacts(this)
		"macos" -> macosFacts(this)
		"mingw" -> windowsFacts(this) + extraFacts()
		else -> emptyList()
	}

	override fun readText(path: String): String? {
		val file = fopen(path, "rb") ?: return null
		val bytes = try {
			readAll { buf, size -> fread(buf, 1.convert(), size.convert(), file).toInt() }
		} finally {
			fclose(file)
		}
		return bytes.decodeToString()
	}

	override fun run(argv: List<String>): CommandResult? {
		val line = argv.joinToString(" ") { quote(it) } + " 2>&1"
		val pipe = openPipe(line) ?: return null
		val bytes = try {
			readAll { buf, size -> fread(buf, 1.convert(), size.convert(), pipe).toInt() }
		} catch (e: Throwable) {
			closePipe(pipe)
			throw e
		}
		val status = closePipe(pipe)
		val code = when {
			windows -> status
			status and 0x7f == 0 -> (status shr 8) and 0xff
			else -> 128 + (status and 0x7f)
		}
		return CommandResult(code, bytes.decodeToString())
	}

	private fun quote(arg: String): String = if (windows) {
		"\"" + arg.replace("\"", "\\\"") + "\""
	} else {
		"'" + arg.replace("'", "'\\''") +
			"'"
	}

	private fun readAll(read: (CPointer<ByteVar>, Int) -> Int): ByteArray {
		val out = ArrayList<ByteArray>()
		var total = 0
		memScoped {
			val buf = allocArray<ByteVar>(CHUNK)
			while (true) {
				val n = read(buf, CHUNK)
				if (n <= 0) break
				out += buf.readBytes(n)
				total += n
			}
		}
		val all = ByteArray(total)
		var at = 0
		for (part in out) {
			part.copyInto(all, at)
			at += part.size
		}
		return all
	}

	private companion object {
		const val CHUNK = 8192
	}
}
