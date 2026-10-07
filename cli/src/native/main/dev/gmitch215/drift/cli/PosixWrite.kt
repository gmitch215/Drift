@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.stdout

internal fun posixWriteText(path: String, text: String, mkdir: (String) -> Unit): Boolean =
	posixWriteBytes(path, text.encodeToByteArray(), mkdir)

internal fun posixWriteBytes(path: String, bytes: ByteArray, mkdir: (String) -> Unit): Boolean {
	val parts = path.split('/')
	for (i in 1 until parts.size) {
		val dir = parts.take(i).joinToString("/")
		if (dir.isNotEmpty()) mkdir(dir)
	}
	val file = fopen(path, "wb") ?: return false
	val written = if (bytes.isEmpty()) {
		0
	} else {
		bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() }
	}
	return (fclose(file) == 0) && written == bytes.size
}

actual fun readBinaryFile(path: String): ByteArray? {
	val file = fopen(path, "rb") ?: return null
	val chunks = ArrayList<ByteArray>()
	val buffer = ByteArray(65536)
	try {
		while (true) {
			val n = buffer.usePinned {
				fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file).toInt()
			}
			if (n <= 0) break
			chunks += buffer.copyOf(n)
		}
	} finally {
		fclose(file)
	}
	val all = ByteArray(chunks.sumOf { it.size })
	var at = 0
	for (c in chunks) {
		c.copyInto(all, at)
		at += c.size
	}
	return all
}

actual fun writeStdout(text: String) {
	val bytes = text.encodeToByteArray()
	if (bytes.isNotEmpty()) {
		bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), stdout) }
	}
	fflush(stdout)
}
