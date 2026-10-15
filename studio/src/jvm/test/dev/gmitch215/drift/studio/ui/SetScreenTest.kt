package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SetScreenTest {
    private val samples = setSamples()
    private val toolchain = samples.indexOfFirst { "Node.js" in it.name }
    private val view = SetStory.of(samples[toolchain].entries)!!

    private fun file(name: String) =
        PickedFile(name, File("../fixtures/set/toolchain/$name").readBytes())

    @Test
    fun startsEmptyAndExplainsWhatToDo() = runComposeUiTest {
        setContent { SetScreen() }
        onNodeWithText(SET_ABOUT).assertExists()
        onNodeWithText("What to do next", substring = true).assertExists()
        onNodeWithTag("set:count").assertTextEquals("Nothing added yet.")
        onNodeWithTag("set:pass").assertDoesNotExist()
        onNodeWithText(NO_FILES).assertExists()
        onNodeWithTag("set:headline").assertDoesNotExist()
    }

    @Test
    fun aSampleSetShowsTheHeadlineAndEveryRanking() = runComposeUiTest {
        setContent { SetScreen() }
        onNodeWithTag("set:sample:$toolchain").performClick()
        onNodeWithTag("set:count").assertTextEquals("4 capsules: 2 failing, 2 passing.")
        onNodeWithTag("set:headline").assertTextEquals(view.headline)
        onNodeWithText(SetStory.CAVEAT).assertExists()
        view.rows.indices.forEach { onNodeWithTag("split:$it").performScrollTo().assertExists() }
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithTag("set:text").assertTextContains("set: 2 failing, 2 passing", substring = true)
        onNodeWithTag("set:clear").performScrollTo().performClick()
        onNodeWithTag("set:headline").assertDoesNotExist()
        onNodeWithTag("set:count").assertTextEquals("Nothing added yet.")
    }

    @Test
    fun capsulesAddedByHandBuildTheSetAndOneSideAskForTheOther() = runComposeUiTest {
        val picks = ArrayDeque(
            listOf(
                file("pass-1.json"),
                PickedFile("junk.json", "{}".encodeToByteArray()),
                file("fail-1.json"),
            ),
        )
        setContent { SetScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        onNodeWithTag("set:pass").performClick()
        onNodeWithTag("set:entry:0").assertTextEquals("Passing: pass-1.json")
        onNodeWithTag("set:headline").assertTextEquals(SetStory.NEED_BOTH)
        onNodeWithTag("set:fail").performClick()
        onNodeWithTag("set:problem").assertTextContains("cannot read junk.json: ", true)
        onNodeWithTag("set:count").assertTextEquals("1 capsule: 0 failing, 1 passing.")
        onNodeWithTag("set:fail").performClick()
        onNodeWithTag("set:problem").assertDoesNotExist()
        onNodeWithTag("set:entry:1").assertTextEquals("Failing: fail-1.json")
        onNodeWithTag("set:headline").assertExists()
        assertEquals(0, picks.size)
    }

    @Test
    fun darkThemeRendersTheSameScreen() = runComposeUiTest {
        setContent { StudioTheme(dark = true) { SetScreen() } }
        onNodeWithText(SET_ABOUT).assertExists()
    }
}
