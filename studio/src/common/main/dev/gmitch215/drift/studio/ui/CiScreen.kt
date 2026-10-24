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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.redact.Redactor

const val CI_ABOUT =
    "Drift reads the log of a passing CI run and of a failing one, finds the runner " +
        "environment in each, and ranks the differences by how likely each is to explain the " +
        "failure. It reads GitHub Actions and GitLab CI logs. Everything happens on this " +
        "device, and secrets are masked before anything is shown."

const val CI_SAMPLE_ABOUT =
    "The sample is a pair of recorded runs of one public project's CI workflow."

const val CI_FOOTER = "Logs are read on this device and are not sent anywhere."

private const val ANY_FILE = ""

private class LogSide(val text: String = "", val source: String? = null)

private fun kept(text: String, source: String? = null) = LogSide(Redactor.redactText(text), source)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CiScreen(files: FilePicker? = null, sample: () -> CiSample = ::ciSample) {
    var passing by remember { mutableStateOf(LogSide()) }
    var failing by remember { mutableStateOf(LogSide()) }
    var job by remember { mutableStateOf("") }
    var format by remember { mutableStateOf<CiFormat?>(null) }
    var askFormat by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CiResult?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun reset() {
        result = null
        problem = null
        askFormat = false
        format = null
    }

    fun diagnose() {
        when (val read = CiDiagnosis.run(passing.text, failing.text, format, job)) {
            is CiResult.Bad -> {
                result = null
                problem = read.message
                askFormat = askFormat || read.askFormat
            }

            is CiResult.Done -> {
                problem = null
                result = read
            }
        }
    }

    fun pick(use: (LogSide) -> Unit) {
        files?.pick(ANY_FILE) {
            when (val read = CiDiagnosis.read(it)) {
                is Load.Ok -> {
                    reset()
                    use(kept(read.value, read.name))
                }

                is Load.Bad -> problem = read.message
            }
        }
    }

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
                "Diagnose My CI",
                CI_ABOUT,
                "choose or paste both logs and press Diagnose These Logs, or try the sample logs.",
            )
            Section("Sample Logs") {
                Muted(CI_SAMPLE_ABOUT, style = Type.body)
                Choice(
                    "Try With Sample Logs",
                    null,
                    Modifier.testTag("ci:sample"),
                    Role.Button,
                ) {
                    val s = sample()
                    reset()
                    passing = kept(s.passing, "sample passing log")
                    failing = kept(s.failing, "sample failing log")
                    job = s.job
                    diagnose()
                }
            }
            LogSlot("1. Passing Run Log", "ci-pass", passing, files != null, {
                reset()
                passing = kept(it)
            }) { pick { side -> passing = side } }
            LogSlot("2. Failing Run Log", "ci-fail", failing, files != null, {
                reset()
                failing = kept(it)
            }) { pick { side -> failing = side } }
            Section("3. Job Name (Optional)") {
                Muted(
                    "Only for a GitHub Actions log that holds more than one job. Leave it " +
                        "empty to use the first job with a runner environment.",
                    style = Type.body,
                )
                LogField(job, "ci:job", "Job name", "Job name", singleLine = true) {
                    reset()
                    job = it
                }
            }
            Choice(
                "Diagnose These Logs",
                null,
                Modifier.testTag("ci:run"),
                Role.Button,
            ) { diagnose() }
            Problem(problem, "ci:problem")
            if (askFormat) {
                Section("Which CI System Made the Logs?") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        CiFormat.entries.forEach { choice ->
                            Choice(
                                choice.label,
                                format == choice,
                                Modifier.testTag("ci:format:${choice.name.lowercase()}"),
                            ) {
                                format = choice
                                diagnose()
                            }
                        }
                    }
                }
            }
            (result as? CiResult.Done)?.let { done ->
                if (!done.separated) {
                    Panel { StudioText(CI_LIMIT, Modifier.testTag("ci:limit"), Type.body) }
                }
                InvestigationResult(done.state) {
                    Section("Plan to Narrow It Down") {
                        Panel {
                            StudioText(done.plan, Modifier.testTag("ci:plan"), Type.monoSmall)
                        }
                    }
                }
            }
            Rule()
            Muted(CI_FOOTER)
        }
    }
}

@Composable
private fun LogSlot(
    title: String,
    tag: String,
    side: LogSide,
    canPick: Boolean,
    onText: (String) -> Unit,
    onPick: () -> Unit,
) {
    val seen = remember(side.text) { CiDiagnosis.detect(side.text) }
    FileSlot(
        title,
        tag,
        side.source ?: if (side.text.isBlank()) null else "pasted text",
        canPick,
        emptyList(),
        extra = {
            LogField(
                if (side.source == null) side.text else "",
                "$tag:text",
                title.substringAfter(". "),
                if (side.source == null) {
                    "Paste the log here"
                } else {
                    "Using ${side.source}. Paste here to replace it."
                },
                onChange = onText,
            )
            seen?.let { Muted("Looks like ${it.label}.", Modifier.testTag("$tag:format")) }
        },
        onPick = onPick,
    )
}

@Composable
private fun LogField(
    value: String,
    tag: String,
    description: String,
    placeholder: String,
    singleLine: Boolean = false,
    onChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    BasicTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().heightIn(min = if (singleLine) MinTarget else 120.dp, max = 240.dp)
            .background(palette.surface, ControlShape)
            .border(1.dp, palette.control, ControlShape)
            .padding(horizontal = Space.m, vertical = Space.m)
            .semantics { contentDescription = description }
            .testTag(tag),
        singleLine = singleLine,
        textStyle = Type.mono.merge(color = palette.text),
        cursorBrush = SolidColor(palette.accentText),
        decorationBox = { field ->
            if (value.isEmpty()) Muted(placeholder, style = Type.mono)
            field()
        },
    )
}
