package dev.gmitch215.drift.case

/** Why a tar archive was refused; never thrown. */
sealed interface TarProblem {
	fun message(): String
}

data class TarTruncated(val detail: String) : TarProblem {
	override fun message() = "archive is truncated: $detail"
}

data class TarBadHeader(val entry: Int, val detail: String) : TarProblem {
	override fun message() = "entry $entry has a bad header: $detail"
}

data class TarUnsafePath(val path: String) : TarProblem {
	override fun message() = "unsafe path in archive: ${path.take(80)}"
}

data class TarDuplicate(val path: String) : TarProblem {
	override fun message() = "archive holds $path twice"
}

data class TarOversized(val detail: String) : TarProblem {
	override fun message() = "archive is too large: $detail"
}

data class TarUnsupported(val path: String, val type: Char) : TarProblem {
	override fun message() = "$path is not a regular file (type ${type.code})"
}

data class TarBadText(val path: String) : TarProblem {
	override fun message() = "$path is not valid UTF-8"
}

object TarNotCanonical : TarProblem {
	override fun message() = "archive bytes differ from the canonical encoding of its entries"
}

sealed interface TarRead {
	data class Entries(val files: Map<String, String>) : TarRead

	data class Failed(val problem: TarProblem) : TarRead
}

/**
 * Plain USTAR tar of text files, written the same way on every target: sorted names, mode 0644,
 * uid and gid 0, mtime 0, no compression, two zero blocks at the end. [read] accepts only
 * archives that [write] would have produced, so reading and writing back gives the same bytes.
 */
object Tar {
	const val MAX_ENTRY = 16 * 1024 * 1024
	const val MAX_TOTAL = 64 * 1024 * 1024
	const val MAX_ENTRIES = 4096

	private const val BLOCK = 512
	private const val NAME = 100
	private const val PREFIX = 155

	/** Null when [path] can be stored: relative, no `..`, no empty parts, printable ASCII. */
	fun problem(path: String): TarProblem? {
		val parts = path.split('/')
		val bad = path.isEmpty() || path.startsWith("/") ||
			parts.any { it.isEmpty() || it == ".." } ||
			path.any { it.code < 0x20 || it.code > 0x7e || it == '\\' } ||
			split(path) == null
		return if (bad) TarUnsafePath(path) else null
	}

	private fun split(path: String): Pair<String, String>? {
		if (path.length <= NAME) return "" to path
		var at = path.lastIndexOf('/', path.length - 1)
		while (at > 0) {
			val prefix = path.substring(0, at)
			val name = path.substring(at + 1)
			if (prefix.length <= PREFIX && name.length <= NAME) return prefix to name
			at = path.lastIndexOf('/', at - 1)
		}
		return null
	}

	/** The archive of [files]; every path must pass [problem]. */
	fun write(files: Map<String, String>): ByteArray {
		val parts = ArrayList<ByteArray>()
		var size = 2 * BLOCK
		for (path in files.keys.sorted()) {
			require(problem(path) == null) { "unsafe path $path" }
			val data = files.getValue(path).encodeToByteArray()
			val padded = (data.size + BLOCK - 1) / BLOCK * BLOCK
			parts += header(path, data.size)
			parts += data.copyOf(padded)
			size += BLOCK + padded
		}
		val out = ByteArray(size)
		var at = 0
		for (part in parts) {
			part.copyInto(out, at)
			at += part.size
		}
		return out
	}

	private fun header(path: String, size: Int): ByteArray {
		val h = ByteArray(BLOCK)
		val (prefix, name) = split(path)!!
		put(h, 0, name)
		put(h, 100, "0000644")
		put(h, 108, "0000000")
		put(h, 116, "0000000")
		put(h, 124, size.toString(8).padStart(11, '0'))
		put(h, 136, "00000000000")
		for (i in 148 until 156) h[i] = ' '.code.toByte()
		h[156] = '0'.code.toByte()
		put(h, 257, "ustar")
		put(h, 263, "00", terminate = false)
		put(h, 329, "0000000")
		put(h, 337, "0000000")
		put(h, 345, prefix)
		val sum = h.sumOf { it.toInt() and 0xff }
		put(h, 148, sum.toString(8).padStart(6, '0'), terminate = false)
		h[154] = 0
		h[155] = ' '.code.toByte()
		return h
	}

