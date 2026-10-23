package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.gmitch215.drift.case.CaseFile
import dev.gmitch215.drift.lab.Archive
import dev.gmitch215.drift.lab.ArchiveOpen
import dev.gmitch215.drift.lab.SolveCertificate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CaseScreenTest {
    private val dir = "../fixtures/lab/cases"
    private val path = "$dir/locale-tz-date-2.driftcase"

    private fun story(name: String): CaseStory =
        assertNotNull(assertNotNull(loadCase("$dir/$name.driftcase")).story)

    private fun tampered(): CaseView {
        val bytes = File(path).readBytes()
        val text = bytes.decodeToString()
        val at = text.indexOf("\"status\":\"fail\"")
        assertTrue(at >= 0)
        val changed = bytes.copyOf()
        "\"status\":\"pass\"".encodeToByteArray().copyInto(changed, at)
        return CaseView.of("tampered.driftcase", changed)
    }

    @Test
    fun aLaunchArgumentOpensTheCaseOnItsOwnTab() = runComposeUiTest {
        val case = assertNotNull(caseFromArgs(arrayOf("--ignored", path)))
        assertTrue(case.lines.any { it.text.startsWith("PASS manifest") })
        assertNotNull(case.verdict)
        assertEquals("locale-tz-date-2.driftcase", case.name)
        setContent { StudioApp(case = case, dark = false) }
        onNodeWithText("Case").assertIsSelected()
        onNodeWithText(CASE_ABOUT).assertExists()
        onNodeWithTag("case-status").assertTextEquals("This file is intact: every check passed")
        onNodeWithText("PASS manifest", substring = true).assertDoesNotExist()
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithText("PASS manifest", substring = true).assertExists()
    }

    @Test
    fun aConfirmedCaseNamesTheCauseHowSureDriftIsAndWhatToTry() = runComposeUiTest {
        val case = assertNotNull(caseFromArgs(arrayOf(path)))
        setContent { StudioApp(case = case, dark = false) }
        onNodeWithTag("case-headline")
            .assertTextEquals("Drift found the cause: the time zone (TZ).")
        onNodeWithTag("case-sureness").assertTextEquals(
            "Confirmed by experiment. With the failing values put in, 5 of 5 runs failed " +
                "(0 of 5 before). With the passing values put back into the failing run, " +
                "0 of 5 runs failed (5 of 5 before).",
        )
        onNodeWithTag("case-next")
            .assertTextEquals("Set the time zone (TZ) back to UTC and run the failing job again.")
        onNodeWithText("env.TZ  (named as the cause)").assertExists()
    }

    @Test
    fun everyKindOfVerdictHasItsOwnPlainWording() {
        val confirmed = story("locale-tz-date-2")
        assertEquals(listOf("env.TZ"), confirmed.differences.filter { it.cause }.map { it.path })
        assertTrue(confirmed.experiments.any { it.contains("green with env.TZ set to red values") })
        assertTrue(confirmed.notes.isNotEmpty())

        val bundle = story("runtime-node-api-1")
        assertTrue(bundle.headline.startsWith("These differences together cause the failure: "))
        assertTrue(bundle.headline.contains("the node version"))
        assertTrue(bundle.headline.contains("could not tell which one matters"))
        assertTrue(bundle.sureness.startsWith("The effect is confirmed for the whole group."))
        assertTrue(bundle.next.contains("change them one at a time"))

        val narrowed = story("env-retries-flaky-plain-1")
        assertEquals(
            "Drift narrowed the cause down to the environment variable MAX_RETRIES but could " +
                "not confirm it.",
            narrowed.headline,
        )
        assertTrue(narrowed.sureness.startsWith("Not sure."))
        assertTrue(narrowed.sureness.contains("not a clear enough result"))
        assertEquals(
            "Drift suggests this next step: Re-run env.MAX_RETRIES with more trials per arm.",
            narrowed.next,
        )

        val stuck = story("limits-nofile-1")
        assertEquals(
            "The failure did not happen again in a container built from the failing run, so " +
                "Drift had nothing to explain.",
            stuck.headline,
        )
        assertEquals("No conclusion.", stuck.sureness)
        assertTrue(stuck.next.startsWith("Drift has no next experiment to suggest."))
    }

    @Test
    fun aCaseWhoseIsolationWasNotMeasuredSaysSoInPlainWords() {
        val opened = assertIs<ArchiveOpen.Opened>(Archive.open(File(path).readBytes()))
        val files = opened.case.files.toMutableMap()
        val certificate = files.getValue(SolveCertificate.CERTIFICATE)
        files[SolveCertificate.CERTIFICATE] = certificate.replaceFirst(
            "\"kind\":\"confirmed\"",
            "\"kind\":\"confirmed-effect\"",
        )
        val effect = CaseStory.of(CaseFile(files))
        val opening = "Setting the time zone (TZ) to the failing values"
        assertTrue(effect.headline.startsWith(opening), effect.headline)
        assertTrue(effect.headline.contains("did not capture both containers"))
        val sure = "The effect is confirmed; the cause is not isolated."
        assertTrue(effect.sureness.startsWith(sure), effect.sureness)
        assertTrue(effect.next.contains("where the containers can be captured"))
    }

    @Test
    fun aFileWithoutAStoryStillShowsItsChecksAndSaysItCouldNotBeSummarized() = runComposeUiTest {
        val case = CaseView("ok.driftcase", listOf(CaseLine("PASS tar: 1 entries", false)), "V")
        assertTrue(case.ok)
        setContent { StudioApp(case = case, dark = false) }
        onNodeWithTag("case-headline").assertTextEquals("Drift Could Not Summarize This File")
        onNodeWithTag("case-status").assertTextContains("every check passed", substring = true)
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithText("PASS tar: 1 entries").assertExists()
        onNodeWithText("Recorded Verdict").assertExists()
    }

    @Test
    fun aTamperedArchiveShowsTheVerifyFailureInsteadOfCrashing() = runComposeUiTest {
        val case = tampered()
        assertFalse(case.ok)
        assertTrue(case.lines.any { it.failed && it.text.startsWith("FAIL manifest") })
        setContent { StudioApp(case = case, dark = false) }
        onNodeWithTag("case-status").assertTextEquals("This file did not pass its checks")
        onAllNodesWithText(case.failure!!.text).assertCountEquals(0)
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onAllNodesWithText(case.failure!!.text).assertCountEquals(1)
    }

    @Test
    fun unreadableInputBecomesAFailureLine() = runComposeUiTest {
        val junk = CaseView.of("junk.driftcase", ByteArray(2000) { (it * 31).toByte() })
        assertFalse(junk.ok)
        assertNull(junk.story)
        val missing = loadCase("nowhere.driftcase")
        assertEquals("cannot read nowhere.driftcase: not a file", missing.failure?.text)
        setContent { StudioApp(case = missing, dark = true) }
        onNodeWithTag("case-status").assertTextEquals("This file did not pass its checks")
        onNodeWithTag("case-headline").assertTextEquals("Drift Could Not Summarize This File")
    }

    @Test
    fun noArgumentMeansNoCaseTab() = runComposeUiTest {
        assertNull(caseFromArgs(emptyArray()))
        assertNull(caseFromArgs(arrayOf("--verbose")))
        setContent { StudioApp(dark = false) }
        onNodeWithText("Case").assertDoesNotExist()
        onNodeWithText("Home").assertIsSelected()
    }
}
