package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.model.Capsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadsTest {
    private val green = samples().first().green

    private fun bad(load: Load<*>) = (load as Load.Bad).message

    @Test
    fun aCapsuleFileRoundTrips() {
        val file = PickedFile("green.json", green.canonical().encodeToByteArray())
        val read = Loads.capsule(file) as Load.Ok<Capsule>
        assertEquals("green.json", read.name)
        assertEquals(green, read.value)
    }

    @Test
    fun aContractFileRoundTrips() {
        val contract = ContractStory.sampleContract(samples().first())!!
        val file = PickedFile("c.json", contract.render().encodeToByteArray())
        assertEquals(contract, (Loads.contract(file) as Load.Ok<Contract>).value)
    }

    @Test
    fun aFileThatIsNotTheRightKindNamesTheFileAndWhatToChoose() {
        val junk = PickedFile("junk.json", "not json".encodeToByteArray())
        assertTrue(
            bad(Loads.capsule(junk)).startsWith("cannot read junk.json: "),
            bad(Loads.capsule(junk)),
        )
        assertTrue(
            bad(Loads.capsule(junk)).endsWith("Choose a capsule file made by drift capture."),
        )
        assertTrue(
            bad(Loads.contract(junk)).endsWith(
                "Choose a contract file made by drift contract create.",
            ),
        )
        val capsule = PickedFile("green.json", green.canonical().encodeToByteArray())
        assertTrue(bad(Loads.contract(capsule)).startsWith("cannot read green.json: "))
    }

    @Test
    fun anUnreadableOrNonTextFileGivesItsReason() {
        assertEquals(
            "cannot read big.json: file is too large. Choose a capsule file made by drift capture.",
            bad(Loads.capsule(PickedFile("big.json", null, "file is too large"))),
        )
        val binary = PickedFile("b.json", byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(bad(Loads.capsule(binary)).startsWith("cannot read b.json: "))
    }
}
