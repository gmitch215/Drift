package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.cloudflare.CLOUDFLARE_BEGIN
import dev.gmitch215.drift.cli.cloudflare.CLOUDFLARE_DRIVER
import dev.gmitch215.drift.cli.cloudflare.CLOUDFLARE_END
import dev.gmitch215.drift.cli.cloudflare.CloudflareOptions
import dev.gmitch215.drift.cli.cloudflare.CloudflareProbe
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.host.CommandResult
import dev.gmitch215.drift.host.FakeHost
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeCommandTest {
    private val transcript = AtlasFixtures.text("wasm.txt")
    private val up = "wrangler dev ready on http://127.0.0.1:8787\n"

    private fun options(wrangler: String? = null, deploy: Boolean = false, dry: Boolean = false) =
        CloudflareOptions("worker", wrangler, 0, 120, deploy, dry)

    private fun output(body: String = transcript) = "$up$CLOUDFLARE_BEGIN\n$body$CLOUDFLARE_END\n"

    private fun run(
        args: String,
        options: CloudflareOptions = options(),
        result: CommandResult? = CommandResult(0, output()),
        files: MemoryCaseFiles = MemoryCaseFiles(),
    ): Triple<CliktCommandTestResult, FakeHost, MemoryCaseFiles> {
        val host = FakeHost(
            canRun = result != null,
            commands = result?.let { mapOf(CloudflareProbe.argv(options) to it) }.orEmpty(),
        )
        return Triple(DriftCommand(host, files).test("probe cloudflare $args"), host, files)
    }

    @Test
    fun writesTheTranscriptUnderTheChosenTarget() {
        val (result, host, files) = run("--worker worker --target cloudflare-wasm --out out/")
        assertEquals(0, result.statusCode, result.stderr)
        val written = files.files.getValue("out/cloudflare-wasm.txt")
        assertTrue("# kotlin.target = cloudflare-wasm\n" in written)
        assertEquals(transcript.lines().size, written.lines().size)
        assertTrue("38 probes for cloudflare-wasm on kotlin" in result.stderr, result.stderr)
        assertTrue("wrangler dev ready on http://127.0.0.1:8787" in result.stderr)
        assertEquals(1, host.ran.size)
    }

    @Test
    fun withoutOutItPrintsTheTranscriptAndDefaultsTheTarget() {
        val (result, _, files) = run("--worker worker")
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("# kotlin.target = cloudflare\n" in result.stdout, result.stdout.take(80))
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun countsUnavailableProbes() {
        val refused = transcript.replace(
            "@@ kotlin.int.overflow\n",
            "@@ kotlin.int.overflow [UNAVAILABLE]\nrefused: trap\n@@ kotlin.int.radix-x\n",
        )
        val (result, _, _) = run("--worker worker", result = CommandResult(0, output(refused)))
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("1 unavailable" in result.stderr, result.stderr)
    }

    @Test
    fun passesTheWranglerCommandAndPortThrough() {
        val custom = CloudflareOptions("worker", "npx wrangler@4", 9000, 30, false, false)
        val (result, host, _) = run(
            "--worker worker --wrangler \"npx wrangler@4\" --port 9000 --timeout 30",
            custom,
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            listOf("dev", "worker", "npx wrangler@4", "9000", "30", "false"),
            host.ran[0].drop(3),
        )
    }

    @Test
    fun theDriverTravelsBase64EncodedInOneArgument() {
        val argv = CloudflareProbe.argv(options())
        assertEquals("node", argv[0])
        assertEquals("-e", argv[1])
        val script = argv[2].substringAfter("Buffer.from('").substringBefore("'")
        assertEquals(CLOUDFLARE_DRIVER, Base64.decode(script).decodeToString())
        assertTrue(CLOUDFLARE_BEGIN in CLOUDFLARE_DRIVER && CLOUDFLARE_END in CLOUDFLARE_DRIVER)
    }

    @Test
    fun deployPrintsThePriceEstimateAndTheDeployMode() {
        val deploy = options(deploy = true)
        val (result, host, _) = run("--worker worker --deploy", deploy)
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(CloudflareProbe.PRICING_URL in result.stderr, result.stderr)
        assertTrue("read ${CloudflareProbe.PRICING_READ}" in result.stderr)
        assertTrue("0.30 USD per million requests" in result.stderr)
        assertEquals(listOf("deploy", "worker", "", "0", "120", "false"), host.ran[0].drop(3))
    }

    @Test
    fun deployDryRunBundlesAndWritesNothing() {
        val dry = options(deploy = true, dry = true)
        val bundle = CommandResult(0, "Total Upload: 372.15 KiB\n--dry-run: exiting now.\n")
        val (result, host, files) = run("--worker worker --deploy --dry-run --out out", dry, bundle)
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("Total Upload: 372.15 KiB" in result.stderr, result.stderr)
        assertEquals(listOf("deploy", "worker", "", "0", "120", "true"), host.ran[0].drop(3))
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun refusesBadFlagsBeforeRunningAnything() {
        for (args in listOf(
            "--worker w --dry-run",
            "--worker w --target a/b",
            "--worker w --timeout 0",
        )) {
            val (result, host, _) = run(args, result = null)
            assertEquals(1, result.statusCode, args)
            assertTrue(host.ran.isEmpty(), args)
        }
    }

    @Test
    fun reportsAMissingNodeAFailedRunAndAnEmptyAnswer() {
        val (missing, _, _) = run("--worker worker", result = null)
        assertEquals(1, missing.statusCode)
        assertTrue("cannot start node" in missing.stderr, missing.stderr)

        val failed = CommandResult(1, "error: wrangler exited with 1\nno config\n")
        val (bad, _, _) = run("--worker worker", result = failed)
        assertEquals(1, bad.statusCode)
        assertTrue("failed (exit 1)" in bad.stderr && "no config" in bad.stderr, bad.stderr)

        val (empty, _, _) = run("--worker worker", result = CommandResult(0, up))
        assertEquals(1, empty.statusCode)
        assertTrue("no transcript" in empty.stderr, empty.stderr)

        val (broken, _, files) = run(
            "--worker worker --out out",
            result = CommandResult(0, output("# kotlin.version = 2.4.20\n")),
        )
        assertEquals(1, broken.statusCode)
        assertTrue("malformed transcript" in broken.stderr, broken.stderr)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun extractsTheTranscriptBetweenTheMarkers() {
        val wrapped = "x\n$CLOUDFLARE_BEGIN\na\nb\n$CLOUDFLARE_END\n"
        assertEquals("a\nb\n", CloudflareProbe.transcript(wrapped))
        assertNull(CloudflareProbe.transcript("x\n$CLOUDFLARE_END\n$CLOUDFLARE_BEGIN\n"))
        assertNull(CloudflareProbe.transcript("nothing"))
        assertEquals("ready", CloudflareProbe.log("ready\n$CLOUDFLARE_BEGIN\na\n$CLOUDFLARE_END\n"))
        val retargeted = CloudflareProbe.retarget("# kotlin.target = a\nx\n", "b")
        assertEquals("# kotlin.target = b\nx\n", retargeted)
    }
}
