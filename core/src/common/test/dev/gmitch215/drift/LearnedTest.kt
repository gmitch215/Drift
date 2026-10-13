package dev.gmitch215.drift

import dev.gmitch215.drift.know.Learned
import dev.gmitch215.drift.know.Rule
import dev.gmitch215.drift.know.RuleException
import dev.gmitch215.drift.know.Rules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearnedTest {
    private fun named(id: String): Rule = demoRule(GCC_14, "id" to "\"$id\"")

    @Test
    fun theStoreFileIsNamedInsideItsDirectory() {
        assertEquals("team/learned.json", Learned.path("team"))
        assertEquals("team/learned.json", Learned.path("team/"))
    }

    @Test
    fun addingRulesKeepsThemSortedAndReadsBackEqual() {
        val first = Learned.add(null, named("learned-b"))
        val both = Learned.add(first, named("learned-a"))
        assertEquals(listOf("learned-a", "learned-b"), Learned.parse(both).map { it.id })
        assertEquals(named("learned-a"), Learned.parse(both).first())
        assertTrue(both.endsWith("}\n"))
    }

    @Test
    fun addingARuleTwiceChangesNothing() {
        val once = Learned.add(null, named("learned-a"))
        assertEquals(once, Learned.add(once, named("learned-a")))
    }

    @Test
    fun aShippedIdIsRefusedOnAddAndOnRead() {
        val shipped = Rules.ids.first()
        assertFailsWith<RuleException> { Learned.add(null, named(shipped)) }
        val text = Learned.add(null, named("learned-a")).replace("learned-a", shipped)
        assertFailsWith<RuleException> { Learned.parse(text) }
    }

    @Test
    fun aRuleThatFailsTheCompilerIsRefusedOnAdd() {
        val bad = named("learned-a").copy(mechanism = "")
        assertFailsWith<RuleException> { Learned.add(null, bad) }
    }

    @Test
    fun aBrokenStoreIsRefusedWithAReason() {
        assertFailsWith<RuleException> { Learned.parse("{nope") }
        assertFailsWith<RuleException> { Learned.parse("[]") }
        assertFailsWith<RuleException> { Learned.parse("{\"schema\":2,\"rules\":[]}") }
        assertFailsWith<RuleException> { Learned.parse("{\"schema\":1}") }
        val one = Learned.add(null, named("learned-a"))
        val twice = one.replace(
            "\"rules\":[",
            "\"rules\":[" + one.substringAfter("\"rules\":[")
                .substringBefore("],\"schema\"").plus(","),
        )
        assertFailsWith<RuleException> { Learned.parse(twice) }
    }
}
