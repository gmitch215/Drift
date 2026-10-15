package dev.gmitch215.drift

import dev.gmitch215.drift.fixtures.ViewportFixtures
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.Dimension
import dev.gmitch215.drift.rank.Ranker
import dev.gmitch215.drift.rank.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportDiagnosisTest {
    private val engines = listOf("chromium", "firefox", "webkit")
    private val viewports = listOf("390x844", "768x1024", "1280x800")
    private val supports = "browser.css.supports.interpolate-size"
    private val width = "browser.viewport.width"

    private fun capsule(engine: String, viewport: String): Capsule =
        Capsule.parse(ViewportFixtures.text("$engine-$viewport.json"))

    private fun expected(a: Pair<String, String>, b: Pair<String, String>): Set<String> = buildSet {
        if ((a.first == "chromium") != (b.first == "chromium")) add(supports)
        if (a.second != b.second) add(width)
    }

    private val captures = engines.flatMap { e -> viewports.map { e to it } }

    @Test
    fun everyEngineAndViewportPairNamesItsCause() {
        var pairs = 0
        for (a in captures) {
            for (b in captures) {
                if (a == b) continue
                val ranking = Ranker.rank(capsule(a.first, a.second), capsule(b.first, b.second))
                val known = ranking.candidates.filter { it.tier == Tier.KNOWN }.map { it.path }
                assertEquals(expected(a, b), known.toSet(), "$a against $b")
                pairs++
            }
        }
        assertEquals(72, pairs)
    }

    @Test
    fun theLayoutBreakIsTheIntrinsicSizeAnimation() {
        val chromium = capsule("chromium", "1280x800").attributes.associate { it.path to it.value }
        val firefox = capsule("firefox", "1280x800").attributes.associate { it.path to it.value }
        val webkit = capsule("webkit", "1280x800").attributes.associate { it.path to it.value }
        assertEquals("true", chromium.getValue(supports))
        assertEquals("false", firefox.getValue(supports))
        assertEquals("false", webkit.getValue(supports))
        assertEquals("45", chromium.getValue("browser.layout.panel.height"))
        assertEquals("0", firefox.getValue("browser.layout.panel.height"))
        val footer = "browser.layout.footer.y"
        assertEquals(firefox.getValue(footer), webkit.getValue(footer))
        assertEquals("221", chromium.getValue(footer))
    }

    @Test
    fun capturedKeysBelongToTheBrowserDimension() {
        val paths = capsule("chromium", "390x844").attributes.map { it.path }
        assertTrue(paths.size < 200)
        assertTrue(paths.all { Dimension.of(it) == Dimension.BROWSER }, paths.toString())
    }
}
