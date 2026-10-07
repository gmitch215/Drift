package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.hash.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GOLDEN = "c756b4fbd6309680e02151b1ef2615bdcf4ad22094c043e0c5e4f335808862b6"

private val targets = listOf(
	"android", "ios", "jvm", "jvm-17", "jvm-21", "jvm-25", "linux", "linux-arm64", "macos",
	"wasm", "wasm-chromium", "wasm-firefox", "wasm-webkit",
)

class AtlasPredictTest {
	private val dataset = AtlasData.dataset
	private val start = PredictState(dataset)

	private fun view(state: PredictState) = assertNotNull(PredictView.of(state))

	private fun right(v: PredictView) = v.choices.indexOfFirst { v.target in it.targets }

	@Test
	fun orderHoldsOnlyDivergentProbesInSeededOrder() {
		val order = PredictView.order(dataset, "2.4.20", 1)
		assertEquals(23, order.size)
		assertTrue(order.all { "2.4.20" in it.divergentVersions })
		assertEquals(order.map { it.id }, PredictView.order(dataset, "2.4.20", 1).map { it.id })
		assertNotEquals(
			order.map { it.id },
			PredictView.order(dataset, "2.4.20", 2).map { it.id },
		)
		assertEquals(18, PredictView.order(dataset, "2.5.0-Beta1", 1).size)
	}

	@Test
	fun choicesAreTheDistinctOutputsOfOneVersion() {
		val v = view(start)
		val probe = assertNotNull(dataset.probe(v.probeId))
		assertEquals(probe.groups("2.4.20").size, v.choices.size)
		assertEquals(targets, v.choices.flatMap { it.targets }.sorted())
		assertEquals(v.choices.map { it.label }, v.choices.indices.map { ('A' + it).toString() })
		assertTrue(v.choices.all { it.lines.isNotEmpty() })
		assertEquals("What does ${v.target} print?", v.prompt)
		assertNull(v.reveal)
	}

	@Test
	fun correctWrongAndSkippedAreScored() {
		val v = view(start)
		val good = right(v)
		val correct = start.guess(good)
		assertEquals(Score(1, 0, 0), correct.score)
		assertEquals("correct", view(correct).reveal?.verdict)
		assertEquals(correct, correct.guess(good), "a second guess is ignored")
		val bad = v.choices.indices.first { it != good }
		val wrong = start.guess(bad)
		assertEquals(Score(0, 1, 0), wrong.score)
		assertEquals("wrong", view(wrong).reveal?.verdict)
		val skipped = start.guess(DONT_KNOW)
		assertEquals(Score(0, 0, 1), skipped.score)
		assertEquals("skipped", view(skipped).reveal?.verdict)
		assertEquals(start, start.guess(99))
	}

	@Test
	fun revealNamesEveryTargetAndTheExplanation() {
		val v = view(start.guess(DONT_KNOW))
		val reveal = assertNotNull(v.reveal)
		assertEquals(targets, reveal.results.map { it.substringBefore(':') })
		assertTrue(reveal.notes.any { it.startsWith("notes: ") })
		assertTrue(reveal.badge.isNotEmpty())
	}

	@Test
	fun nextKeepsTheScoreAndAdvances() {
		val done = start.guess(DONT_KNOW)
		val next = done.next()
		assertEquals(1, next.position)
		assertNull(next.guess)
		assertEquals(done.score, next.score)
		assertNotEquals(view(start).probeId, view(next).probeId)
		val other = next.withVersion("2.5.0-Beta1")
		assertEquals(0, other.position)
		assertEquals(done.score, other.score)
	}

	@Test
	fun selectionDoesNotDependOnTheTarget() {
		val ids = (0 until 40).map { view(start.copy(position = it)).probeId }
		assertEquals(23, ids.take(23).toSet().size)
		assertEquals(ids[0], ids[23])
	}

	@Test
	fun viewModelGolden() {
		val text = view(start.guess(DONT_KNOW)).encode()
		assertEquals(GOLDEN, Sha256.hex(text))
	}
}
