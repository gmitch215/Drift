package dev.gmitch215.drift.cli

import java.io.File

actual fun writeTextFile(path: String, text: String): Boolean = runCatching {
	val file = File(path)
	file.parentFile?.mkdirs()
	file.writeText(text)
}.isSuccess

actual fun writeBinaryFile(path: String, bytes: ByteArray): Boolean = runCatching {
	val file = File(path)
	file.parentFile?.mkdirs()
	file.writeBytes(bytes)
}.isSuccess

actual fun readBinaryFile(path: String): ByteArray? =
	runCatching { File(path).readBytes() }.getOrNull()

actual fun writeStdout(text: String) {
	val bytes = text.encodeToByteArray()
	System.out.write(bytes, 0, bytes.size)
	System.out.flush()
}
