package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.core.BaseCliktCommand
import dev.gmitch215.drift.case.MemoryArchiveFiles
import dev.gmitch215.drift.case.MemoryCaseFiles
import dev.gmitch215.drift.cli.command.DriftCommand
import dev.gmitch215.drift.cli.command.Examples
import dev.gmitch215.drift.cli.install.FakeInstallSystem
import dev.gmitch215.drift.cli.serve.FakeServeSystem
import dev.gmitch215.drift.help.Glossary
import dev.gmitch215.drift.host.FakeHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelpCommandTest {
    private val host = FakeHost(platform = "fake", canRun = false)

    private fun drift() = DriftCommand(
        host,
        MemoryCaseFiles(),
        MemoryArchiveFiles(),
        FakeInstallSystem(),
        FakeServeSystem(),
    )

    private fun paths(command: BaseCliktCommand<*>, prefix: String = ""): List<String> =
        command.registeredSubcommands().flatMap {
            val path = prefix + it.commandName
            listOf(path) + paths(it, "$path ")
        }

    @Test
    fun firstScreenNamesTheStartCommandsAndTheTerms() {
        val result = drift().test("--help")
        assertEquals(0, result.statusCode, result.stderr)
        val out = result.stdout
        for (start in listOf("drift ci:", "drift doctor:", "drift diagnose:")) {
            assertTrue(start in out, out)
        }
        assertTrue("drift help TERM" in out, out)
        assertTrue("https://drift.gmitch215.dev" in out, out)
        assertTrue(out.indexOf("Start with one of these") < out.indexOf("Commands:"), out)
    }

    @Test
    fun everyCommandSitsInOneTaskGroup() {
        val groups = drift().test("--help").stdout.lines().map { it.trim() }
            .dropWhile { it != "Commands by task:" }.drop(1).takeWhile { it.isNotBlank() }
        assertEquals(5, groups.size, groups.toString())
        val listed = groups.flatMap { it.substringAfter(": ").split(", ") }
        val names = drift().registeredSubcommands().map { it.commandName }
        assertEquals(names.sorted(), listed.sorted())
    }

    @Test
    fun helpPrintsEachTerm() {
        for (term in Glossary.terms) {
            for (spelled in listOf(term.name, term.name.lowercase(), term.name.uppercase())) {
                val result = drift().test("help $spelled")
                assertEquals(0, result.statusCode, result.stderr)
                assertEquals("${term.name}: ${term.meaning}\n", result.stdout)
            }
        }
    }

    @Test
    fun helpWithoutATermPrintsAllOfThem() {
        val result = drift().test("help")
        assertEquals(0, result.statusCode, result.stderr)
        val expected = Glossary.terms.map { "${it.name}: ${it.meaning}" }
        assertEquals(expected, result.stdout.lines().dropLast(1))
    }

    @Test
    fun anUnknownTermListsTheKnownOnes() {
        val result = drift().test("help flux")
        assertEquals(1, result.statusCode)
        assertEquals("", result.stdout)
        assertTrue("unknown term \"flux\"" in result.stderr, result.stderr)
        for (term in Glossary.terms) {
            assertTrue(term.name.lowercase() in result.stderr, result.stderr)
        }
    }

    @Test
    fun theGlossaryHasTheSevenReadmeTerms() {
        assertEquals(
            listOf("Capsule", "Diagnose", "Solve", "Certificate", "Verify", "Atlas", "Probe"),
            Glossary.terms.map { it.name },
        )
        assertEquals(null, Glossary.find("flux"))
    }

    @Test
    fun completionPrintsAScriptForEachShell() {
        for (shell in listOf("bash", "zsh", "fish")) {
            val result = drift().test("completion $shell")
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("drift" in result.stdout && "atlas" in result.stdout, result.stdout)
        }
        assertEquals(1, drift().test("completion powershell").statusCode)
    }

    @Test
    fun everyCommandHasExamplesAndNoneIsStale() {
        val real = paths(drift())
        assertEquals(real.sorted(), Examples.byCommand.keys.sorted())
        for (path in real) {
            val help = drift().test("$path --help")
            assertEquals(0, help.statusCode, help.stderr)
            assertTrue("Examples:" in help.stdout, "$path\n${help.stdout}")
            val flat = help.stdout.replace(Regex("\\s+"), " ")
            for (line in Examples.byCommand.getValue(path)) {
                assertTrue("drift $line" in flat, "$path: $line\n${help.stdout}")
            }
        }
    }

    @Test
    fun everyExampleParsesWithTheRealOptions() {
        for ((path, lines) in Examples.byCommand) {
            for (line in lines) {
                val result = drift().test(line)
                assertFalse("Usage:" in result.stderr, "$path: drift $line\n${result.stderr}")
            }
        }
    }
}
