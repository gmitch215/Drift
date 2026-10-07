@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fread
import platform.posix.fwrite

/** File reads and writes shared by every native target; [open] picks the path encoding. */
internal abstract class NativeInstall(private val open: (String, String) -> CPointer<FILE>?) :
	InstallSystem {
	protected abstract fun markExecutable(path: String): Boolean

	override fun read(path: String): ByteArray? {
		val file = open(path, "rb") ?: return null
		val chunks = ArrayList<ByteArray>()
		val failed: Boolean
		try {
			memScoped {
				val buffer = allocArray<ByteVar>(CHUNK)
				while (true) {
					val n = fread(buffer, 1.convert(), CHUNK.convert(), file).toInt()
					if (n <= 0) break
					chunks += buffer.readBytes(n)
				}
			}
			failed = ferror(file) != 0
		} finally {
			fclose(file)
		}
		if (failed) return null
		val all = ByteArray(chunks.sumOf { it.size })
		var at = 0
		for (chunk in chunks) {
			chunk.copyInto(all, at)
			at += chunk.size
		}
		return all
	}

	override fun write(path: String, bytes: ByteArray, executable: Boolean): Boolean {
		val file = open(path, "wb") ?: return false
		val written = if (bytes.isEmpty()) {
			0
		} else {
			bytes.usePinned {
				fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
			}
		}
		if (fclose(file) != 0 || written != bytes.size) return false
		return !executable || markExecutable(path)
	}

	override fun remove(path: String): Boolean = platform.posix.remove(path) == 0

	private companion object {
		const val CHUNK = 65536
	}
}
