package dev.gmitch215.drift.studio.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioViewTest {
	private val state = sampleState()

	@Test
	fun funnelMatchesState() {
		val view = StudioView.of(state)
		val labels = listOf("facts", "changed", "relevant", "competing")
		assertEquals(labels, view.funnel.map { it.label })
		assertEquals(state.changed.changes.size, view.funnel[1].count)
		assertEquals(state.relevant.size, view.funnel[2].count)
		assertEquals(state.competing.size, view.funnel[3].count)
		assertEquals(view.funnel.maxOf { it.count }, view.maxCount)
		assertTrue(view.funnel[0].count >= view.funnel[1].count)
	}

	@Test
	fun emptyFunnelHasNoMax() {
		assertEquals(0, StudioView(emptyList(), "", emptyList(), 0, emptyList()).maxCount)
	}

	@Test
	fun detailLevelChangesTextNotState() {
		val summary = StudioView.of(state, DetailLevel.SUMMARY)
		val detail = StudioView.of(state, DetailLevel.DETAIL)
		val full = StudioView.of(state, DetailLevel.FULL)
		assertEquals(summary.funnel, detail.funnel)
		assertEquals(detail.funnel, full.funnel)
		assertEquals(summary.verdict, full.verdict)
		assertEquals(3, summary.candidates.size)
		assertEquals(state.relevant.size - 3, summary.hidden)
		assertEquals(state.relevant.size, detail.candidates.size)
		assertEquals(0, detail.hidden)
		assertTrue(summary.extras.isEmpty() && detail.extras.isEmpty())
		assertTrue(full.extras.isNotEmpty())
		assertEquals(
			state.relevant.map { it.path },
			detail.candidates.map { it.path },
		)
		assertTrue(detail.candidates.all { " -> " in it.text })
		assertTrue(full.candidates.all { it.text.endsWith("]") })
	}

	@Test
	fun bundleIsNotPresentedAsOneCause() {
		val view = StudioView.of(state)
		assertTrue(state.competing.size > 1)
		assertTrue("does not name one cause" in view.verdict, view.verdict)
		val paths = view.candidates.map { it.path }
		assertTrue("ci.runner.image.version" in paths)
		assertTrue("ci.provisioner.version" in paths)
		assertTrue("tool.node.version" in paths)
	}

	@Test
	fun fullListsUnrankedAndVolatileChanges() {
		val view = StudioView.of(samples()[1].state, DetailLevel.FULL)
		val extras = view.extras
		assertTrue(extras.any { it.startsWith("not ranked: tool.yarn.version") }, "$extras")
		assertTrue(view.extras.any { it.startsWith("volatile, ignored: ci.runner.region") })
	}

	@Test
	fun drillShowsTheChangeAndEvidence() {
		val lines = StudioView.drill(state, "deps.wrangler.version")
		assertTrue("change: changed  4.123.0 -> 4.146.0" in lines, "$lines")
		assertTrue("evidence: deps.wrangler.version" in lines)
		assertTrue(StudioView.drill(state, "no.such.path").isEmpty())
	}

	@Test
	fun samplesPairTheGreenRunWithEachRed() {
		val all = samples()
		assertEquals(RED_RUNS.map { "green vs red $it" }, all.map { it.name })
		assertTrue(all.all { it.green.label == "drangler-$GREEN_RUN" })
	}
}
