package dev.gmitch215.drift.case

/** Where case files live: the host reads them and each target supplies its own writer. */
interface CaseFiles {
	fun read(path: String): String?

	fun write(path: String, text: String): Boolean
}

class MemoryCaseFiles(val files: MutableMap<String, String> = mutableMapOf()) : CaseFiles {
	override fun read(path: String): String? = files[path]

	override fun write(path: String, text: String): Boolean {
		files[path] = text
		return true
	}
}
