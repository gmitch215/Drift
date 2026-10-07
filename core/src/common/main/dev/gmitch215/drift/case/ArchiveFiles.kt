package dev.gmitch215.drift.case

/** Where archive files live: whole files as bytes, since a tar is not text. */
interface ArchiveFiles {
	fun read(path: String): ByteArray?

	fun write(path: String, bytes: ByteArray): Boolean
}

class MemoryArchiveFiles(val files: MutableMap<String, ByteArray> = mutableMapOf()) :
	ArchiveFiles {
	override fun read(path: String): ByteArray? = files[path]

	override fun write(path: String, bytes: ByteArray): Boolean {
		files[path] = bytes
		return true
	}
}
