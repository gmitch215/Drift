package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.contract.ContractResult
import dev.gmitch215.drift.contract.Holds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ContractScreenTest {
    private val first = samples().first()
    private val contract = ContractStory.sampleContract(first)!!

    @Test
    fun waitsForBothInputsThenShowsTheSentence() = runComposeUiTest {
        setContent { ContractScreen() }
        onNodeWithText(CONTRACT_ABOUT).assertExists()
        onNodeWithTag("contract:chosen").assertTextEquals("Nothing chosen yet.")
        onNodeWithTag("contract:headline").assertDoesNotExist()
        onNodeWithTag("contract:sample:0").performClick()
        onNodeWithTag("contract:headline").assertDoesNotExist()
        onNodeWithTag("capsule:sample:0").performClick()
        onNodeWithTag("capsule:chosen").assertTextEquals("Chosen: Passing Run $GREEN_RUN")
        onNodeWithTag("contract:headline").assertTextEquals(
            ContractStory.headline(contract.verify(first.green)),
        )
    }

    @Test
    fun aFailingRunShowsTheBrokenRequirementsFirstInPlainWords() = runComposeUiTest {
        setContent { ContractScreen() }
        onNodeWithTag("contract:sample:0").performClick()
        onNodeWithTag("capsule:sample:1").performClick()
        val report = contract.verify(first.red)
        onNodeWithTag("contract:headline").assertTextEquals(ContractStory.headline(report))
        val broken = report.results.first { it.finding.status == Holds.BREAKS }
        val words = ContractStory.words(broken)
        onNodeWithText(words.ask).performScrollTo().assertExists()
        onNodeWithText("${ContractStory.title(Holds.BREAKS)} (${report.breaks})").assertExists()
        onNodeWithTag("requirement:breaks:0").assertExists()
        onNodeWithText("Show Technical Details").performScrollTo().performClick()
        onNodeWithTag("contract:text").assertExists()
    }

    @Test
    fun longGroupsShowEightRequirementsAndOfferTheRest() = runComposeUiTest {
        val greens = Contract.create(listOf(first.green)) as ContractResult.Created
        val holding = greens.contract.verify(first.green).holds
        assertTrue(holding > 8)
        val picks = ArrayDeque(
            listOf(PickedFile("greens.json", greens.contract.render().encodeToByteArray())),
        )
        setContent { ContractScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        onNodeWithTag("contract:pick").performClick()
        onNodeWithTag("capsule:sample:0").performClick()
        onNodeWithTag("requirement:holds:7").performScrollTo().assertExists()
        onNodeWithTag("requirement:holds:8").assertDoesNotExist()
        onNodeWithTag("more:holds").performScrollTo().assertTextEquals("Show ${holding - 8} More")
        onNodeWithTag("more:holds").performClick()
        onNodeWithTag("requirement:holds:${holding - 1}").performScrollTo().assertExists()
        onNodeWithTag("more:holds").assertTextEquals("Show Fewer")
    }

    @Test
    fun chosenFilesLoadAndAWrongKindOfFileIsExplained() = runComposeUiTest {
        val picks = ArrayDeque(
            listOf(
                PickedFile("capsule-as-contract.json", File(CAPSULE).readBytes()),
                PickedFile("made.json", contract.render().encodeToByteArray()),
                PickedFile("failing.json", File(CAPSULE).readBytes()),
            ),
        )
        setContent { ContractScreen(FilePicker { _, done -> done(picks.removeFirst()) }) }
        onNodeWithTag("contract:pick").performClick()
        onNodeWithTag("contract:problem").assertTextContains(
            "cannot read capsule-as-contract.json: ",
            substring = true,
        )
        onNodeWithTag("contract:problem").assertTextContains(
            "Choose a contract file made by drift contract create.",
            substring = true,
        )
        onNodeWithTag("contract:pick").performClick()
        onNodeWithTag("contract:chosen").assertTextEquals("Chosen: made.json")
        onNodeWithTag("capsule:pick").performClick()
        onNodeWithTag("capsule:chosen").assertTextEquals("Chosen: failing.json")
        onNodeWithTag("contract:headline").assertExists()
    }

    @Test
    fun withoutAPickerTheScreenSaysSamplesOnly() = runComposeUiTest {
        setContent { ContractScreen() }
        onNodeWithTag("contract:pick").assertDoesNotExist()
        onNodeWithTag("capsule:pick").assertDoesNotExist()
    }

    private companion object {
        const val CAPSULE = "../fixtures/drangler/capsules/36995781138.json"
    }
}
