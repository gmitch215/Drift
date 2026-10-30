package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.scan.why.WhyFlow
import dev.gmitch215.drift.why.Certainty
import dev.gmitch215.drift.why.MemorySessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhyChatTest {
    private val asking = "The build fails on our server with node and npm.\n"
    private val frame = "TypeError: x\n    at test.js:3:8\n"
    private val missing = "ModuleNotFoundError: No module named 'distutils'"
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

    private fun capture(label: String, node: String, extra: List<Attribute> = emptyList()) =
        Capsule(label, listOf(Attribute("tool.node.version", node, "scanner")) + extra).canonical()

    private fun WhyChat.drifts() = messages.filterIsInstance<ChatMessage.Drift>()

    private fun WhyChat.last() = drifts().last()

    private fun WhyChat.notes() = messages.filterIsInstance<ChatMessage.Note>().map { it.text }

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

    @Test
    fun anIssueTextThatLeavesAToolOpenAsksForOneCheckInACard() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        assertEquals(2, chat.messages.size)
        val you = assertIs<ChatMessage.You>(chat.messages[0])
        assertEquals("I read this as an issue text.", you.understood)
        val card = assertNotNull(chat.card)
        assertEquals("node --version", card.command)
        assertTrue(card.reply && card.works)
        assertEquals(
            "To tell, run node --version where it fails and paste the result: the report " +
                "names Node but not which version fails.",
            card.sentence,
        )
        val said = chat.last()
        assertTrue(said.next.none { "drift why --reply" in it }, said.next.toString())
        assertFalse("npm --version" in flat(said.next.joinToString(" ")))
    }

    @Test
    fun theAnswersWalkTheChecksFromTheFailingSideToTheWorkingSide() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        chat.reply("v18.19.0\n", works = false)
        val first = chat.last()
        assertTrue(
            "Node version: not stated where it works, 18.19.0 where it fails" in
                flat(first.found.joinToString(" ")),
            first.found.toString(),
        )
        val waiting = assertNotNull(chat.card)
        assertTrue(!waiting.reply && waiting.works, "only the works side is left")
        assertEquals("npm --version", waiting.later?.line)
        chat.reply("v20.11.1\n", works = true)
        val done = chat.last()
        assertTrue(
            "Node version: 20.11.1 where it works, 18.19.0 where it fails" in
                flat(done.found.joinToString(" ")),
            done.found.toString(),
        )
        assertFalse("Assuming" in done.verdict)
        assertNull(chat.card)
    }

    @Test
    fun aWorksAnswerThatIsEqualSaysSoAndMovesOnToTheNextCheck() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        chat.reply("v18.19.0\n", works = false)
        chat.reply("v18.19.0\n", works = true)
        assertEquals(
            "Node version is the same where it works and where it fails, so it is not what " +
                "differs.",
            chat.last().verdict,
        )
        assertEquals("npm --version", chat.card?.command)
    }

    @Test
    fun theWorksSideCanComeFirst() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        chat.reply("v20.11.1\r\n", works = true)
        val waiting = assertNotNull(chat.card)
        assertTrue(waiting.reply && !waiting.works, "the failing side is left")
        chat.reply("v18.19.0", works = false)
        assertTrue("20.11.1 where it works" in flat(chat.last().found.joinToString(" ")))
    }

    @Test
    fun theSameInputsGiveTheSameAnswersAsTheCommandLogic() {
        val inputs = listOf(
            asking to Paste.ISSUE,
            frame to Paste.ISSUE,
            frame to Paste.ERROR,
            "It is broken, please fix.\n" to Paste.ISSUE,
            missing to Paste.ERROR,
            "TypeError: Object.groupBy is not a function\n    at test.js:3:8\n" to Paste.ISSUE,
        )
        for ((text, read) in inputs) {
            val chat = WhyChat()
            chat.send(text, read)
            val view = WhyFlow.report("x", text, self = read == Paste.ERROR).view
            val said = chat.last()
            assertEquals(view.verdict, said.verdict, text)
            assertEquals(view.evidence, said.found, text)
            assertEquals(WhyChat.certainty(view.certainty, view.kind), said.certainty, text)
        }
    }

    @Test
    fun anErrorMessageIsNamedByTheKindOfErrorItIs() {
        fun reading(text: String): String? {
            val chat = WhyChat()
            chat.send(text)
            return (chat.messages.first() as ChatMessage.You).understood
        }
        assertEquals("I read this as a stack trace.", reading(frame))
        assertEquals(
            "I read this as a Python traceback.",
            reading("Traceback (most recent call last):\n  File \"a.py\", line 1\nValueError: x\n"),
        )
        assertEquals("I read this as an error message.", reading(missing))
        assertEquals("I read this as an issue text.", reading("## Steps\n\nRun the build.\n"))
        assertEquals("I read this as an issue text.", reading("It fails:\n```\nboom\n```\n"))
    }

    @Test
    fun aMissingModuleAsksForTheRuntimeThenTheCaptureAndTheCaptureAnswersIt() {
        val chat = WhyChat()
        chat.send(missing)
        assertEquals("python3 --version", chat.card?.command)
        assertTrue(chat.last().next.any { "paste a capture of each" in it })
        chat.reply("Python 3.11.4\n", works = false)
        assertEquals("capture", chat.card?.later?.family)
        chat.send(capture("there", "18.19.0"))
        assertEquals(3, chat.drifts().size)
        assertTrue(
            chat.messages.filterIsInstance<ChatMessage.You>().last().understood!!
                .startsWith("I read this as the output of drift why --save there.json"),
        )
    }

    @Test
    fun theNotEnvironmentVerdictKeepsItsHedgedWording() {
        val chat = WhyChat()
        chat.send(frame, Paste.ISSUE)
        val said = chat.last()
        assertEquals(
            "This does not look like an environment difference. The report points at your own " +
                "code (test.js:3): start there.",
            said.verdict,
        )
        assertEquals("A hint only: Drift is not certain of this.", said.certainty)
        val named = WhyChat()
        named.send("TypeError: Object.groupBy is not a function\n    at test.js:3:8\n", Paste.ISSUE)
        assertFalse("not an environment difference" in flat(named.last().verdict))
    }

    @Test
    fun everyCertaintyHasItsOwnPlainWords() {
        val words = (Certainty.entries.map { it.id } + "fact").map { WhyChat.certainty(it, null) }
        assertEquals(words.size, words.toSet().size)
        assertTrue(words.all { it.isNotBlank() && "capsule" !in it && "attribute" !in it })
        assertTrue(WhyChat.certainty("cause", null).startsWith("Cause"))
        assertTrue(WhyChat.certainty("likely", null).startsWith("Likely"))
        assertTrue(WhyChat.certainty("one-of", null).startsWith("One of several"))
        assertTrue(WhyChat.certainty("tie", null).startsWith("Tied"))
        assertTrue(WhyChat.certainty("none", null).startsWith("Not sure"))
    }

    @Test
    fun aMainBoxPasteThatIsTheWaitingChecksOutputAnswersTheCheck() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        chat.send("v18.19.0\n")
        val you = chat.messages.filterIsInstance<ChatMessage.You>().last()
        assertEquals(
            "I read this as the output of node --version from the machine where it fails.",
            you.understood,
        )
        assertTrue("18.19.0 where it fails" in flat(chat.last().found.joinToString(" ")))
    }

    @Test
    fun aReplyWithNoConversationIsCarriedBackWithANote() {
        val chat = WhyChat()
        chat.send("v18.19.0\n")
        assertEquals(listOf(WHY_NO_CONVERSATION), chat.notes())
        assertTrue(chat.drifts().isEmpty())
        chat.reply("v18.19.0\n", works = true)
        assertEquals(WHY_NO_CONVERSATION, chat.notes().last())
        val ended = WhyChat()
        ended.send("It is broken, please fix.\n", Paste.ISSUE)
        ended.reply("v18.19.0\n", works = false)
        assertEquals(WHY_NO_CONVERSATION, ended.notes().last())
    }

    @Test
    fun anAnswerThatIsNotTheChecksOutputKeepsTheCheckOpen() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        chat.reply("command not found: node\n", works = false)
        assertTrue(
            "I could not read that as the output of node --version" in chat.notes().last(),
            chat.notes().toString(),
        )
        assertEquals("node --version", chat.card?.command)
        chat.reply("v18.19.0\n", works = false)
        assertTrue("18.19.0 where it fails" in flat(chat.last().found.joinToString(" ")))
    }

    @Test
    fun aWrongReadingCanBeRedoneAsTheOtherKind() {
        val chat = WhyChat()
        chat.send(asking)
        val you = chat.messages.first() as ChatMessage.You
        assertEquals("I read this as an error message.", you.understood)
        assertEquals(Paste.ISSUE, you.rereadAs)
        val size = chat.messages.size
        chat.reread(Paste.ISSUE)
        assertEquals(size, chat.messages.size)
        assertEquals(
            "I read this as an issue text.",
            (chat.messages.first() as ChatMessage.You).understood,
        )
        assertEquals("node --version", chat.card?.command)
        val direct = WhyChat()
        direct.send(asking, Paste.ISSUE)
        assertEquals(direct.last().verdict, chat.last().verdict)
        assertEquals(direct.last().found, chat.last().found)
    }

    @Test
    fun aCiLogPairRunsTheCiDiagnosisInEitherOrder() {
        val passing = github("1.0", failed = false)
        val failing = github("2.0", failed = true)
        val expected = (CiDiagnosis.run(passing, failing, null, null) as CiResult.Done)
        for ((first, second) in listOf(failing to passing, passing to failing)) {
            val chat = WhyChat()
            chat.send(first)
            assertTrue(chat.drifts().isEmpty())
            assertTrue("Paste the log of a run that" in chat.notes().single())
            chat.send(second)
            val said = chat.last()
            assertEquals(Plain.reading(expected.state).headline, said.verdict)
            assertNotNull(said.ci)
            assertTrue("CI setting runner.image.version" in said.verdict, said.verdict)
        }
        val chat = WhyChat()
        chat.send(failing)
        val you = chat.messages.first() as ChatMessage.You
        assertEquals("I read this as a GitHub Actions log of a run that failed.", you.understood)
        chat.send(passing)
        val second = chat.messages.filterIsInstance<ChatMessage.You>().last()
        assertEquals(
            "I read this as a GitHub Actions log of the run that passed.",
            second.understood,
        )
    }

    @Test
    fun twoLogsFromDifferentCiSystemsAreRefusedAndTheFirstStaysHeld() {
        val chat = WhyChat()
        chat.send(github("1.0", failed = false))
        chat.send("Running with gitlab-runner 16.0.0 (abc)\nsome output\n")
        assertTrue("different CI systems" in chat.notes().last(), chat.notes().toString())
        assertTrue(chat.drifts().isEmpty())
        chat.send(github("2.0", failed = true))
        assertNotNull(chat.last().ci)
    }

    @Test
    fun twoCapturesAreComparedTheFirstIsWhereItFails() {
        val chat = WhyChat()
        chat.send(capture("a", "20.11.1"))
        assertEquals(WHY_SECOND_CAPTURE, chat.notes().last())
        val first = chat.messages.first() as ChatMessage.You
        assertEquals(
            "I read this as the capture of the machine where it fails.",
            first.understood,
        )
        chat.send(capture("b", "22.4.0"))
        val said = chat.last()
        assertEquals(
            listOf("Node version: 22.4.0 where it works, 20.11.1 where it fails"),
            said.found.take(1),
        )
        assertTrue(said.certainty.startsWith("Cause") || said.certainty.startsWith("Likely"))
        assertEquals(
            "I read this as the capture of the machine where it works.",
            chat.messages.filterIsInstance<ChatMessage.You>().last().understood,
        )
    }

    @Test
    fun aCaptureThatLacksAFamilyTheOtherHasLeavesItOutAndSaysSo() {
        val chat = WhyChat()
        val limit = listOf(Attribute("limits.nofile", "1024:1024", "scanner"))
        chat.send(capture("a", "20.11.1"))
        chat.send(capture("b", "22.4.0", limit))
        val said = chat.last()
        assertTrue(said.next.any { "has no limits facts" in it }, said.next.toString())
    }

    @Test
    fun emptyAndOversizedPastesAreRefusedAndNeverEchoed() {
        val chat = WhyChat()
        chat.send("  \n ")
        chat.reply("", works = false)
        assertEquals(listOf(WHY_EMPTY, WHY_EMPTY), chat.notes())
        chat.send("x".repeat(WHY_LIMIT + 1))
        assertEquals(WHY_TOO_BIG, chat.notes().last())
        assertTrue(chat.messages.none { it is ChatMessage.You })
        assertEquals(16 * 1024 * 1024, WHY_LIMIT)
        chat.send("fails with node and npm.\n")
        chat.reply("y".repeat(WHY_LIMIT + 1), works = true)
        assertEquals(WHY_TOO_BIG, chat.notes().last())
    }

    @Test
    fun aLongPasteIsShownAsAShortMaskedPreview() {
        val chat = WhyChat()
        val lines = (1..40).joinToString("\n") { "line $it with a token $token" }
        chat.send(lines)
        val you = chat.messages.first() as ChatMessage.You
        assertEquals(8, you.preview.lines().size)
        assertEquals(32, you.hidden)
        assertFalse(token in you.preview)
    }

    @Test
    fun aSecretInAPasteIsNeverEchoedKeptOrExported() {
        val env = listOf("  API_TOKEN: $token", "  MODE: one")
        val store = MemorySessionStore()
        val chat = WhyChat(store)
        chat.send("Fails with node. Token $token was in the log.\n", Paste.ISSUE)
        chat.reply("Python $token\n", works = false)
        chat.send(github("1.0", failed = false, env = env))
        chat.send(github("2.0", failed = true, env = env))
        val shown = chat.export() + store.load()?.text().orEmpty()
        assertFalse(token in shown, shown)
        assertFalse("ghp_" in shown, shown)
        val again = WhyChat(store)
        again.send("Fails with node. Token $token was in the log.\n", Paste.ISSUE)
        assertFalse(token in store.load()!!.text())
        assertFalse(token in again.export())
    }

    @Test
    fun aNewConversationForgetsTheSessionTheCardAndTheHeldLog() {
        val store = MemorySessionStore()
        val chat = WhyChat(store)
        chat.send(asking, Paste.ISSUE)
        assertNotNull(store.load())
        chat.send(github("1.0", failed = false))
        chat.restart()
        assertTrue(chat.messages.isEmpty())
        assertNull(chat.card)
        assertNull(store.load())
        chat.send(github("2.0", failed = true))
        assertTrue(chat.drifts().isEmpty(), "the held log did not survive the restart")
    }

    @Test
    fun theExportHoldsTheConversationAndTheCheckToRunButNoCommandLineSteps() {
        val chat = WhyChat()
        chat.send(asking, Paste.ISSUE)
        chat.reply("v18.19.0\n", works = false)
        val text = chat.export()
        assertTrue(text.startsWith("Drift conversation\n"))
        assertTrue("You:\nThe build fails on our server" in text, text)
        assertTrue("Drift:" in text && "What I found:" in text, text)
        assertTrue("Check to run: node --version" in text, text)
        assertFalse("drift why --reply" in text, text)
        assertFalse(Regex("capsule|attribute", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }
}
