package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CiScreenTest {
    private val logs = "../fixtures/drangler/logs"
    private val gitlab = "../fixtures/ci"
    private val token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz"

    private fun github(version: String, env: List<String> = emptyList()) = (
        listOf(
            "##[group]Operating System",
            "Ubuntu",
            "24.04.5",
            "LTS",
            "##[endgroup]",
            "##[group]Runner Image",
            "Image: ubuntu-24.04",
            "Version: $version",
            "##[endgroup]",
            "##[group]Run echo hi",
            "echo hi",
            "env:",
        ) + env + "##[endgroup]"
        ).joinToString("\n")

    private val sampleHeadline: String get() {
        val s = ciSample()
        val done = CiDiagnosis.run(s.passing, s.failing, null, s.job) as CiResult.Done
        return Plain.reading(done.state).headline
    }

    private fun SemanticsNodeInteraction.paste(text: String) =
        performScrollTo().performTextInput(text)

    @Test
    fun theSampleLogsReachAResultFromHomeInTwoClicks() = runComposeUiTest {
        var clicks = 0
        setContent { StudioApp(dark = false) }
        onNodeWithText("Home").assertIsSelected()
        onNodeWithTag("home:ci").performScrollTo().performClick()
        clicks++
        onNodeWithText("CI").assertIsSelected()
        onNodeWithTag("ci:sample").performScrollTo().performClick()
        clicks++
        onNodeWithTag("summary").performScrollTo()
            .assertTextEquals(sampleHeadline)
        onNodeWithTag("ci:limit").assertTextEquals(CI_LIMIT)
        onNodeWithTag("ci-pass:chosen").assertTextEquals("Chosen: sample passing log")
        onNodeWithTag("ci-fail:chosen").assertTextEquals("Chosen: sample failing log")
        onNodeWithTag("ci-pass:format").assertTextEquals("Looks like GitHub Actions.")
        assertEquals(2, clicks)
    }

    @Test
    fun aPastedPairIsDiagnosedAndTheJobFieldIsOptional() = runComposeUiTest {
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci-pass:text").paste(github("1.0"))
        onNodeWithTag("ci-fail:text").paste(github("2.0"))
        onNodeWithTag("ci-pass:chosen").assertTextEquals("Chosen: pasted text")
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("summary").performScrollTo()
            .assertTextContains("CI setting runner.image.version", substring = true)
        onNodeWithTag("ci:problem").assertDoesNotExist()
        onNodeWithTag("ci:limit").assertDoesNotExist()
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithTag("ci:plan").performScrollTo().assertExists()
    }

    @Test
    fun theProviderIsDetectedForGithubAndGitlabLogs() = runComposeUiTest {
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci-pass:format").assertDoesNotExist()
        onNodeWithTag("ci-pass:text").paste(github("1.0"))
        onNodeWithTag("ci-fail:text").paste(File("$gitlab/gitlab-red.trace").readText())
        onNodeWithTag("ci-pass:format").assertTextEquals("Looks like GitHub Actions.")
        onNodeWithTag("ci-fail:format").assertTextEquals("Looks like GitLab CI.")
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("ci:problem").assertTextContains("different CI systems", substring = true)
    }

    @Test
    fun aGitlabPairIsDiagnosed() = runComposeUiTest {
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci-pass:text").paste(File("$gitlab/gitlab-green.trace").readText())
        onNodeWithTag("ci-fail:text").paste(File("$gitlab/gitlab-red.trace").readText())
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("summary").performScrollTo().assertExists()
        onNodeWithTag("ci:problem").assertDoesNotExist()
    }

    @Test
    fun emptyLogsAreRefusedInPlainWords() = runComposeUiTest {
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("ci:problem")
            .assertTextEquals("The passing log is empty. Paste it or choose a file.")
        onNodeWithTag("ci-pass:text").paste(github("1.0"))
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("ci:problem")
            .assertTextEquals("The failing log is empty. Paste it or choose a file.")
        onNodeWithTag("summary").assertDoesNotExist()
    }

    @Test
    fun anUnknownProviderOffersBothChoicesAndTheChoiceRunsTheDiagnosis() = runComposeUiTest {
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci-pass:text").paste("nothing recognizable")
        onNodeWithTag("ci-fail:text").paste("nothing either")
        onNodeWithTag("ci:format:github").assertDoesNotExist()
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("ci:problem")
            .assertTextEquals("Drift cannot tell which CI system made either log.")
        onNodeWithTag("ci:format:gitlab").performScrollTo().assertExists()
        onNodeWithTag("ci:format:github").performScrollTo().performClick()
        onNodeWithTag("ci:format:github").assertIsSelected()
        onNodeWithTag("ci:problem").assertTextContains(
            "The passing log has no runner environment section, so Drift has nothing to " +
                "compare.",
            substring = true,
        )
        onNodeWithTag("ci:format:github").assertExists()
    }

    @Test
    fun aJobNameThatIsNotInTheLogIsExplained() = runComposeUiTest {
        val picks = ArrayDeque(
            listOf(
                PickedFile("green.log", File("$logs/36702683742.log").readBytes()),
                PickedFile("red.log", File("$logs/36995781138.log").readBytes()),
            ),
        )
        setContent {
            StudioTheme(false) { CiScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        }
        onNodeWithTag("ci-pass:pick").performClick()
        onNodeWithTag("ci-fail:pick").performScrollTo().performClick()
        onNodeWithTag("ci-pass:chosen").assertTextEquals("Chosen: green.log")
        onNodeWithTag("ci-fail:chosen").assertTextEquals("Chosen: red.log")
        onNodeWithTag("ci:job").paste("Nope")
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("ci:problem").assertTextContains("the job \"Nope\"", substring = true)
    }

    @Test
    fun chosenFilesAreDiagnosedWithTheirJobName() = runComposeUiTest {
        val picks = ArrayDeque(
            listOf(
                PickedFile("green.log", File("$logs/36702683742.log").readBytes()),
                PickedFile("red.log", File("$logs/36995781138.log").readBytes()),
            ),
        )
        setContent {
            StudioTheme(false) { CiScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        }
        onNodeWithTag("ci-pass:pick").performClick()
        onNodeWithTag("ci-fail:pick").performScrollTo().performClick()
        onNodeWithTag("ci:job").paste("Docker E2E")
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithTag("summary").performScrollTo()
            .assertTextEquals(sampleHeadline)
    }

    @Test
    fun anUnreadableFileKeepsTheScreenAndSaysWhy() = runComposeUiTest {
        val picks = ArrayDeque(
            listOf(
                PickedFile("a.log", null, "permission denied"),
                PickedFile("b.log", byteArrayOf(-1, -2, -3)),
                PickedFile("c.log", ByteArray(0)),
            ),
        )
        setContent {
            StudioTheme(false) { CiScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        }
        onNodeWithTag("ci-pass:pick").performClick()
        onNodeWithTag("ci:problem").assertTextEquals("cannot read a.log: permission denied.")
        onNodeWithTag("ci-pass:pick").performClick()
        onNodeWithTag("ci:problem").assertTextEquals("cannot read b.log: it is not a text file.")
        onNodeWithTag("ci-pass:pick").performClick()
        onNodeWithTag("ci:problem").assertTextEquals("cannot read c.log: the file is empty.")
        onNodeWithTag("ci-pass:chosen").assertTextEquals("Nothing chosen yet.")
        assertEquals(0, picks.size)
    }

    @Test
    fun withoutAPickerTheScreenStillTakesPastedLogs() = runComposeUiTest {
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci-pass:pick").assertDoesNotExist()
        onNodeWithTag("ci-pass:text").assertExists()
    }

    @Test
    fun aTokenInAPastedLogIsMaskedInTheFieldTheResultAndTheDetails() = runComposeUiTest {
        val env = listOf("  API_TOKEN: $token", "  NOTE: see $token", "  MODE: one")
        setContent { StudioTheme(false) { CiScreen() } }
        onNodeWithTag("ci-pass:text").paste(github("1.0", env))
        onNodeWithTag("ci-fail:text").paste(github("2.0", env.dropLast(1) + "  MODE: two"))
        onNodeWithTag("ci:run").performScrollTo().performClick()
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithTag("summary").performScrollTo().assertExists()
        onAllNodesWithText(token, substring = true).assertCountEquals(0)
        onAllNodesWithText("ghp_", substring = true).assertCountEquals(0)
    }

    @Test
    fun theFlowNeverAsksForAProxyOrAConnection() = runComposeUiTest {
        val asked = mutableListOf<URI>()
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    asked += uri
                    return listOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI, sa: SocketAddress, e: java.io.IOException) {
                    asked += uri
                }
            },
        )
        try {
            setContent { StudioApp(dark = false) }
            onNodeWithTag("home:ci").performScrollTo().performClick()
            onNodeWithTag("ci:sample").performScrollTo().performClick()
            onNodeWithTag("summary").performScrollTo().assertExists()
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertTrue(asked.isEmpty(), "network use: $asked")
    }

    @Test
    fun theActionBothAreasAndTheResultAreReachableAt360dp() = runComposeUiTest {
        setContent { Box(Modifier.width(360.dp)) { StudioTheme(false) { CiScreen() } } }
        onNodeWithTag("ci:sample").performScrollTo().assertIsDisplayed()
        onNodeWithTag("ci-pass:text").performScrollTo().assertIsDisplayed()
        onNodeWithTag("ci-fail:text").performScrollTo().assertIsDisplayed()
        onNodeWithTag("ci:run").performScrollTo().assertIsDisplayed()
        onNodeWithTag("ci:sample").performScrollTo().performClick()
        onNodeWithTag("ci:limit").performScrollTo().assertIsDisplayed()
        onNodeWithTag("summary").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun darkThemeRendersTheSameScreen() = runComposeUiTest {
        setContent { StudioTheme(dark = true) { CiScreen() } }
        onNodeWithText("Diagnose My CI").assertExists()
    }
}
