package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.CaseFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.ContractCommand
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.contract.AssertionKind
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.fixtures.Fixtures
import dev.gmitch215.drift.hash.Sha256
import dev.gmitch215.drift.host.FakeHost
import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractCommandTest {
    private fun capsule(label: String, tz: String, cpus: String) = Capsule(
        label,
        listOf(
            Attribute("os.arch", "x86_64", "test"),
            Attribute("env.TZ", tz, "test"),
            Attribute("cpu.count", cpus, "test"),
        ),
    )

    private val files = MemoryCaseFiles()
    private val capsules = mapOf(
        "g1.json" to capsule("g1", "UTC", "4").canonical(),
        "g2.json" to capsule("g2", "UTC", "8").canonical(),
        "r1.json" to capsule("r1", "Asia/Tokyo", "4").canonical(),
        "twin.json" to capsule("twin", "UTC", "4").canonical(),
        "bare.json" to Capsule("bare", emptyList()).canonical(),
        "bad.json" to "{nope",
        "/abs/g1.json" to capsule("g1", "UTC", "4").canonical(),
        "/abs/g2.json" to capsule("g2", "UTC", "8").canonical(),
        "/abs/r1.json" to capsule("r1", "Asia/Tokyo", "4").canonical(),
    )
    private val host = FakeHost(canRun = false, files = capsules)

    private fun run(vararg argv: String, on: FakeHost = host) =
        DriftCommand(on, files).test(argv.joinToString(" "))

    private fun contractFile(vararg argv: String): String {
        val result = run("contract", "create", *argv)
        assertEquals(0, result.statusCode, result.stderr)
        return result.stdout.trimEnd('\n')
    }

    @Test
    fun createSeparatesGreensFromRedsAndPrintsTheContract() {
        val result = run("contract", "create", "--from", "g1.json", "g2.json", "--red", "r1.json")
        assertEquals(0, result.statusCode, result.stderr)
        val contract = Contract.parse(result.stdout)
        assertEquals(listOf("env.TZ"), contract.assertions.map { it.id })
        assertEquals(listOf("r1"), contract.assertions.single().breaks)
        assertEquals(result.stdout, contract.render() + "\n")
        assertTrue(
            result.stderr.startsWith(
                "contract: 1 assertion(s) separate 2 green capsule(s) from 1 red capsule(s)",
            ),
            result.stderr,
        )
    }

    @Test
    fun createWritesTheFileWithOut() {
        val result = run("contract", "create", "--from", "g1.json", "g2.json", "-o", "c.json")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.endsWith("wrote c.json\n"), result.stderr)
        val text = files.read("c.json")!!
        assertTrue(text.endsWith("}\n"))
        assertEquals(Contract.parse(text).render() + "\n", text)
    }

    @Test
    fun greensOnlyListsTheSharedValuesAsCoincidental() {
        val result = run("contract", "create", "--from", "g1.json", "g2.json")
        assertEquals(0, result.statusCode, result.stderr)
        val contract = Contract.parse(result.stdout)
        assertEquals(listOf("env.TZ", "os.arch"), contract.assertions.map { it.id })
        assertTrue(result.stdout.contains("\"role\":\"coincidental\""))
        assertTrue("coincidental until a red capsule discriminates them" in result.stderr)
    }

    @Test
    fun absolutePathsAreCapsulesNotOptions() {
        val result = run(
            "contract",
            "create",
            "--from",
            "/abs/g1.json",
            "/abs/g2.json",
            "--red",
            "/abs/r1.json",
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(listOf("env.TZ"), Contract.parse(result.stdout).assertions.map { it.id })
    }

    @Test
    fun aRedThatEqualsAGreenExitsWithItsOwnCode() {
        val result = run(
            "contract",
            "create",
            "--from",
            "g1.json",
            "g2.json",
            "--red",
            "r1.json",
            "twin.json",
        )
        assertEquals(3, result.statusCode)
        assertEquals("", result.stdout)
        assertTrue("no separating set: red capsule(s) twin contradict none of" in result.stderr)
    }

    @Test
    fun createNamesAMissingOrMalformedCapsule() {
        val missing = run("contract", "create", "--from", "nope.json")
        assertEquals(1, missing.statusCode)
        assertTrue("cannot read capsule file: nope.json" in missing.stderr, missing.stderr)
        val bad = run("contract", "create", "--from", "g1.json", "--red", "bad.json")
        assertEquals(1, bad.statusCode)
        assertTrue("invalid capsule file bad.json" in bad.stderr, bad.stderr)
    }

    @Test
    fun createNeedsFromAndReportsAWriteFailure() {
        assertEquals(1, run("contract", "create").statusCode)
        val refusing = object : CaseFiles {
            override fun read(path: String): String? = null

            override fun write(path: String, text: String) = false
        }
        val result = ContractCommand(host, refusing).test(
            "create --from g1.json g2.json -o /nowhere/c.json",
        )
        assertEquals(1, result.statusCode)
        assertTrue("cannot write contract file: /nowhere/c.json" in result.stderr, result.stderr)
    }

    private fun separating(): FakeHost {
        val text = contractFile("--from", "g1.json", "g2.json", "--red", "r1.json")
        return FakeHost(canRun = false, files = capsules + ("c.json" to text))
    }

    @Test
    fun verifyPassesAGreenAndFailsARed() {
        val on = separating()
        val green = run("contract", "verify", "c.json", "g1.json", on = on)
        assertEquals(0, green.statusCode, green.stderr)
        assertEquals(
            "HOLDS attribute env.TZ (discriminating): \"UTC\"\ncontract: 1 hold, 0 break, " +
                "0 unverifiable\n",
            green.stdout,
        )
        val red = run("contract", "verify", "c.json", "r1.json", on = on)
        assertEquals(1, red.statusCode)
        assertTrue(
            "BREAKS attribute env.TZ (discriminating): expected \"UTC\", found \"Asia/Tokyo\"" in
                red.stdout,
            red.stdout,
        )
        assertTrue("contract broken: attribute env.TZ" in red.stderr, red.stderr)
    }

    @Test
    fun verifyReportsAMissingValueAsUnverifiableWithoutFailing() {
        val result = run("contract", "verify", "c.json", "bare.json", on = separating())
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.startsWith("UNVERIFIABLE attribute env.TZ"), result.stdout)
        assertTrue("0 hold, 0 break, 1 unverifiable" in result.stdout)
    }

    @Test
    fun verifyJsonMatchesTheTextOutcome() {
        val on = separating()
        val ok = run("contract", "verify", "c.json", "g2.json", "--json", on = on)
        assertEquals(0, ok.statusCode, ok.stderr)
        assertTrue(ok.stdout.startsWith("{\"breaks\":0,\"format\":\"drift-contract-verify-1\""))
        assertTrue("\"ok\":true" in ok.stdout)
        val broken = run("contract", "verify", "c.json", "r1.json", "--json", on = on)
        assertEquals(1, broken.statusCode)
        assertTrue("\"ok\":false" in broken.stdout)
        assertTrue("\"status\":\"breaks\"" in broken.stdout)
    }

    @Test
    fun verifyNeedsExactlyOneEnvironment() {
        val on = separating()
        assertEquals(1, run("contract", "verify", "c.json", on = on).statusCode)
        val both = run("contract", "verify", "c.json", "g1.json", "--capture", on = on)
        assertEquals(1, both.statusCode)
        assertTrue("either a capsule file or --capture" in both.stderr, both.stderr)
    }

    @Test
    fun verifyNamesAMissingOrMalformedContract() {
        val on = separating()
        val missing = run("contract", "verify", "nope.json", "g1.json", on = on)
        assertEquals(1, missing.statusCode)
        assertTrue("cannot read contract file: nope.json" in missing.stderr, missing.stderr)
        val bad = run("contract", "verify", "bad.json", "g1.json", on = on)
        assertEquals(1, bad.statusCode)
        assertTrue("invalid contract file bad.json" in bad.stderr, bad.stderr)
    }

    @Test
    fun captureChecksThisEnvironment() {
        val here = FakeHost(canRun = false, env = mapOf("TZ" to "UTC"))
        val capsuleText = run("capture", "--label", "here", on = here).stdout
        val withProbes = run("capture", "--label", "here", "--probes", on = here).stdout
        val greens = FakeHost(
            canRun = false,
            files = mapOf("here.json" to capsuleText, "probes.json" to withProbes),
        )
        val text = run("contract", "create", "--from", "probes.json", on = greens).stdout
        val contract = Contract.parse(text)
        assertTrue(contract.assertions.any { it.kind == AssertionKind.PROBE })
        val files = mapOf("c.json" to text.trimEnd('\n'))
        val same = run(
            "contract",
            "verify",
            "c.json",
            "--capture",
            on = FakeHost(canRun = false, env = mapOf("TZ" to "UTC"), files = files),
        )
        assertEquals(0, same.statusCode, same.stdout + same.stderr)
        assertFalse("BREAKS" in same.stdout)
        assertFalse("UNVERIFIABLE" in same.stdout, same.stdout)
        val other = run(
            "contract",
            "verify",
            "c.json",
            "--capture",
            on = FakeHost(canRun = false, env = mapOf("TZ" to "Asia/Tokyo"), files = files),
        )
        assertEquals(1, other.statusCode)
        assertTrue("BREAKS attribute env.TZ" in other.stdout, other.stdout)
    }

    private fun dranglerHost(contract: String? = null) = FakeHost(
        canRun = false,
        files = (GREENS + REDS).associate { "$it.json" to Fixtures.text("$it.json") } +
            listOfNotNull(contract?.let { "c.json" to it }),
    )

    @Test
    fun dranglerContractBytesAreIdenticalOnEveryTarget() {
        val result = run(
            "contract",
            "create",
            "--from",
            *GREENS.map { "$it.json" }.toTypedArray(),
            "--red",
            *REDS.map { "$it.json" }.toTypedArray(),
            on = dranglerHost(),
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(DRANGLER_SHA, Sha256.hex(result.stdout))
        val contract = Contract.parse(result.stdout)
        assertEquals(GREENS.size, contract.greens.size)
        assertEquals(REDS.size, contract.reds.size)
        for (id in GREENS) {
            val ok = run(
                "contract",
                "verify",
                "c.json",
                "$id.json",
                on = dranglerHost(result.stdout),
            )
            assertEquals(0, ok.statusCode, ok.stdout)
        }
        for (id in REDS) {
            val broken = run(
                "contract",
                "verify",
                "c.json",
                "$id.json",
                on = dranglerHost(result.stdout),
            )
            assertEquals(1, broken.statusCode, broken.stdout)
        }
    }

    private companion object {
        val GREENS = listOf(
            "35585969640",
            "36232485230",
            "36311117223",
            "36412389380",
            "36702683742",
        )
        val REDS = listOf("36995781138", "37114464625", "37195797966")
        const val DRANGLER_SHA = "01e3954e9d78251503528308251d6dd706ff4edd88a1bc86e1007433497b8125"
    }
}
