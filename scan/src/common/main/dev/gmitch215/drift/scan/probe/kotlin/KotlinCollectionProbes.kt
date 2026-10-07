package dev.gmitch215.drift.scan.probe.kotlin

internal val kotlinCollectionProbes = listOf(
	KotlinProbe(
		id = "kotlin.collections.hash-order",
		question = "In which order do HashSet and HashMap iterate small fixed keys?",
		source = """
			line("ints", hashSetOf(100, 3, 17, 42, 8, 1000, -5).toList())
			line("strings", hashSetOf("pear", "apple", "fig", "banana", "kiwi").toList())
			line("chars", hashSetOf('z', 'a', 'm', 'B').toList())
			line("longs", hashSetOf(5000000000L, 3L, -1L, 100L).toList())
			line("doubles", hashSetOf(1.5, 0.1, -2.0, 100.0).toList())
			line("grown", (0 until 20).map { it * 7 }.toHashSet().toList())
			line("map-keys", hashMapOf("z" to 1, "a" to 2, "m" to 3, "b" to 4).keys.toList())
			line("map-string", hashMapOf(3 to "c", 1 to "a", 2 to "b"))
			line("linked-set", linkedSetOf(5, 1, 3).toList())
			line("set-of", setOf(5, 1, 3).toList())
			line("map-of", mapOf("z" to 1, "a" to 2).keys.toList())
			line("to-set", listOf(3, 1, 3, 2).toSet().toList())
			line("group-by", listOf("bb", "a", "cc", "d").groupBy { it.length })
			line("readd", linkedSetOf(1, 2, 3).also { it.remove(1) }.also { it.add(1) }.toList())
		""",
	) {
		line("ints", hashSetOf(100, 3, 17, 42, 8, 1000, -5).toList())
		line("strings", hashSetOf("pear", "apple", "fig", "banana", "kiwi").toList())
		line("chars", hashSetOf('z', 'a', 'm', 'B').toList())
		line("longs", hashSetOf(5000000000L, 3L, -1L, 100L).toList())
		line("doubles", hashSetOf(1.5, 0.1, -2.0, 100.0).toList())
		line("grown", (0 until 20).map { it * 7 }.toHashSet().toList())
		line("map-keys", hashMapOf("z" to 1, "a" to 2, "m" to 3, "b" to 4).keys.toList())
		line("map-string", hashMapOf(3 to "c", 1 to "a", 2 to "b"))
		line("linked-set", linkedSetOf(5, 1, 3).toList())
		line("set-of", setOf(5, 1, 3).toList())
		line("map-of", mapOf("z" to 1, "a" to 2).keys.toList())
		line("to-set", listOf(3, 1, 3, 2).toSet().toList())
		line("group-by", listOf("bb", "a", "cc", "d").groupBy { it.length })
		line("readd", linkedSetOf(1, 2, 3).also { it.remove(1) }.also { it.add(1) }.toList())
	},
	KotlinProbe(
		id = "kotlin.collections.sorting",
		question = "Is sorting stable, and what does it do with NaN and a broken comparator?",
		source = """
			val nan = Double.NaN
			line("doubles", listOf(3.0, nan, -0.0, 0.0, Double.NEGATIVE_INFINITY, 1.0).sorted())
			line("max-nan", listOf(1.0, nan).max())
			line("max-zeros", listOf(-0.0, 0.0).max())
			line("min-zeros", listOf(0.0, -0.0).min())
			val pairs = listOf("bb" to 1, "a" to 2, "cc" to 3, "d" to 4)
			line("stable-4", pairs.sortedBy { it.first.length })
			line("stable-40", (0 until 40).sortedBy { it % 3 } ==
				(0 until 40).filter { it % 3 == 0 } + (0 until 40).filter { it % 3 == 1 } +
				(0 until 40).filter { it % 3 == 2 })
			line("min-by-ties", listOf("bb", "aa", "cc").minBy { it.length })
			line("max-by-ties", listOf("bb", "aa", "cc").maxBy { it.length })
			line("nulls-first", listOf(3, null, 1).sortedBy { it })
			line("strings", listOf("b", "B", "a", "A").sorted())
			line("descending", listOf(2, 3, 1).sortedDescending())
			val items = listOf(3, 1, 2, 5, 4, 0, 7, 6)
			outcome("always-positive") { items.sortedWith { _, _ -> 1 } }
			outcome("always-negative") { items.sortedWith { _, _ -> -1 } }
			outcome("parity") { items.sortedWith { a, b -> if (a % 2 == 0) 1 else -1 } }
			outcome("broken-40") {
				(0 until 40).toList().sortedWith { a, b -> if (a == b) 0 else 1 }.take(6)
			}
		""",
	) {
		val nan = Double.NaN
		line("doubles", listOf(3.0, nan, -0.0, 0.0, Double.NEGATIVE_INFINITY, 1.0).sorted())
		line("max-nan", listOf(1.0, nan).max())
		line("max-zeros", listOf(-0.0, 0.0).max())
		line("min-zeros", listOf(0.0, -0.0).min())
		line(
			"stable-4",
			listOf("bb" to 1, "a" to 2, "cc" to 3, "d" to 4).sortedBy { it.first.length },
		)
		line(
			"stable-40",
			(0 until 40).sortedBy { it % 3 } ==
				(0 until 40).filter { it % 3 == 0 } + (0 until 40).filter { it % 3 == 1 } +
				(0 until 40).filter { it % 3 == 2 },
		)
		line("min-by-ties", listOf("bb", "aa", "cc").minBy { it.length })
		line("max-by-ties", listOf("bb", "aa", "cc").maxBy { it.length })
		line("nulls-first", listOf(3, null, 1).sortedBy { it })
		line("strings", listOf("b", "B", "a", "A").sorted())
		line("descending", listOf(2, 3, 1).sortedDescending())
		val items = listOf(3, 1, 2, 5, 4, 0, 7, 6)
		outcome("always-positive") { items.sortedWith { _, _ -> 1 } }
		outcome("always-negative") { items.sortedWith { _, _ -> -1 } }
		outcome("parity") { items.sortedWith { a, b -> if (a % 2 == 0) 1 else -1 } }
		outcome("broken-40") {
			(0 until 40).toList().sortedWith { a, b -> if (a == b) 0 else 1 }.take(6)
		}
	},
	KotlinProbe(
		id = "kotlin.collections.equality-hashing",
		question = "Which collections and arrays compare equal, and what do they hash to?",
		source = """
			val a = arrayOf(1)
			val b = arrayOf(1)
			line("array-eq", a.equals(b))
			line("array-content-eq", a contentEquals b)
			line("list-vs-arraylist", listOf(1, 2) == arrayListOf(1, 2))
			line("list-vs-set", listOf(1) == setOf(1))
			line("set-order", setOf(1, 2) == setOf(2, 1))
			line("map-order", mapOf(1 to "a", 2 to "b") == mapOf(2 to "b", 1 to "a"))
			line("list-of-arrays", listOf(a) == listOf(b))
			line("int-vs-long", listOf<Any>(1) == listOf<Any>(1L))
			line("pair", Pair(1, 2) == Pair(1, 2))
			line("range", (1..3) == (1..3))
			line("empty-ranges", (1..0) == (5..2))
			line("range-hash", (1..3).hashCode())
			line("list-null-hash", listOf(null, 1).hashCode())
			line("set-hash-order", setOf(1, 2, 3).hashCode() == setOf(3, 2, 1).hashCode())
			line("entry-hash", mapOf("k" to "v").entries.first().hashCode())
			line("empty-hashes", emptyList<Int>().hashCode().toString() + " " +
				emptySet<Int>().hashCode() + " " + emptyMap<Int, Int>().hashCode())
			line("string-builder", StringBuilder("a") == StringBuilder("a"))
			line("char-array-hash", charArrayOf('a', 'b').contentHashCode())
			line("double-list-hash", listOf(1.5, -0.0).hashCode())
		""",
	) {
		val a = arrayOf(1)
		val b = arrayOf(1)
		line("array-eq", a.equals(b))
		line("array-content-eq", a contentEquals b)
		line("list-vs-arraylist", listOf(1, 2) == arrayListOf(1, 2))
		line("list-vs-set", listOf(1) == setOf(1))
		line("set-order", setOf(1, 2) == setOf(2, 1))
		line("map-order", mapOf(1 to "a", 2 to "b") == mapOf(2 to "b", 1 to "a"))
		line("list-of-arrays", listOf(a) == listOf(b))
		line("int-vs-long", listOf<Any>(1) == listOf<Any>(1L))
		line("pair", Pair(1, 2) == Pair(1, 2))
		line("range", (1..3) == (1..3))
		line("empty-ranges", (1..0) == (5..2))
		line("range-hash", (1..3).hashCode())
		line("list-null-hash", listOf(null, 1).hashCode())
		line("set-hash-order", setOf(1, 2, 3).hashCode() == setOf(3, 2, 1).hashCode())
		line("entry-hash", mapOf("k" to "v").entries.first().hashCode())
		line(
			"empty-hashes",
			emptyList<Int>().hashCode().toString() + " " +
				emptySet<Int>().hashCode() + " " + emptyMap<Int, Int>().hashCode(),
		)
		line("string-builder", StringBuilder("a") == StringBuilder("a"))
		line("char-array-hash", charArrayOf('a', 'b').contentHashCode())
		line("double-list-hash", listOf(1.5, -0.0).hashCode())
	},
	KotlinProbe(
		id = "kotlin.collections.tostring-forms",
		question = "What do these values print through toString?",
		source = """
			line("list-doubles", listOf(1.0, 2.5f, 1e10))
			line("nested", mapOf("k" to listOf(1, 2), "e" to emptyList<Int>()))
			line("pair-null", Pair(1, null))
			line("triple", Triple('a', "b", 1L))
			line("array-content", arrayOf(1, null).contentToString())
			line("array-deep", arrayOf<Any>(arrayOf(1), intArrayOf(2)).contentDeepToString())
			line("char-array", charArrayOf('a', 'b').contentToString())
			line("range-step", 1..3 step 2)
			line("empty-range", 1..0)
			line("down-to", 3 downTo 1 step 2)
			line("char-range", 'a'..'c')
			line("long-range", 1L..3L)
			line("unit", Unit)
			line("null", null)
			line("result-success", Result.success(1))
			line("result-failure", Result.failure<Int>(IllegalStateException("x")))
			line("set-null", setOf(null))
			line("map-null-value", mapOf(1 to null))
			line("char-list", listOf('a', 'b'))
			line("string-list", listOf("a b", ""))
			line("regex", Regex("a+"))
			line("regex-option", Regex("a", RegexOption.IGNORE_CASE))
			line("enum", Classification.DOCUMENTED)
			line("uint", 5u)
			line("ulong-max", ULong.MAX_VALUE)
			line("ubyte", 200.toUByte())
			line("char-plus", 'a' + 1)
			line("char-minus", 'c' - 'a')
			line("kclass-int", Int::class)
			line("kclass-simple", String::class.simpleName)
			outcome("kclass-qualified") { String::class.qualifiedName }
			line("kclass-list", listOf(1)::class.simpleName)
		""",
	) {
		line("list-doubles", listOf(1.0, 2.5f, 1e10))
		line("nested", mapOf("k" to listOf(1, 2), "e" to emptyList<Int>()))
		line("pair-null", Pair(1, null))
		line("triple", Triple('a', "b", 1L))
		line("array-content", arrayOf(1, null).contentToString())
		line("array-deep", arrayOf<Any>(arrayOf(1), intArrayOf(2)).contentDeepToString())
		line("char-array", charArrayOf('a', 'b').contentToString())
		line("range-step", 1..3 step 2)
		line("empty-range", 1..0)
		line("down-to", 3 downTo 1 step 2)
		line("char-range", 'a'..'c')
		line("long-range", 1L..3L)
		line("unit", Unit)
		line("null", null)
		line("result-success", Result.success(1))
		line("result-failure", Result.failure<Int>(IllegalStateException("x")))
		line("set-null", setOf(null))
		line("map-null-value", mapOf(1 to null))
		line("char-list", listOf('a', 'b'))
		line("string-list", listOf("a b", ""))
		line("regex", Regex("a+"))
		line("regex-option", Regex("a", RegexOption.IGNORE_CASE))
		line("enum", Classification.DOCUMENTED)
		line("uint", 5u)
		line("ulong-max", ULong.MAX_VALUE)
		line("ubyte", 200.toUByte())
		line("char-plus", 'a' + 1)
		line("char-minus", 'c' - 'a')
		line("kclass-int", Int::class)
		line("kclass-simple", String::class.simpleName)
		outcome("kclass-qualified") { String::class.qualifiedName }
		line("kclass-list", listOf(1)::class.simpleName)
	},
	KotlinProbe(
		id = "kotlin.collections.iterator-modification",
		question = "Which structural changes during iteration throw, and which pass silently?",
		source = """
			outcome("list-add") {
				val l = mutableListOf(1, 2, 3)
				for (x in l) if (x == 1) l.add(4)
				l
			}
			outcome("list-remove-first") {
				val l = mutableListOf(1, 2, 3)
				for (x in l) if (x == 1) l.remove(1)
				l
			}
			outcome("list-remove-second") {
				val l = mutableListOf(1, 2, 3)
				for (x in l) if (x == 2) l.remove(2)
				l
			}
			outcome("list-remove-last") {
				val l = mutableListOf(1, 2, 3)
				for (x in l) if (x == 3) l.remove(3)
				l
			}
			outcome("map-put") {
				val m = mutableMapOf(1 to 1, 2 to 2)
				for (k in m.keys) if (k < 10) m[k + 10] = 0
				m
			}
			outcome("hashmap-remove-other") {
				val m = hashMapOf(1 to 1, 2 to 2, 3 to 3)
				for (k in m.keys) if (k == 1) m.remove(2)
				m
			}
			outcome("set-add") {
				val s = linkedSetOf(1, 2)
				for (x in s) if (x < 10) s.add(x + 10)
				s
			}
			outcome("sublist-after-add") {
				val l = mutableListOf(1, 2, 3)
				val s = l.subList(0, 2)
				l.add(4)
				s.toList()
			}
			outcome("sublist-size-after-add") {
				val l = mutableListOf(1, 2, 3)
				val s = l.subList(0, 2)
				l.add(4)
				s.size
			}
			outcome("next-on-empty") { emptyList<Int>().iterator().next() }
			outcome("remove-before-next") { mutableListOf(1).iterator().remove() }
			outcome("set-before-next") { mutableListOf(1).listIterator().set(2) }
			outcome("removeAt-range") { mutableListOf(1).removeAt(1) }
			outcome("subList-range") { listOf(1, 2).subList(1, 5) }
		""",
	) {
		outcome("list-add") {
			val l = mutableListOf(1, 2, 3)
			for (x in l) if (x == 1) l.add(4)
			l
		}
		outcome("list-remove-first") {
			val l = mutableListOf(1, 2, 3)
			for (x in l) if (x == 1) l.remove(1)
			l
		}
		outcome("list-remove-second") {
			val l = mutableListOf(1, 2, 3)
			for (x in l) if (x == 2) l.remove(2)
			l
		}
		outcome("list-remove-last") {
			val l = mutableListOf(1, 2, 3)
			for (x in l) if (x == 3) l.remove(3)
			l
		}
		outcome("map-put") {
			val m = mutableMapOf(1 to 1, 2 to 2)
			for (k in m.keys) if (k < 10) m[k + 10] = 0
			m
		}
		outcome("hashmap-remove-other") {
			val m = hashMapOf(1 to 1, 2 to 2, 3 to 3)
			for (k in m.keys) if (k == 1) m.remove(2)
			m
		}
		outcome("set-add") {
			val s = linkedSetOf(1, 2)
			for (x in s) if (x < 10) s.add(x + 10)
			s
		}
		outcome("sublist-after-add") {
			val l = mutableListOf(1, 2, 3)
			val s = l.subList(0, 2)
			l.add(4)
			s.toList()
		}
		outcome("sublist-size-after-add") {
			val l = mutableListOf(1, 2, 3)
			val s = l.subList(0, 2)
			l.add(4)
			s.size
		}
		outcome("next-on-empty") { emptyList<Int>().iterator().next() }
		outcome("remove-before-next") { mutableListOf(1).iterator().remove() }
		outcome("set-before-next") { mutableListOf(1).listIterator().set(2) }
		outcome("removeAt-range") { mutableListOf(1).removeAt(1) }
		outcome("subList-range") { listOf(1, 2).subList(1, 5) }
	},
)
