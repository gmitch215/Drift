package dev.gmitch215.drift

import dev.gmitch215.drift.delta.BisectApply
import dev.gmitch215.drift.delta.BisectPlan
import dev.gmitch215.drift.delta.BisectState
import dev.gmitch215.drift.delta.Ddmin
import dev.gmitch215.drift.delta.TrialOutcome
import dev.gmitch215.drift.delta.Verdict
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class BisectTest {
    private fun capsule(label: String, n: Int, red: Set<Int>) = Capsule(
        label,
        (0 until n).map { Attribute("tool.t$it.version", if (it in red) "2" else "1", "test") },
    )

    private fun plan(n: Int) =
        BisectPlan.of(capsule("g", n, emptySet()), capsule("r", n, (0 until n).toSet()))!!

    private fun drive(plan: BisectPlan, fails: (Set<String>) -> Boolean): BisectPlan {
        var now = plan
        while (true) {
            val state = now.state()
            if (state !is BisectState.Next) return now
            val paths = state.set.map { now.flips[it].path }.toSet()
            val outcome = if (fails(paths)) TrialOutcome.RED else TrialOutcome.GREEN
            now = assertIs<BisectApply.Updated>(now.apply(state.step, outcome)).plan
        }
    }

    @Test
    fun stepByStepMatchesDdminRunDirectly() {
        for (n in 1..9) {
            for (culprits in listOf(setOf(0), setOf(n - 1), setOf(0, n - 1), (0 until n).toSet())) {
                val start = plan(n)
                val names = culprits.map { "tool.t$it.version" }.toSet()
                val done = drive(start) { set -> names.all { it in set } }
                val direct = Ddmin.run(start.flips.indices.toList()) { subset ->
                    val set = subset.map { start.flips[it].path }.toSet()
                    if (names.all { it in set }) Verdict.FAIL else Verdict.PASS
                }
                val state = assertIs<BisectState.Done>(done.state())
                assertEquals(direct.minimal, state.minimal, "n=$n culprits=$culprits")
                assertEquals(direct.sequence.drop(1).size, done.trials.size, "n=$n $culprits")
                assertEquals(names, state.minimal.map { done.flips[it].path }.toSet())
            }
        }
    }

    @Test
    fun theFullSetIsNeverATestAndASingleFlipIsDoneAtOnce() {
        val one = plan(1)
        assertEquals(BisectState.Done(listOf(0)), one.state())
        val two = plan(2)
        val next = assertIs<BisectState.Next>(two.state())
        assertEquals(1, next.step)
        assertEquals(listOf(0), next.set)
    }

    @Test
    fun theSameInputsGiveTheSameBytesAndTheFileRoundTrips() {
        val a = plan(6).canonical()
        assertEquals(a, plan(6).canonical())
        val advanced = drive(plan(6)) { "tool.t3.version" in it }
        assertEquals(advanced, BisectPlan.parse(advanced.canonical()))
        assertEquals(advanced.canonical(), BisectPlan.parse(advanced.canonical()).canonical())
    }

    @Test
    fun applyChecksTheStepAndIsIdempotent() {
        val start = plan(4)
        val wrong = assertIs<BisectApply.Refused>(start.apply(2, TrialOutcome.RED))
        assertEquals("the pending step is 1, not 2", wrong.reason)
        val first = assertIs<BisectApply.Updated>(start.apply(1, TrialOutcome.GREEN)).plan
        assertSame(first, assertIs<BisectApply.Updated>(first.apply(1, TrialOutcome.GREEN)).plan)
        val clash = assertIs<BisectApply.Refused>(first.apply(1, TrialOutcome.RED))
        assertEquals("step 1 already holds green", clash.reason)
        val done = drive(start) { "tool.t0.version" in it }
        val late = assertIs<BisectApply.Refused>(done.apply(done.trials.size + 1, TrialOutcome.RED))
        assertEquals("the bisection is finished", late.reason)
    }

    @Test
    fun flipsListLikelierCulpritsFirstAndCarryBothValues() {
        val green = Capsule(
            "g",
            listOf(
                Attribute("env.A", "1", "t"),
                Attribute("tool.node.version", "20", "t"),
                Attribute("cgroup.memory.max", "100", "t"),
            ),
        )
        val red = Capsule(
            "r",
            listOf(
                Attribute("env.A", "2", "t"),
                Attribute("tool.node.version", "22", "t"),
                Attribute("env.B", "x", "t"),
            ),
        )
        val plan = BisectPlan.of(green, red)!!
        assertEquals(
            setOf("env.A", "tool.node.version", "cgroup.memory.max", "env.B"),
            plan.flips.map { it.path }.toSet(),
        )
        val memory = plan.flips.single { it.path == "cgroup.memory.max" }
        assertEquals("100", memory.green)
        assertNull(memory.red)
        assertEquals(green.hash(), plan.greenSha256)
        assertEquals(red.hash(), plan.redSha256)
    }

    @Test
    fun identicalCapsulesHaveNothingToBisect() {
        assertNull(BisectPlan.of(capsule("a", 3, emptySet()), capsule("b", 3, emptySet())))
        val volatileOnly = Capsule(
            "v",
            listOf(Attribute("env.PWD", "/a", "t", dev.gmitch215.drift.model.Stability.VOLATILE)),
        )
        val moved = volatileOnly.attributes[0].copy(value = "/b")
        val other = volatileOnly.copy(attributes = listOf(moved))
        assertNull(BisectPlan.of(volatileOnly, other))
    }

    @Test
    fun malformedPlansAreRejected() {
        val good = plan(3).canonical()
        assertFailsWith<JsonException> { BisectPlan.parse("{") }
        val newer = good.replace("\"schema\":1", "\"schema\":9")
        assertFailsWith<JsonException> { BisectPlan.parse(newer) }
        val badSet = good.replace(
            "\"trials\":[]",
            "\"trials\":[{\"outcome\":\"red\",\"set\":[7],\"step\":1}]",
        )
        assertFailsWith<JsonException> { BisectPlan.parse(badSet) }
        val badOutcome = good.replace(
            "\"trials\":[]",
            "\"trials\":[{\"outcome\":\"blue\",\"set\":[0],\"step\":1}]",
        )
        assertFailsWith<JsonException> { BisectPlan.parse(badOutcome) }
    }
}
