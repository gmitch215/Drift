package dev.gmitch215.drift.bench

import dev.gmitch215.drift.hash.Sha256

/** SplitMix64 on Long arithmetic, so every target draws the same sequence for a seed. */
class SplitMix64(seed: Long) {
	private var state = seed

	fun next(): Long {
		state += GOLDEN
		var z = state
		z = (z xor (z ushr 30)) * MIX1
		z = (z xor (z ushr 27)) * MIX2
		return z xor (z ushr 31)
	}

	/** Uniform to within 2^-40 for the small bounds used here (modulo of 63 random bits). */
	fun below(bound: Int): Int {
		require(bound > 0) { "bound must be positive" }
		return ((next() ushr 1) % bound).toInt()
	}

	fun <T> shuffled(items: List<T>): List<T> {
		val out = items.toMutableList()
		for (i in out.size - 1 downTo 1) {
			val j = below(i + 1)
			val t = out[i]
			out[i] = out[j]
			out[j] = t
		}
		return out
	}

	private companion object {
		val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
		val MIX1 = 0xBF58476D1CE4E5B9uL.toLong()
		val MIX2 = 0x94D049BB133111EBuL.toLong()
	}
}

object Hashing {
	/** First eight bytes of the SHA-256 of the text, big endian. */
	fun hash64(text: String): Long {
		val d = Sha256.digest(text.encodeToByteArray())
		var v = 0L
		for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
		return v
	}

	/** First four bytes of the SHA-256 of the text as a non-negative Long. */
	fun hash32(text: String): Long = hash64(text) ushr 32
}
