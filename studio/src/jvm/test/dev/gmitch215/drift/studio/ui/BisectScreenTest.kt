package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class BisectScreenTest {
    private val first = samples().first()
    private val view = BisectStory.of(first.green, first.red)!!

    @Test
    fun showsThePlanOnceBothCapsulesAreChosen() = runComposeUiTest {
        setContent { BisectScreen() }
        onNodeWithText(BISECT_ABOUT).assertExists()
        onNodeWithTag("bisect:headline").assertDoesNotExist()
        onNodeWithTag("green:sample:0").performClick()
        onNodeWithTag("bisect:headline").assertDoesNotExist()
        onNodeWithTag("red:sample:0").performClick()
        onNodeWithTag("green:chosen").assertTextEquals("Chosen: Passing Run $GREEN_RUN")
        onNodeWithTag("red:chosen").assertTextEquals("Chosen: ${first.name}")
        onNodeWithTag("bisect:headline").assertTextEquals(view.headline)
        onNodeWithText("Settings That Differ (${view.settings.size})")
            .performScrollTo().assertExists()
        view.settings.indices.forEach { onNodeWithTag("setting:$it").assertExists() }
        val test = view.first!!
        onNodeWithTag("bisect:test").performScrollTo().assertTextEquals(test.sentence)
        test.settings.indices.forEach { onNodeWithTag("test-setting:$it").assertExists() }
        onNodeWithText(BisectStory.NOT_RUN).assertExists()
        onNodeWithText(test.commands.last()).assertExists()
    }

    @Test
    fun twoIdenticalCapsulesSayThereIsNothingToBisect() = runComposeUiTest {
        val green = first.green
        val picks = ArrayDeque(
            listOf(PickedFile("a.json", green.canonical().encodeToByteArray())),
        )
        setContent { BisectScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        onNodeWithTag("green:pick").performClick()
        onNodeWithTag("green:chosen").assertTextEquals("Chosen: a.json")
        picks.add(PickedFile("b.json", green.canonical().encodeToByteArray()))
        onNodeWithTag("red:pick").performClick()
        onNodeWithTag("bisect:headline").assertTextEquals(BisectStory.NONE)
    }

    @Test
    fun technicalDetailsHoldThePlanJson() = runComposeUiTest {
        setContent { BisectScreen() }
        onNodeWithTag("green:sample:0").performClick()
        onNodeWithTag("red:sample:0").performClick()
        onNodeWithTag("bisect:json").assertDoesNotExist()
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithTag("bisect:json").assertTextEquals(view.plan.canonical())
    }

    @Test
    fun aFileThatIsNotACapsuleIsExplained() = runComposeUiTest {
        val picks = ArrayDeque(listOf(PickedFile("bad.json", "[]".encodeToByteArray())))
        setContent { BisectScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        onNodeWithTag("red:pick").performClick()
        onNodeWithTag("red:problem").assertExists()
        onNodeWithTag("red:chosen").assertTextEquals("Nothing chosen yet.")
    }
}
