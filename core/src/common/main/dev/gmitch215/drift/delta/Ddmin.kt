package dev.gmitch215.drift.delta

enum class Verdict { FAIL, PASS, UNRESOLVED }

/**
 * [DdminResult.minimal] keeps the input order. [DdminResult.sequence] lists the index subsets
 * passed to the test, in call order, memoized repeats excluded; [calls] is its size.
 * [DdminResult.unresolved] counts tested subsets that came back UNRESOLVED, so a nonzero value
 * means those subsets were not proven PASS.
 */
data class DdminResult<T>(
	val minimal: List<T>,
	val sequence: List<List<Int>>,
	val unresolved: Int,
) {
	val calls: Int get() = sequence.size
}

object Ddmin {
	/**
	 * Zeller's ddmin: a subset of [items] that [test] reports FAIL for and from which no single
	 * item can be removed while keeping FAIL. The full input must FAIL; the empty set is assumed
	 * PASS and never tested. UNRESOLVED counts as not FAIL.
	 */
	fun <T> run(items: List<T>, test: (List<T>) -> Verdict): DdminResult<T> {
		val memo = HashMap<List<Int>, Verdict>()
		val sequence = mutableListOf<List<Int>>()
		fun fails(subset: List<Int>): Boolean = memo.getOrPut(subset) {
			sequence.add(subset)
			test(subset.map { items[it] })
		} == Verdict.FAIL

		var current = items.indices.toList()
		require(fails(current)) { "the full input must FAIL" }
		var n = 2
		while (current.size >= 2) {
			val size = current.size
			val chunks = (0 until n).map { current.subList(it * size / n, (it + 1) * size / n) }
			val subset = chunks.firstOrNull { fails(it) }
			if (subset != null) {
				current = subset
				n = 2
				continue
			}
			val complement = chunks.indices
				.map { i -> chunks.filterIndexed { j, _ -> j != i }.flatten() }
				.firstOrNull { fails(it) }
			if (complement != null) {
				current = complement
				n = maxOf(n - 1, 2)
			} else if (n < size) {
				n = minOf(2 * n, size)
			} else {
				break
			}
		}
		return DdminResult(
			current.map { items[it] },
			sequence,
			memo.values.count { it == Verdict.UNRESOLVED },
		)
	}
}