	private fun put(h: ByteArray, at: Int, text: String, terminate: Boolean = true) {
		val bytes = text.encodeToByteArray()
		bytes.copyInto(h, at)
		if (terminate) h[at + bytes.size] = 0
	}

	/** Entries of [bytes], or the first reason it is not a canonical archive. */
	fun read(bytes: ByteArray): TarRead = try {
		parse(bytes)
	} catch (e: Exception) {
		TarRead.Failed(TarBadHeader(-1, e.message ?: "unreadable"))
	}

	private fun parse(bytes: ByteArray): TarRead {
		if (bytes.size > MAX_TOTAL + 2 * MAX_ENTRIES * BLOCK) {
			return failed(TarOversized("${bytes.size} bytes"))
		}
		val files = LinkedHashMap<String, String>()
		var at = 0
		var total = 0L
		var index = 0
		while (true) {
			if (at + BLOCK > bytes.size) return failed(TarTruncated("no end-of-archive marker"))
			if (isZero(bytes, at)) {
				if (at + 2 * BLOCK > bytes.size || !isZero(bytes, at + BLOCK)) {
					return failed(TarTruncated("the end-of-archive marker is incomplete"))
				}
				break
			}
			if (++index > MAX_ENTRIES) return failed(TarOversized("more than $MAX_ENTRIES entries"))
			val sum = octal(bytes, at + 148, 8) ?: return failed(TarBadHeader(index, "checksum"))
			var actual = 0
			for (i in 0 until BLOCK) {
				actual += if (i in 148 until 156) 32 else bytes[at + i].toInt() and 0xff
			}
			if (sum != actual.toLong()) return failed(TarBadHeader(index, "checksum mismatch"))
			if (text(bytes, at + 257, 6) != "ustar") return failed(TarBadHeader(index, "magic"))
			val size = octal(bytes, at + 124, 12) ?: return failed(TarBadHeader(index, "size"))
			val name = text(bytes, at, NAME)
			val prefix = text(bytes, at + 345, PREFIX)
			val path = if (prefix.isEmpty()) name else "$prefix/$name"
			problem(path)?.let { return failed(it) }
			val type = bytes[at + 156].toInt().toChar()
			if (type != '0') return failed(TarUnsupported(path, type))
			if (size > MAX_ENTRY) return failed(TarOversized("$path is $size bytes"))
			total += size
			if (total > MAX_TOTAL) return failed(TarOversized("more than $MAX_TOTAL bytes"))
			val start = at + BLOCK
			if (start + size > bytes.size) return failed(TarTruncated("$path ends early"))
			if (path in files) return failed(TarDuplicate(path))
			files[path] = try {
				bytes.decodeToString(start, start + size.toInt(), throwOnInvalidSequence = true)
			} catch (e: Exception) {
				return failed(TarBadText(path))
			}
			at = start + ((size + BLOCK - 1) / BLOCK * BLOCK).toInt()
			if (at > bytes.size) return failed(TarTruncated("$path padding ends early"))
		}
		if (!write(files).contentEquals(bytes)) return failed(TarNotCanonical)
		return TarRead.Entries(files)
	}

	private fun failed(problem: TarProblem) = TarRead.Failed(problem)

	private fun isZero(b: ByteArray, at: Int): Boolean {
		for (i in at until at + BLOCK) if (b[i].toInt() != 0) return false
		return true
	}

	private fun text(b: ByteArray, at: Int, length: Int): String {
		var end = at
		while (end < at + length && b[end].toInt() != 0) end++
		return b.decodeToString(at, end)
	}

	private fun octal(b: ByteArray, at: Int, length: Int): Long? {
		var value = 0L
		var digits = 0
		for (i in at until at + length) {
			val c = b[i].toInt()
			if (c == 0 || c == 32) {
				if (digits == 0) continue
				break
			}
			if (c < '0'.code || c > '7'.code) return null
			value = value * 8 + (c - '0'.code)
			digits++
			if (digits > 11) return null
		}
		return if (digits == 0) null else value
	}
}
