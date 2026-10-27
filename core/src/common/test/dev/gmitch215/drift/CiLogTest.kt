package dev.gmitch215.drift

import dev.gmitch215.drift.triage.Side
import dev.gmitch215.drift.triage.Triage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CiLogTest {
    private fun log(vararg lines: String): String {
        val prefix = "build (ubuntu-22.04)\tRun tests\t2026-01-02T03:04:05.1234567Z"
        return lines.joinToString("\n") { "$prefix $it" }
    }

    private fun facts(text: String) = Triage.run(text, rules = emptyList()).facts
        .filter { it.side == Side.FAILING }.associate { it.key to it.value }

    @Test
    fun aRuntimeBannerIsTheVersionOfThatRuntime() {
        val text = listOf(
            "Python 3.9.18",
            "PHP 7.4.33 (cli) (built: Jan  1 2024 00:00:00) ( NTS )",
            "ruby 3.0.2p107 (2021-07-07 revision abc) [x86_64-linux]",
            "go version go1.20.4 linux/amd64",
            "rustc 1.70.0 (90c541806 2023-05-31)",
            "git version 2.31.1",
            "Docker version 24.0.5, build abc",
        ).joinToString("\n")
        val found = facts(text)
        assertEquals("3.9.18", found["tool.python.version"])
        assertEquals("7.4.33", found["tool.php.version"])
        assertEquals("3.0.2", found["tool.ruby.version"])
        assertEquals("1.20.4", found["tool.go.version"])
        assertEquals("1.70.0", found["tool.rust.version"])
        assertEquals("2.31.1", found["tool.git.version"])
        assertEquals("24.0.5", found["tool.docker.version"])
    }

    @Test
    fun javaBannersAndTheOutputOfAVersionCommandAreRead() {
        assertEquals("11.0.2", facts("java version \"11.0.2\" 2019-01-15 LTS")["tool.java.version"])
        assertEquals("17.0.8", facts("openjdk version \"17.0.8\" 2023-07-18")["tool.java.version"])
        val node = facts("\$ node --version\nv18.19.1\n\$ npm -v\n9.6.7\n")
        assertEquals("18.19.1", node["tool.node.version"])
        assertEquals("9.6.7", node["tool.npm.version"])
    }

    @Test
    fun aBannerNeedsTheToolNameAndAVersion() {
        assertNull(facts("The snake was 3.9 metres long.")["tool.python.version"])
        assertNull(facts("Python is fun")["tool.python.version"])
    }

    @Test
    fun aSetupInputIsTheRequestedToolchainAtTheConfidenceOfAStatedVersion() {
        val text = """
            with:
              python-version: 3.9
              node-version: 18
              java-version: '11'
              go-version: 1.20
              ruby-version: 3.0
              php-version: 7.4
              dotnet-version: 8.0.x
        """.trimIndent()
        val result = Triage.run(text, rules = emptyList())
        val found = result.facts.associate { it.key to it }
        assertEquals("3.9", found.getValue("tool.python.version").value)
        assertEquals("18", found.getValue("tool.node.version").value)
        assertEquals("11", found.getValue("tool.java.version").value)
        assertEquals("1.20", found.getValue("tool.go.version").value)
        assertEquals("3.0", found.getValue("tool.ruby.version").value)
        assertEquals("7.4", found.getValue("tool.php.version").value)
        assertEquals("8.0", found.getValue("tool.dotnet.version").value)
        assertTrue(found.getValue("tool.python.version").confidence.permille < 900)
    }

    @Test
    fun aCiLogIsReadLikeItsOutputAndTheBannerBeatsTheSetupInput() {
        val text = log(
            "  python-version: 3.9",
            "Successfully set up CPython (3.9.18)",
            "Python 3.9.18",
            "  File \"/work/app/check.py\", line 2",
            "    match x:",
            "SyntaxError: invalid syntax",
            "Error: Process completed with exit code 1.",
        )
        val found = facts(text)
        assertEquals("3.9.18", found["tool.python.version"])
        assertEquals("SyntaxError: invalid syntax", found["run:signature"])
        val cleaned = Triage.clean(text)
        assertTrue(cleaned.startsWith("```\n") && cleaned.endsWith("\n```"), cleaned)
        assertFalse("2026-01-02" in cleaned || "Run tests" in cleaned, cleaned)
    }

    @Test
    fun onlyATextThatIsMostlyLogLinesIsUnwrapped() {
        val one = "Notes\n\ntest\tstep\t2026-01-02T03:04:05.1234567Z Python 3.9.18\nand prose"
        assertEquals(one, Triage.clean(one))
        val prose = "It fails on 2026-01-02T03:04:05Z with Python 3.9.18.\n\nSecond paragraph."
        assertEquals(prose, Triage.clean(prose))
        val raw = (1..4).joinToString("\n") { "2026-01-02T03:04:0$it.5Z line $it" }
        assertTrue(Triage.clean(raw).startsWith("```\nline 1"))
    }

    @Test
    fun aSyntaxErrorWithAPythonFrameMakesPythonTheRuntime() {
        val frame = "  File \"/opt/lib/site-packages/mod.py\", line 2\n    match x:\n" +
            "SyntaxError: invalid syntax"
        val asks = Triage.run(frame, rules = emptyList()).ask.map { it.family }
        assertTrue("tool.python" in asks, "$asks")
        val bare = Triage.run("SyntaxError: invalid syntax", rules = emptyList())
            .ask.map { it.family }
        assertFalse("tool.python" in bare, "$bare")
    }
}
