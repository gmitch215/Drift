package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.gmitch215.drift.upgrade.Group
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UpgradeScreenTest {
    private val sample = upgradeSamples().first()
    private val path = "../fixtures/upgrade-check/drupal-10-php-8-1.json"

    private fun view(text: String) =
        (UpgradeStory.check(sample.value, text) as UpgradeCheckResult.Done).view

    @Test
    fun startsEmptyAndTellsADesktopFreeUserWhatToDo() = runComposeUiTest {
        setContent { UpgradeScreen() }
        onNodeWithText("Check an Upgrade").assertExists()
        onNodeWithText(UPGRADE_ABOUT).assertExists()
        onNodeWithText("What to do next", substring = true).assertExists()
        onNodeWithTag("upgrade:pick").assertDoesNotExist()
        onNodeWithText(NO_FILES).assertExists()
        onNodeWithTag("upgrade:chosen").assertTextEquals("Nothing chosen yet.")
        onNodeWithTag("upgrade:headline").assertDoesNotExist()
        onNodeWithTag("upgrade:check").performClick()
        onNodeWithTag("upgrade:problem").assertTextEquals("Choose a capsule first.")
    }

    @Test
    fun aSampleAndACommonMoveGiveTheSentenceAndTheThreeGroups() = runComposeUiTest {
        setContent { UpgradeScreen() }
        onNodeWithTag("upgrade:sample:0").performClick()
        onNodeWithTag("upgrade:chosen").assertTextEquals("Chosen: ${sample.name}")
        val offered = UpgradeStory.presetsFor(sample.value)
        val i = offered.indexOfFirst { it.label == "PHP 8.3 and Drupal 11" }
        onNodeWithTag("preset:$i").performScrollTo().performClick()
        val expected = view("php=8.3,drupal=11")
        onNodeWithTag("upgrade:headline").assertTextEquals(expected.headline)
        onNodeWithText(UPGRADE_CAVEAT).assertExists()
        Group.entries.forEach { g ->
            val n = expected.report.of(g).size
            val words = UpgradeStory.words(g)
            onNodeWithText("${words.title} ($n)").performScrollTo().assertExists()
            if (n == 0) onNodeWithText(words.none).assertExists()
        }
        expected.report.findings.forEach {
            onNodeWithText(it.rule.title).performScrollTo().assertExists()
        }
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithTag("upgrade:text")
            .assertTextContains("upgrade-check: ${sample.value.label}", substring = true)
    }

    @Test
    fun typedVersionsAreCheckedAndABadEntryIsExplained() = runComposeUiTest {
        setContent { UpgradeScreen() }
        onNodeWithTag("upgrade:sample:0").performClick()
        onNodeWithTag("upgrade:targets").performTextInput("php=8.3")
        onNodeWithTag("upgrade:check").performScrollTo().performClick()
        onNodeWithTag("upgrade:headline").assertTextEquals(view("php=8.3").headline)
        onNodeWithTag("upgrade:targets").performTextInput(",cobol=1")
        onNodeWithTag("upgrade:check").performScrollTo().performClick()
        onNodeWithTag("upgrade:bad").assertTextContains("unknown target 'cobol'", substring = true)
        onNodeWithTag("upgrade:headline").assertDoesNotExist()
    }

    @Test
    fun emptyTargetsAreRefusedWithASentence() = runComposeUiTest {
        setContent { UpgradeScreen() }
        onNodeWithTag("upgrade:sample:0").performClick()
        onNodeWithTag("upgrade:check").performScrollTo().performClick()
        onNodeWithTag("upgrade:bad").assertTextEquals(UpgradeStory.NO_TARGETS)
    }

    @Test
    fun aChosenFileLoadsAndAFileThatIsNotACapsuleStaysOnTheScreenWithAReason() = runComposeUiTest {
        val picks = ArrayDeque(
            listOf(
                PickedFile("junk.json", "{}".encodeToByteArray()),
                PickedFile("site.json", File(path).readBytes()),
            ),
        )
        setContent { UpgradeScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        onNodeWithTag("upgrade:pick").performClick()
        onNodeWithTag("upgrade:problem").assertTextContains("cannot read junk.json: ", true)
        onNodeWithTag("upgrade:chosen").assertTextEquals("Nothing chosen yet.")
        onNodeWithTag("upgrade:pick").performClick()
        onNodeWithTag("upgrade:chosen").assertTextEquals("Chosen: site.json")
        onNodeWithTag("upgrade:problem").assertDoesNotExist()
        assertEquals(0, picks.size)
    }

    @Test
    fun darkThemeRendersTheSameScreen() = runComposeUiTest {
        setContent { StudioTheme(dark = true) { UpgradeScreen() } }
        onNodeWithText("Check an Upgrade").assertExists()
    }
}
