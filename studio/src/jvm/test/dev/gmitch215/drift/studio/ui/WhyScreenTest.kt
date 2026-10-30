package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WhyScreenTest {
    private val asking = "The build fails on our server with node and npm.\n"
    private val token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz"

    private fun github(version: String, failed: Boolean, env: List<String> = emptyList()) = (
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
        ) + env + "##[endgroup]" +
            if (failed) listOf("##[error]Process completed with exit code 1.") else emptyList()
        ).joinToString("\n")

    private fun SemanticsNodeInteraction.paste(text: String) =
        performScrollTo().performTextInput(text)

    private fun ComposeUiTest.send(text: String) {
        onNodeWithTag("why:composer").paste(text)
        onNodeWithTag("why:send").performScrollTo().performClick()
    }

    private fun ComposeUiTest.wholeLoop(dark: Boolean, width: Int) {
        val copied = mutableListOf<String>()
        setContent {
            Box(Modifier.width(width.dp)) {
                StudioTheme(dark) { WhyScreen(copy = { copied += it }) }
            }
        }
        onNodeWithTag("why:composer").performScrollTo().assertIsDisplayed()
        send(asking)
        onNodeWithTag("why:understood:0").performScrollTo()
            .assertTextEquals("I read this as an error message.")
        onNodeWithTag("why:reread").performScrollTo().performClick()
        onNodeWithTag("why:understood:0").performScrollTo()
            .assertTextEquals("I read this as an issue text.")
        onNodeWithTag("why:certainty:1").performScrollTo().assertIsDisplayed()
        onNodeWithTag("why:card:sentence").performScrollTo().assertTextContains(
            "To tell, run node --version where it fails and paste the result",
            substring = true,
        )
        onNodeWithTag("why:card:command").performScrollTo().assertTextEquals("node --version")
        onNodeWithTag("why:card:copy").performScrollTo().performClick()
        assertEquals(listOf("node --version"), copied)
        onNodeWithTag("why:reply").paste("v18.19.0\n")
        onNodeWithTag("why:reply:send").performScrollTo().performClick()
        onNodeWithTag("why:found:3").performScrollTo().assertTextContains(
            "Node version: not stated where it works, 18.19.0 where it fails",
            substring = true,
        )
        onNodeWithTag("why:works").paste("v20.11.1\n")
        onNodeWithTag("why:works:send").performScrollTo().performClick()
        onNodeWithTag("why:found:5").performScrollTo().assertTextContains(
            "Node version: 20.11.1 where it works, 18.19.0 where it fails",
            substring = true,
        )
        onNodeWithTag("why:card").assertDoesNotExist()
        onNodeWithTag("why:export").performScrollTo().performClick()
        assertTrue(copied.last().startsWith("Drift conversation"), copied.last())
        onNodeWithTag("why:new").performScrollTo().performClick()
        onNodeWithTag("why:message:0").assertDoesNotExist()
    }

    @Test
    fun theWholeLoopRunsInTheLightTheme() = runComposeUiTest { wholeLoop(false, 1000) }

    @Test
    fun theWholeLoopRunsAt360dpInTheDarkTheme() = runComposeUiTest { wholeLoop(true, 360) }

    @Test
    fun homeOffersTheChatFirstAndOpensIt() = runComposeUiTest {
        setContent { StudioApp(dark = false) }
        onNodeWithText(WHY_ABOUT_HOME).assertExists()
        val why = onNodeWithTag("home:why").getUnclippedBoundsInRoot()
        val ci = onNodeWithTag("home:ci").getUnclippedBoundsInRoot()
        assertTrue(why.top < ci.top, "$why before $ci")
        onNodeWithTag("home:why").performClick()
        onNodeWithText("Why").assertIsSelected()
        onNodeWithTag("why:composer").assertExists()
    }

    @Test
    fun theConversationSurvivesASwitchToAnotherTab() = runComposeUiTest {
        setContent { StudioApp(dark = false) }
        onNodeWithTag("home:why").performClick()
        send(asking)
        onNodeWithTag("why:verdict:1").assertExists()
        onNodeWithText("Home").performClick()
        onNodeWithText("Why").performClick()
        onNodeWithTag("why:verdict:1").performScrollTo().assertExists()
    }

    @Test
    fun aCiLogPairIsDiagnosedInsideTheChat() = runComposeUiTest {
        setContent { StudioTheme(false) { WhyScreen() } }
        send(github("1.0", failed = false))
        onNodeWithTag("why:note:1").performScrollTo().assertTextContains(
            "Paste the log of a run that failed",
            substring = true,
        )
        send(github("2.0", failed = true))
        onNodeWithTag("why:verdict:3").performScrollTo().assertTextContains(
            "CI setting runner.image.version",
            substring = true,
        )
        onNodeWithText("Show the Ranked Differences").performScrollTo().performClick()
        onNodeWithTag("summary").performScrollTo().assertExists()
    }

    @Test
    fun aSecretInAPasteIsNeverOnScreen() = runComposeUiTest {
        val env = listOf("  API_TOKEN: $token", "  MODE: one")
        setContent { StudioTheme(false) { WhyScreen() } }
        send("Fails with node. Token $token was in the log.\n")
        send(github("1.0", failed = false, env = env))
        send(github("2.0", failed = true, env = env))
        onNodeWithText("Show the Ranked Differences").performScrollTo().performClick()
        onAllNodesWithText(token, substring = true).assertCountEquals(0)
        onAllNodesWithText("ghp_0123", substring = true).assertCountEquals(0)
    }

    @Test
    fun anEmptySendSaysWhatToPaste() = runComposeUiTest {
        setContent { StudioTheme(false) { WhyScreen() } }
        onNodeWithTag("why:send").performScrollTo().performClick()
        onNodeWithTag("why:note:0").performScrollTo().assertTextEquals(WHY_EMPTY)
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
            onNodeWithTag("home:why").performClick()
            send(asking)
            onNodeWithTag("why:reply").paste("v18.19.0\n")
            onNodeWithTag("why:reply:send").performScrollTo().performClick()
            onNodeWithTag("why:found:3").performScrollTo().assertExists()
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertTrue(asked.isEmpty(), "network use: $asked")
    }
}
