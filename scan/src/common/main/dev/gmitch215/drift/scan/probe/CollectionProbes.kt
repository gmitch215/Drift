package dev.gmitch215.drift.scan.probe

import kotlin.random.Random

internal val collectionProbes = listOf(
	Probe("collections.hash-order", Family.COLLECTIONS) {
		line("hashset-ints", hashSetOf(100, 3, 17, 42, 8, 1000, -5).toList())
		line("hashset-strings", hashSetOf("pear", "apple", "fig", "banana", "kiwi").toList())
		line("hashmap-keys", hashMapOf("z" to 1, "a" to 2, "m" to 3, "b" to 4).keys.toList())
		line("set-of", setOf(5, 1, 3).toList())
	},
	Probe("collections.sort-edge-cases", Family.COLLECTIONS) {
		line("doubles", listOf(3.0, Double.NaN, -0.0, 0.0, Double.NEGATIVE_INFINITY, 1.0).sorted())
		line("max-with-nan", listOf(1.0, Double.NaN).max())
		line(
			"stable",
			listOf("bb" to 1, "a" to 2, "cc" to 3, "d" to 4).sortedBy {
				it.first.length
			},
		)
		line("sorted-desc-strings", listOf("b", "a", "c").sortedDescending())
		line("min-of-neg-zero", minOf(0.0, -0.0))
	},
	Probe("collections.random-seeded", Family.COLLECTIONS) {
		val r = Random(42)
		line("ints", List(4) { r.nextInt() })
		line("long", r.nextLong())
		line("double", r.nextDouble().toRawBits().toULong().toString(16))
		line("bounded", List(4) { r.nextInt(10, 20) })
		line("seed-string", Random("drift".hashCode()).nextInt())
		line("shuffle", (1..8).toList().shuffled(Random(7)))
	},
)
