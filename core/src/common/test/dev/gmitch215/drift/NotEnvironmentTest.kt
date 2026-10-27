package dev.gmitch215.drift

import dev.gmitch215.drift.json.CanonicalJson
import dev.gmitch215.drift.triage.NotEnvSignal
import dev.gmitch215.drift.triage.NotEnvironment
import dev.gmitch215.drift.triage.Triage
import dev.gmitch215.drift.triage.TriageResult
import dev.gmitch215.drift.triage.TriageText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotEnvironmentTest {
    private fun run(text: String): TriageResult = Triage.run(text, rules = emptyList())

    private fun notEnv(text: String): NotEnvironment? = run(text).notEnvironment

    @Test
    fun aFrameInProjectCodeWithNoEnvironmentFactIsNotEnvironment() {
        val python = """
            Traceback (most recent call last):
              File "src/app/parser.py", line 42, in parse
                value = int(raw)
            ValueError: invalid literal
        """.trimIndent()
        val py = assertNotNull(notEnv(python))
        assertEquals(NotEnvSignal.FRAME, py.signal)
        assertEquals("src/app/parser.py:42", py.frame)
        assertEquals(2, py.line)
        assertEquals("File \"src/app/parser.py\", line 42, in parse", py.text)
        val js = assertNotNull(
            notEnv(
                "TypeError: x is not a function\n" +
                    "    at Object.<anonymous> (/work/app/src/index.js:3:8)",
            ),
        )
        assertEquals("/work/app/src/index.js:3", js.frame)
        val jvm = assertNotNull(
            notEnv(
                "IllegalStateException: bad state\n" +
                    "    at com.example.app.Store.load(Store.kt:17)",
            ),
        )
        assertEquals("Store.kt:17", jvm.frame)
        val rust = assertNotNull(notEnv("   0: app::run\n             at ./src/main.rs:5:5"))
        assertEquals("./src/main.rs:5", rust.frame)
        assertEquals("at test.js:3:8", notEnv("TypeError: x\n    at test.js:3:8")?.text)
    }

    @Test
    fun aFrameInVendorOrRuntimeCodeDoesNotCount() {
        assertNull(
            notEnv("TypeError: x\n    at f (/work/app/node_modules/left-pad/index.js:3:8)"),
        )
        assertNull(
            notEnv(
                "Traceback (most recent call last):\n" +
                    "  File \"/usr/lib/python3.12/json/decoder.py\", line 355, in decode",
            ),
        )
        assertNull(
            notEnv("  File \"/home/u/.venv/lib/python3.12/site-packages/requests/api.py\", line 9"),
        )
        assertNull(notEnv("Exception\n    at java.base/java.util.HashMap.get(HashMap.java:1)"))
        assertNull(notEnv("Exception\n    at kotlin.collections.CollectionsKt.first(Coll.kt:9)"))
        assertNull(notEnv("Failure\n    at org.junit.Assert.fail(Assert.java:89)"))
    }

    @Test
    fun aWrongValueIsAMismatchAndTheFirstMatchingLineIsKept() {
        val labelled = assertNotNull(notEnv("It runs.\nExpected: 3\nActual: 4"))
        assertEquals(NotEnvSignal.MISMATCH, labelled.signal)
        assertEquals(2, labelled.line)
        assertNull(labelled.frame)
        val gotWord = assertNotNull(notEnv("it expected 3 but got 4"))
        assertEquals(NotEnvSignal.MISMATCH, gotWord.signal)
        val assertion = assertNotNull(notEnv("AssertionError: 3 != 4"))
        assertEquals(NotEnvSignal.MISMATCH, assertion.signal)
        assertNull(notEnv("I expected the cache to be cleared on start."))
    }

    @Test
    fun aRegressionWordIsASignal() {
        val r = assertNotNull(notEnv("This is a regression in the 2.0 release."))
        assertEquals(NotEnvSignal.REGRESSION, r.signal)
        assertEquals(1, r.line)
    }

    @Test
    fun aFrameOutranksAMismatchAndAMismatchOutranksARegression() {
        val frame = "Expected: 3\nActual: 4\n    at com.example.Main.run(Main.kt:9)"
        assertEquals(NotEnvSignal.FRAME, assertNotNull(notEnv(frame)).signal)
        val mismatch = "This is a regression.\nExpected 3 but got 4"
        val got = assertNotNull(notEnv(mismatch))
        assertEquals(NotEnvSignal.MISMATCH, got.signal)
        assertEquals(2, got.line)
    }

    @Test
    fun anEnvironmentFactOnTheFailingSideKeepsTheReadingOut() {
        assertNull(notEnv("It fails on Node 22.4.0\n    at test.js:3:8"))
        assertNull(notEnv("It fails on Windows 11 with a regression.\nExpected 3 but got 4"))
    }

    @Test
    fun plainComplaintsAreNotASignal() {
        assertNull(notEnv("It is broken, please fix."))
        assertNull(notEnv(""))
    }

    @Test
    fun theReadingIsInTheJsonOnlyWhenItApplies() {
        val hit = CanonicalJson.encode(run("TypeError: x\n    at test.js:3:8").toJson())
        assertContains(hit, "\"notEnvironment\"")
        assertContains(hit, "\"signal\"")
        assertContains(hit, "\"frame\"")
        val miss = CanonicalJson.encode(run("It is broken, please fix.").toJson())
        assertFalse(miss.contains("notEnvironment"))
    }

    @Test
    fun theTextNamesTheReadingOnItsLine() {
        val out = TriageText.render(run("It runs.\nTypeError: x\n    at test.js:3:8"))
        assertContains(out, "Not environment (frame) at line 3: at test.js:3:8")
        assertFalse(TriageText.render(run("It is broken.")).contains("Not environment"))
    }

    @Test
    fun aCrashWithAnExceptionAndOnlyTheSystemNamedIsNotEnvironment() {
        val text = """
            Server crashed when a player joins

            Operating system: Windows
            Exception unhandled: access violation
            Stack trace (most recent call first):
            [0] in the plugin loader
        """.trimIndent()
        val reading = assertNotNull(notEnv(text))
        assertEquals(NotEnvSignal.CRASH, reading.signal)
        assertEquals(1, reading.line)
        assertEquals("Server crashed when a player joins", reading.text)
        assertNull(reading.frame)
    }

    @Test
    fun aCrashNeedsATraceAndNoOtherEnvironmentFact() {
        assertNull(notEnv("The app crashes when I press the button."))
        assertNull(notEnv("An exception is thrown in the parser."))
        assertNull(notEnv("It crashes with an exception on Node 20.1.0 and Ubuntu 22.04."))
        assertNull(notEnv("Crashed with an exception. We use docker 24.0.7."))
    }

    @Test
    fun aCrashDoesNotOverrideASignalThatRanksHigher() {
        val text = "It crashed. Exception below.\nTypeError: x\n    at test.js:3:8\n"
        assertEquals(NotEnvSignal.FRAME, assertNotNull(notEnv(text)).signal)
    }

    @Test
    fun theOperatingSystemOnlyOpensTheCrashSignal() {
        val text = "Windows 11. expected 3 but got 4 in the totals. It is a regression.\n"
        assertNull(notEnv(text))
        assertEquals(NotEnvSignal.MISMATCH, assertNotNull(notEnv("expected 3 but got 4")).signal)
    }

    @Test
    fun aTemplateHeadingIsNotAMismatch() {
        assertNull(notEnv("### Expected vs Actual Output\n\nIt does not work."))
        assertNull(notEnv("**Expected:**\n\nIt does not work."))
        assertEquals(
            NotEnvSignal.MISMATCH,
            assertNotNull(notEnv("Expected: 3\nActual: 4")).signal,
        )
    }

    private fun signal(text: String) = notEnv(text)?.signal

    @Test
    fun theProgramBeingWrongAboutItsOwnLogicIsNotEnvironment() {
        assertEquals(NotEnvSignal.LOGIC, signal("The linter reports false positives on match."))
        assertEquals(NotEnvSignal.LOGIC, signal("It incorrectly merges two links."))
        assertEquals(NotEnvSignal.LOGIC, signal("The loop is off by one at the end."))
        assertEquals(NotEnvSignal.LOGIC, signal("It silently ignores the header."))
        assertEquals(
            NotEnvSignal.NULL_DEREF,
            signal("TypeError: Cannot read properties of undefined (reading 'x')"),
        )
        assertEquals(NotEnvSignal.NULL_DEREF, signal("A NullPointerException in the parser."))
        assertEquals(NotEnvSignal.BOUNDS, signal("It reads an index out of range in the table."))
        assertEquals(NotEnvSignal.MEMORY, signal("There is a memory leak when it closes."))
        assertEquals(NotEnvSignal.NOT_SHOWN, signal("The tasks are not displayed in the bar."))
        assertEquals(NotEnvSignal.NOT_SHOWN, signal("The box doesn't render for tabs."))
        assertEquals(NotEnvSignal.NOT_SHOWN, signal("The text disappears after typing."))
    }

    @Test
    fun theReporterNamingTheDefectInTheProjectIsNotEnvironment() {
        assertEquals(NotEnvSignal.LOCATED, signal("Typo line 398 of upload.php"))
        assertEquals(NotEnvSignal.LOCATED, signal("See line 12 of src/app.rs for the cause."))
        assertEquals(NotEnvSignal.LOCATED, signal("The history link has a wrong target."))
        val link = "https://github.com/org/repo/blob/abc123/src/main.rs#L614-L641"
        assertEquals(NotEnvSignal.LOCATED, signal("This code: $link fails."))
        assertNull(signal("The typography is nice."))
        assertNull(signal("See line 12 of the report."))
        assertNull(signal("The link is fine, see https://github.com/org/repo/blob/main/README.md."))
    }

    @Test
    fun aToolVersionAndTheSystemDoNotHideAProgramFault() {
        val text = "Node 20.1.0 on Ubuntu 22.04. The parser reports false positives."
        assertEquals(NotEnvSignal.LOGIC, signal(text))
    }

    @Test
    fun anEnvironmentStoryKeepsTheProgramFaultQuiet() {
        val fault = "The parser reports false positives."
        assertNull(signal("$fault It works on my laptop."))
        assertNull(signal("$fault It worked before the update."))
        assertNull(signal("$fault Since upgrading it fails."))
        assertNull(signal("$fault It worked with node 18 and fails with node 20."))
        assertNull(signal("$fault It requires node >= 20."))
        assertNull(signal("$fault Too many open files."))
    }

    @Test
    fun aMatchedRuleOrADraftKeepsTheProgramFaultQuiet() {
        val text = "python 3.12 breaks: ModuleNotFoundError: No module named 'distutils'. " +
            "It incorrectly says so."
        val withRule = Triage.run(text)
        assertNull(withRule.notEnvironment)
    }
}
