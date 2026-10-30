package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val WHY_ABOUT =
    "Paste an error message, an issue text, a CI log or a saved capture. Drift says what it " +
        "found, what it points at and how sure it is, and what to try next. When it cannot be " +
        "sure it asks for one check at a time, and you paste what the check prints."

const val WHY_ABOUT_HOME =
    "Paste an error message, an issue text or a CI log and talk it through: Drift says what it " +
        "found, how sure it is and which check to run next. Nothing leaves this device."

const val WHY_FOOTER =
    "What you paste is read on this device, is kept only until you start a new conversation " +
        "or close Studio, and is never sent anywhere. Secrets are masked first."

/** The conversation, one panel per message; [chat] outlives the screen so a tab switch keeps it. */
@Suppress("DEPRECATION") // LocalClipboard needs a platform ClipEntry; this one is common
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhyScreen(chat: WhyChat? = null, copy: ((String) -> Unit)? = null) {
    val talk = chat ?: remember { WhyChat() }
    var version by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val put: (String) -> Unit = copy ?: { text -> clipboard.setText(AnnotatedString(text)) }
    fun act(change: () -> Unit) {
        change()
        version++
    }

    val messages = remember(version) { talk.messages.toList() }
    val card = remember(version) { talk.card }
    val lastYou = messages.indexOfLast { it is ChatMessage.You }
    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxSize().background(palette.canvas).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth()
                .padding(horizontal = Space.l, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Intro(
                "Ask Why It Fails",
                WHY_ABOUT,
                "paste what you have in the box below and press Send.",
            )
            messages.forEachIndexed { i, message ->
                when (message) {
                    is ChatMessage.You -> YouPanel(
                        message,
                        i,
                        canReread = i == lastYou,
                    ) { read -> act { talk.reread(read) } }

                    is ChatMessage.Drift -> DriftPanel(message, i)

                    is ChatMessage.Note -> Panel {
                        StudioText(message.text, Modifier.testTag("why:note:$i"), Type.body)
                    }
                }
            }
            card?.let { waiting ->
                CardPanel(
                    waiting,
                    onCopy = { put(waiting.command) },
                    onReply = { text -> act { talk.reply(text, works = false) } },
                    onWorks = { text -> act { talk.reply(text, works = true) } },
                )
            }
            Section("Your Message") {
                PasteField(draft, "why:composer", "Message", "Paste the text here") { draft = it }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Choice("Send", null, Modifier.testTag("why:send"), Role.Button) {
                        val text = draft
                        draft = ""
                        act { talk.send(text) }
                    }
                    Choice("New Conversation", null, Modifier.testTag("why:new"), Role.Button) {
                        draft = ""
                        act { talk.restart() }
                    }
                    Choice(
                        "Export Conversation",
                        null,
                        Modifier.testTag("why:export"),
                        Role.Button,
                    ) { put(talk.export()) }
                }
            }
            Rule()
            Muted(WHY_FOOTER)
        }
    }
}

@Composable
private fun YouPanel(
    message: ChatMessage.You,
    index: Int,
    canReread: Boolean,
    onReread: (Paste) -> Unit,
) {
    Panel(Modifier.testTag("why:message:$index")) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("You")
            StudioText(message.preview, Modifier.testTag("why:you:$index"), Type.monoSmall)
            if (message.hidden > 0) Muted("${message.hidden} more lines are not shown.")
            message.understood?.let {
                Muted(it, Modifier.testTag("why:understood:$index"), Type.body)
            }
            val other = message.rereadAs
            if (canReread && other != null) {
                Choice(
                    other.instead,
                    null,
                    Modifier.testTag("why:reread"),
                    Role.Button,
                ) { onReread(other) }
            }
        }
    }
}

@Composable
private fun DriftPanel(message: ChatMessage.Drift, index: Int) {
    Panel(Modifier.testTag("why:message:$index")) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("Drift")
            StudioText(
                message.verdict,
                Modifier.testTag("why:verdict:$index"),
                Type.body,
                weight = FontWeight.SemiBold,
            )
            Muted(message.certainty, Modifier.testTag("why:certainty:$index"), Type.body)
            if (message.found.isNotEmpty()) {
                Muted("What I Found")
                StudioText(
                    message.found.joinToString("\n"),
                    Modifier.testTag("why:found:$index"),
                    Type.body,
                )
            }
            Muted("What to Try Next")
            StudioText(
                message.next.joinToString("\n"),
                Modifier.testTag("why:next:$index"),
                Type.body,
            )
            message.ci?.let { done ->
                Disclosure("the Ranked Differences") { InvestigationResult(done.state) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardPanel(
    card: AskCard,
    onCopy: () -> Unit,
    onReply: (String) -> Unit,
    onWorks: (String) -> Unit,
) {
    var reply by remember(card) { mutableStateOf("") }
    var next by remember(card) { mutableStateOf("") }
    var works by remember(card) { mutableStateOf("") }
    Panel(Modifier.testTag("why:card")) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Heading("The Check to Run")
            StudioText(card.sentence, Modifier.testTag("why:card:sentence"), Type.body)
            StudioText(card.command, Modifier.testTag("why:card:command"), Type.mono)
            Choice("Copy the Command", null, Modifier.testTag("why:card:copy"), Role.Button) {
                onCopy()
            }
            if (card.reply) {
                Muted("Paste what it prints where the program fails.", style = Type.body)
                PasteField(reply, "why:reply", "Output where it fails", "Paste the output here") {
                    reply = it
                }
                Choice("Send the Answer", null, Modifier.testTag("why:reply:send"), Role.Button) {
                    val text = reply
                    reply = ""
                    onReply(text)
                }
            }
            if (card.works) {
                Muted(
                    "Paste what the same check prints on the machine where it works.",
                    style = Type.body,
                )
                PasteField(works, "why:works", "Output where it works", "Paste the output here") {
                    works = it
                }
                Choice(
                    "This Is From the Machine Where It Works",
                    null,
                    Modifier.testTag("why:works:send"),
                    Role.Button,
                ) {
                    val text = works
                    works = ""
                    onWorks(text)
                }
            }
            card.later?.let { later ->
                Muted(
                    "Or skip that for now and answer the next check: ${later.sentence()}",
                    Modifier.testTag("why:card:later"),
                    Type.body,
                )
                StudioText(later.line, style = Type.mono)
                PasteField(next, "why:reply", "Output where it fails", "Paste the output here") {
                    next = it
                }
                Choice("Send the Answer", null, Modifier.testTag("why:reply:send"), Role.Button) {
                    val text = next
                    next = ""
                    onReply(text)
                }
            }
        }
    }
}

@Composable
private fun PasteField(
    value: String,
    tag: String,
    description: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    BasicTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
            .background(palette.surface, ControlShape)
            .border(1.dp, palette.control, ControlShape)
            .padding(horizontal = Space.m, vertical = Space.m)
            .semantics { contentDescription = description }
            .testTag(tag),
        textStyle = Type.mono.merge(color = palette.text),
        cursorBrush = SolidColor(palette.accentText),
        decorationBox = { field ->
            if (value.isEmpty()) Muted(placeholder, style = Type.mono)
            field()
        },
    )
}
