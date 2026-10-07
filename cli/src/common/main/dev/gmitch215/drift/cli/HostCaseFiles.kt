package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.ArchiveFiles
import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.host.Host

/** Reads through the host and writes with the target's own file writer. */
class HostCaseFiles(private val host: Host) : CaseFiles {
	override fun read(path: String): String? = host.readText(path)

	override fun write(path: String, text: String): Boolean = writeTextFile(path, text)
}

/** Writes [text] as UTF-8 to [path], creating missing directories; false when it cannot. */
expect fun writeTextFile(path: String, text: String): Boolean

/** Reads and writes whole files as bytes, for the `.driftcase` archive. */
class HostArchiveFiles : ArchiveFiles {
	override fun read(path: String): ByteArray? = readBinaryFile(path)

	override fun write(path: String, bytes: ByteArray): Boolean = writeBinaryFile(path, bytes)
}

expect fun readBinaryFile(path: String): ByteArray?

/** Writes [bytes] to [path], creating missing directories; false when it cannot. */
expect fun writeBinaryFile(path: String, bytes: ByteArray): Boolean
