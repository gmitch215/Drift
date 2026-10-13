package dev.gmitch215.drift

import dev.gmitch215.drift.contract.Assertion
import dev.gmitch215.drift.contract.AssertionKind
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.contract.ContractResult
import dev.gmitch215.drift.contract.Holds
import dev.gmitch215.drift.contract.Role
import dev.gmitch215.drift.fixtures.RunFixtures
import dev.gmitch215.drift.json.JsonException
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.model.ProbeResult
import dev.gmitch215.drift.model.ProbeStatus
import dev.gmitch215.drift.model.Stability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractTest {
    private fun capsule(
        label: String,
        vararg attrs: Pair<String, String>,
        probes: List<ProbeResult> = emptyList(),
    ) = Capsule(label, attrs.map { Attribute(it.first, it.second, "test") }, probes)

    private val greenA = capsule(
        "green-a",
        "os.arch" to "x86_64",
        "env.TZ" to "UTC",
        "tool.node.version" to "22.12.0",
        "cpu.count" to "4",
    )
    private val greenB = capsule(
        "green-b",
        "os.arch" to "x86_64",
        "env.TZ" to "UTC",
        "tool.node.version" to "22.12.0",
        "cpu.count" to "8",
    )
    private val redTz = capsule(
        "red-tz",
        "os.arch" to "x86_64",
        "env.TZ" to "Asia/Tokyo",
        "tool.node.version" to "22.12.0",
        "cpu.count" to "4",
    )
    private val redNode = capsule(
        "red-node",
        "os.arch" to "x86_64",
        "env.TZ" to "UTC",
        "tool.node.version" to "20.1.0",
        "cpu.count" to "4",
    )

    private fun created(result: ContractResult) = (result as ContractResult.Created)

    @Test
    fun aSingleRedIsSeparatedByTheOneValueItChanges() {
        val result = created(Contract.create(listOf(greenA, greenB), listOf(redTz)))
        val expected = Assertion(
            AssertionKind.ATTRIBUTE,
            "env.TZ",
            "UTC",
            Role.DISCRIMINATING,
            listOf("red-tz"),
        )
        assertEquals(listOf(expected), result.contract.assertions)
        assertEquals(3, result.candidates)
        assertEquals("separating", result.contract.mode)
        assertEquals(listOf("red-tz"), result.contract.reds.map { it.label })
    }

    @Test
    fun everyRedNeedsAnAssertionAndNoAssertionIsSpare() {
        val result = created(Contract.create(listOf(greenA, greenB), listOf(redTz, redNode)))
        assertEquals(
            listOf("env.TZ", "tool.node.version"),
            result.contract.assertions.map { it.id },
        )
        for (kept in result.contract.assertions) {
            val rest = result.contract.assertions - kept
            val verdicts = listOf(redTz, redNode).map { red ->
                rest.any { it.check(red).status == Holds.BREAKS }
            }
            assertTrue(false in verdicts, "${kept.id} is redundant")
        }
    }

    @Test
    fun aRedThatBreaksSeveralValuesKeepsOnlyOneOfThem() {
        val both = capsule(
            "red-both",
            "os.arch" to "aarch64",
            "env.TZ" to "Asia/Tokyo",
            "tool.node.version" to "20.1.0",
            "cpu.count" to "4",
        )
        val result = created(Contract.create(listOf(greenA, greenB), listOf(both)))
        assertEquals(1, result.contract.assertions.size)
        assertEquals(listOf("red-both"), result.contract.assertions.single().breaks)
    }

    @Test
    fun theResultDoesNotDependOnCapsuleOrder() {
        val one = Contract.create(listOf(greenA, greenB), listOf(redTz, redNode))
        val two = Contract.create(listOf(greenB, greenA), listOf(redNode, redTz))
        assertEquals(created(one).contract.render(), created(two).contract.render())
    }

    @Test
    fun greensAloneListEverySharedValueAsCoincidental() {
        val volatile = Attribute("env.SECONDS", "1", "test", Stability.VOLATILE)
        val probe = ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")
        val a = greenA.copy(attributes = greenA.attributes + volatile, probes = listOf(probe))
        val b = greenB.copy(
            attributes = greenB.attributes + volatile,
            probes = listOf(probe, ProbeResult("text.other", ProbeStatus.OK, "x\n")),
        )
        val result = created(Contract.create(listOf(a, b)))
        assertEquals("greens", result.contract.mode)
        assertEquals(0, result.calls)
        assertEquals(
            listOf("env.TZ", "os.arch", "tool.node.version", "text.sort"),
            result.contract.assertions.map { it.id },
        )
        assertTrue(result.contract.assertions.all { it.role == Role.COINCIDENTAL })
        assertTrue(result.contract.assertions.all { it.breaks.isEmpty() })
        assertEquals(AssertionKind.PROBE, result.contract.assertions.last().kind)
    }

    @Test
    fun aProbeThatDiffersBetweenGreensOrIsUnavailableIsNotShared() {
        val a = greenA.copy(probes = listOf(ProbeResult("p.diff", ProbeStatus.OK, "1\n")))
        val b = greenB.copy(
            probes = listOf(
                ProbeResult("p.diff", ProbeStatus.OK, "2\n"),
                ProbeResult("p.gone", ProbeStatus.UNAVAILABLE, "requires process\n"),
            ),
        )
        val ids = created(Contract.create(listOf(a, b))).contract.assertions.map { it.id }
        assertFalse("p.diff" in ids)
        assertFalse("p.gone" in ids)
    }

    @Test
    fun aProbeResultCanSeparateARedFromTheGreens() {
        val probe = ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")
        val green = greenA.copy(probes = listOf(probe))
        val red = greenA.copy(
            label = "red-probe",
            probes = listOf(ProbeResult("text.sort", ProbeStatus.OK, "b\na\n")),
        )
        val assertion = created(Contract.create(listOf(green), listOf(red)))
            .contract.assertions.single()
        assertEquals(AssertionKind.PROBE, assertion.kind)
        assertEquals("text.sort", assertion.id)
        assertEquals(probe.hash, assertion.expect)
    }

    @Test
    fun aRunIdentityVariableNeverSeparatesARedFromTheGreens() {
        val greens = listOf("a", "b").map {
            capsule("green-$it", "env.TZ" to "UTC", "env.GITHUB_SHA" to "aaa")
        }
        val red = capsule("red", "env.TZ" to "UTC", "env.GITHUB_SHA" to "bbb")
        val result = Contract.create(greens, listOf(red))
        assertEquals(listOf("red"), (result as ContractResult.Inseparable).reds)
        assertEquals(1, result.candidates)
    }

    @Test
    fun aRedThatEqualsAGreenHasNoSeparatingSet() {
        val twin = greenA.copy(label = "red-twin")
        val result = Contract.create(listOf(greenA, greenB), listOf(redTz, twin))
        val loose = result as ContractResult.Inseparable
        assertEquals(listOf("red-twin"), loose.reds)
        assertEquals(3, loose.candidates)
    }

    @Test
    fun aRedThatOnlyLacksAValueIsNotSeparated() {
        val bare = capsule("red-bare", "os.arch" to "x86_64")
        val result = Contract.create(listOf(greenA, greenB), listOf(bare))
        assertEquals(listOf("red-bare"), (result as ContractResult.Inseparable).reds)
    }

    @Test
    fun createNeedsAGreen() {
        assertFailsWith<IllegalArgumentException> { Contract.create(emptyList()) }
    }

    @Test
    fun verifyReportsHoldsBreaksAndUnverifiable() {
        val probe = ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")
        val contract = created(
            Contract.create(listOf(greenA.copy(probes = listOf(probe)))),
        ).contract
        val env = capsule(
            "env",
            "os.arch" to "aarch64",
            "env.TZ" to "UTC",
            probes = listOf(ProbeResult("text.sort", ProbeStatus.UNAVAILABLE, "no process\n")),
        )
        val report = contract.verify(env)
        val byId = report.results.associate { it.assertion.id to it.finding }
        assertEquals(Holds.HOLDS, byId.getValue("env.TZ").status)
        assertEquals(Holds.BREAKS, byId.getValue("os.arch").status)
        assertEquals("aarch64", byId.getValue("os.arch").found)
        assertEquals(Holds.UNVERIFIABLE, byId.getValue("tool.node.version").status)
        assertEquals(Holds.UNVERIFIABLE, byId.getValue("text.sort").status)
        assertEquals(null, byId.getValue("text.sort").found)
        assertEquals(5, report.results.size)
        assertFalse(report.ok)
        assertEquals("os.arch", report.first!!.assertion.id)
    }

    @Test
    fun aProbeWithADifferentTranscriptBreaks() {
        val probe = ProbeResult("text.sort", ProbeStatus.OK, "a\nb\n")
        val green = capsule("g", probes = listOf(probe))
        val contract = created(Contract.create(listOf(green))).contract
        val swapped = ProbeResult("text.sort", ProbeStatus.OK, "b\na\n")
        val other = capsule("e", probes = listOf(swapped))
        val finding = contract.verify(other).results.single().finding
        assertEquals(Holds.BREAKS, finding.status)
        assertEquals(other.probes.single().hash, finding.found)
    }

    @Test
    fun everyKnownGreenHoldsAndEveryKnownRedBreaks() {
        val greens = listOf(greenA, greenB)
        val reds = listOf(redTz, redNode)
        val contract = created(Contract.create(greens, reds)).contract
        assertTrue(greens.all { contract.verify(it).ok })
        assertTrue(reds.none { contract.verify(it).ok })
    }

    @Test
    fun aContractWithNoAssertionsHoldsEverywhere() {
        val report = Contract(emptyList(), emptyList(), emptyList()).verify(greenA)
        assertTrue(report.ok)
        assertEquals("contract: 0 hold, 0 break, 0 unverifiable", report.text())
    }

    @Test
    fun reportTextAndJsonAreFixed() {
        val contract = created(Contract.create(listOf(greenA, greenB), listOf(redTz))).contract
        val env = capsule("env", "env.TZ" to "Asia/Tokyo")
        val report = contract.verify(env)
        assertEquals(
            "BREAKS attribute env.TZ (discriminating): expected \"UTC\", found \"Asia/Tokyo\"\n" +
                "contract: 0 hold, 1 break, 0 unverifiable",
            report.text(),
        )
        assertEquals(
            "{\"breaks\":1,\"format\":\"drift-contract-verify-1\",\"holds\":0,\"ok\":false," +
                "\"results\":[{\"expect\":\"UTC\",\"found\":\"Asia/Tokyo\",\"id\":\"env.TZ\"," +
                "\"kind\":\"attribute\",\"role\":\"discriminating\",\"status\":\"breaks\"}]," +
                "\"schema\":1,\"unverifiable\":0}",
            report.json(),
        )
        val missing = contract.verify(capsule("none")).text()
        assertTrue(missing.startsWith("UNVERIFIABLE attribute env.TZ (discriminating): not in"))
    }

    @Test
    fun longValuesAreClippedInTextButNotInJson() {
        val long = "v".repeat(200)
        val contract = created(Contract.create(listOf(capsule("g", "a.long" to long)))).contract
        val report = contract.verify(capsule("e", "a.long" to long))
        assertTrue("..." in report.text())
        assertTrue(long in report.json())
    }

    @Test
    fun renderedBytesAreFixedAndRoundTrip() {
        val contract = created(Contract.create(listOf(greenA, greenB), listOf(redTz))).contract
        val text = contract.render()
        assertEquals(
            "{\n" +
                "  \"assertions\": [\n" +
                "    {\"breaks\":[\"red-tz\"],\"expect\":\"UTC\",\"id\":\"env.TZ\"," +
                "\"kind\":\"attribute\",\"role\":\"discriminating\"}\n" +
                "  ],\n" +
                "  \"format\": \"drift-contract-1\",\n" +
                "  \"greens\": [\n" +
                "    {\"hash\":\"${greenA.hash()}\",\"label\":\"green-a\"},\n" +
                "    {\"hash\":\"${greenB.hash()}\",\"label\":\"green-b\"}\n" +
                "  ],\n" +
                "  \"mode\": \"separating\",\n" +
                "  \"reds\": [\n" +
                "    {\"hash\":\"${redTz.hash()}\",\"label\":\"red-tz\"}\n" +
                "  ],\n" +
                "  \"schema\": 1\n" +
                "}",
            text,
        )
        assertEquals(contract, Contract.parse(text))
        assertEquals(text, Contract.parse(text).render())
        assertEquals(contract, Contract.fromJson(contract.toJson()))
    }

    @Test
    fun emptyListsRenderInline() {
        val text = Contract(emptyList(), emptyList(), emptyList()).render()
        assertTrue("\"assertions\": []," in text)
        assertEquals(Contract(emptyList(), emptyList(), emptyList()), Contract.parse(text))
    }

    @Test
    fun parseRejectsWhatItCannotRead() {
        val good = Contract(emptyList(), emptyList(), emptyList()).render()
        val schema2 = good.replace("\"schema\": 1", "\"schema\": 2")
        assertFailsWith<JsonException> { Contract.parse(schema2) }
        assertFailsWith<JsonException> { Contract.parse(good.replace("drift-contract-1", "x")) }
        assertFailsWith<JsonException> { Contract.parse("{nope") }
        val assertion = "{\"expect\":\"a\",\"id\":\"b\",\"kind\":\"KIND\",\"role\":\"ROLE\"}"
        val with = good.replace("\"assertions\": []", "\"assertions\": [$assertion]")
        assertFailsWith<JsonException> { Contract.parse(with) }
        assertFailsWith<JsonException> { Contract.parse(with.replace("KIND", "attribute")) }
        val valid = with.replace("KIND", "attribute").replace("ROLE", "coincidental")
        val fixed = Contract.parse(valid)
        assertEquals(Role.COINCIDENTAL, fixed.assertions.single().role)
    }

    private val greenIds =
        listOf("35585969640", "36232485230", "36311117223", "36412389380", "36702683742")
    private val redIds = listOf("36995781138", "37114464625", "37195797966")

    private fun drangler(ids: List<String>) =
        ids.map { Capsule.parse(RunFixtures.text("capsules/$it.json")) }

    @Test
    fun theDranglerCapsulesYieldAContractEveryGreenHoldsAndEveryRedBreaks() {
        val greens = drangler(greenIds)
        val reds = drangler(redIds)
        val result = created(Contract.create(greens, reds))
        assertTrue(result.contract.assertions.isNotEmpty())
        assertTrue(greens.all { result.contract.verify(it).ok })
        assertTrue(reds.none { result.contract.verify(it).ok })
        assertTrue(result.contract.assertions.size < result.candidates)
        val again = created(Contract.create(greens.reversed(), reds.reversed()))
        assertEquals(result.contract.render(), again.contract.render())
    }
}
