package dev.gmitch215.drift.cli

import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.fixtures.AtlasFixtures
import dev.gmitch215.drift.host.systemHost
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudflareProcessTest {
    private val dir = createTempDirectory("drift-cloudflare").toFile()
    private val worker = File(dir, "worker").apply { mkdirs() }
    private val dirPath = dir.path.replace('\\', '/')

    private val fake = File(dir, "fake-wrangler.mjs").apply {
        writeText(
            """
            import { createServer } from 'node:http';
            import { readFileSync } from 'node:fs';
            const args = process.argv.slice(2);
            if (args[0] === 'dev') {
              const body = readFileSync('$dirPath/transcript.txt', 'utf8');
              const port = Number(args[args.indexOf('--port') + 1]);
              createServer((req, res) => res.end(body)).listen(port, '127.0.0.1', () => {
                console.log('[wrangler:info] Ready on http://127.0.0.1:' + port);
              });
            } else if (args.includes('--dry-run')) {
              console.log('Total Upload: 1 KiB');
            } else {
              console.error('not logged in');
              process.exit(3);
            }
            """.trimIndent(),
        )
    }

    private val command = "node $dirPath/fake-wrangler.mjs"

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun drift(args: String, files: MemoryCaseFiles) =
        DriftCommand(systemHost(), files).test(args)

    @Test
    fun recordsFromAFakeWranglerOverLoopback() {
        File(dir, "transcript.txt").writeText(AtlasFixtures.text("wasm.txt"))
        val files = MemoryCaseFiles()
        val result = drift(
            "probe cloudflare --worker ${worker.path.replace('\\', '/')} --wrangler \"$command\" " +
                "--target cloudflare-wasm --out out --timeout 60",
            files,
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("wrangler dev ready on http://127.0.0.1:" in result.stderr, result.stderr)
        val written = files.files.getValue("out/cloudflare-wasm.txt")
        val expected = AtlasFixtures.text("wasm.txt")
            .replace("kotlin.target = wasm", "kotlin.target = cloudflare-wasm")
        assertEquals(expected, written)
    }

    @Test
    fun deployDryRunAndAFailedDeployReachTheFakeWrangler() {
        val files = MemoryCaseFiles()
        val base = "probe cloudflare --worker ${worker.path.replace('\\', '/')} " +
            "--wrangler \"$command\" --deploy --timeout 60"
        val dry = drift("$base --dry-run", files)
        assertEquals(0, dry.statusCode, dry.stderr)
        assertTrue("Total Upload: 1 KiB" in dry.stderr, dry.stderr)

        val real = drift(base, files)
        assertEquals(1, real.statusCode)
        assertTrue("wrangler deploy exited with 3" in real.stderr, real.stderr)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun aWranglerThatNeverStartsListeningIsReported() {
        File(dir, "silent.mjs").writeText("setInterval(() => {}, 1000);")
        val result = drift(
            "probe cloudflare --worker ${worker.path.replace('\\', '/')} " +
                "--wrangler \"node $dirPath/silent.mjs\" --timeout 2",
            MemoryCaseFiles(),
        )
        assertEquals(1, result.statusCode)
        assertTrue("not ready in time" in result.stderr, result.stderr)
    }

    @Test
    fun aMissingWorkerDirectoryIsReported() {
        val result = drift(
            "probe cloudflare --worker $dirPath/missing --wrangler \"$command\" --timeout 20",
            MemoryCaseFiles(),
        )
        assertEquals(1, result.statusCode)
        assertTrue("no such worker directory" in result.stderr, result.stderr)
    }
}
