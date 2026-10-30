package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {
    private val path = "../fixtures/lab/cases/locale-tz-date-2.driftcase"

    @Test
    fun offersThreeWaysInEachWithOneLineOnWhatItDoes() = runComposeUiTest {
        setContent { StudioApp(dark = false, picker = CasePicker { }) }
        onNodeWithText(HOME_ABOUT).assertExists()
        onNodeWithText(OPEN_ABOUT).assertExists()
        onNodeWithText(SAMPLE_ABOUT).assertExists()
        onNodeWithText(ATLAS_ABOUT).assertExists()
        onNodeWithTag("home:open").assertExists()
        onNodeWithTag("home:sample").performScrollTo().performClick()
        onNodeWithText("Investigation").assertIsSelected()
        onNodeWithText("Home").performClick()
        onNodeWithTag("home:atlas").performScrollTo().performClick()
        onNodeWithText("Atlas").assertIsSelected()
    }

    private fun ComposeUiTest.openingACaseLeavesTheOtherTabsInPlace(width: Int) {
        val case = CaseView.of("locale-tz-date-2.driftcase", File(path).readBytes())
        setContent {
            Box(Modifier.width(width.dp)) {
                StudioApp(dark = false, picker = CasePicker { it(case) })
            }
        }
        val tabs =
            listOf("Home", "Atlas", "Investigation", "CI", "Upgrade", "Contract", "Bisect", "Set")
        val before = tabs.map { onNodeWithText(it).getUnclippedBoundsInRoot() }
        onNodeWithTag("home:open").performScrollTo().performClick()
        onNodeWithText("Case").assertIsSelected()
        onNodeWithText("Home").performClick()
        val after = tabs.map { onNodeWithText(it).getUnclippedBoundsInRoot() }
        assertEquals(before, after)
    }

    @Test
    fun openingACaseLeavesTheOtherTabsInPlaceOnDesktop() = runComposeUiTest {
        openingACaseLeavesTheOtherTabsInPlace(1000)
    }

    @Test
    fun openingACaseLeavesTheOtherTabsInPlaceAtPhoneWidth() = runComposeUiTest {
        openingACaseLeavesTheOtherTabsInPlace(360)
    }

    @Test
    fun theDiagnoseActionOpensTheCiScreen() = runComposeUiTest {
        setContent { StudioApp(dark = false) }
        onNodeWithText(CI_ABOUT_HOME).assertExists()
        onNodeWithTag("home:ci").performScrollTo().performClick()
        onNodeWithText("CI").assertIsSelected()
        onNodeWithTag("ci:sample").assertExists()
    }

    @Test
    fun theCheckScreensAreReachableFromHomeWithOneLineEach() = runComposeUiTest {
        setContent { StudioApp(dark = false) }
        onNodeWithText(UPGRADE_ABOUT_HOME).assertExists()
        onNodeWithText(CONTRACT_ABOUT_HOME).assertExists()
        onNodeWithText(BISECT_ABOUT_HOME).assertExists()
        onNodeWithText(SET_ABOUT_HOME).assertExists()
        listOf(
            "upgrade" to "Upgrade",
            "contract" to "Contract",
            "bisect" to "Bisect",
            "set" to "Set",
        )
            .forEach { (tag, tab) ->
                onNodeWithTag("home:$tag").performScrollTo().performClick()
                onNodeWithText(tab).assertIsSelected()
                onNodeWithText("Home").performClick()
            }
    }

    @Test
    fun chooseACaseFileOpensItOnTheCaseTab() = runComposeUiTest {
        var asked = 0
        val picker = CasePicker {
            asked++
            it(loadCase(path))
        }
        setContent { StudioApp(dark = false, picker = picker) }
        onNodeWithTag("home:open").performScrollTo().performClick()
        assertEquals(1, asked)
        onNodeWithText("Case").assertIsSelected()
        onNodeWithTag("case-headline").assertTextContains("the time zone (TZ)", substring = true)
    }

    @Test
    fun aFileThatCannotBeReadStaysOnHomeWithAnExplanation() = runComposeUiTest {
        val picker = CasePicker { it(CaseView.unreadable("junk.driftcase", "not a tar archive")) }
        setContent { StudioApp(dark = false, picker = picker) }
        onNodeWithTag("home:open").performScrollTo().performClick()
        onNodeWithText("Home").assertIsSelected()
        onNodeWithTag("home-problem").assertTextContains(
            "cannot read junk.driftcase: not a tar archive. Choose a .driftcase file made by " +
                "drift solve.",
        )
        onNodeWithText("Case").assertDoesNotExist()
    }

    @Test
    fun withoutAPickerTheOpenOptionIsReplacedByANote() = runComposeUiTest {
        setContent { StudioApp(dark = true) }
        onNodeWithTag("home:open").assertDoesNotExist()
        onNodeWithText(NO_PICKER).assertExists()
        onNodeWithTag("home:sample").assertExists()
    }
}
