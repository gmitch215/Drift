package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.scan.atlas.Dataset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

const val ATLAS_FOOTER =
    "Cells are recorded results for each listed Kotlin version. The device column is measured " +
        "when Studio opens. A difference between targets says nothing yet about a cause."

private const val ABOUT =
    "The same Kotlin code can behave differently on different targets, such as the JVM, native " +
        "and WebAssembly. Each row is a probe with its recorded result on every target, next " +
        "to a measurement of this device."

private const val PREDICT_ABOUT =
    "Each question shows a probe's source and asks what it prints on one target. Pick an " +
        "answer to see the recorded result."

private enum class Mode(val label: String) {
    MATRIX("Compare Targets"),
    PREDICT("Guess the Result"),
}

private enum class Tab(val label: String) {
    HOME("Home"),
    CASE("Case"),
    ATLAS("Atlas"),
    INVESTIGATION("Investigation"),
    UPGRADE("Upgrade"),
    CONTRACT("Contract"),
    BISECT("Bisect"),
}

/** Lets the app ask the platform for a case file; [onOpened] gets the result, good or not. */
fun interface CasePicker {
    fun pick(onOpened: (CaseView) -> Unit)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioApp(
    dataset: Dataset = AtlasData.dataset,
    samples: List<Sample> = samples(),
    measure: (Dataset) -> Device = { Devices.measure(it) },
    dark: Boolean = isSystemInDarkTheme(),
    case: CaseView? = null,
    picker: CasePicker? = null,
    files: FilePicker? = null,
    startOnAtlas: Boolean = false,
) {
    var opened by remember { mutableStateOf(case) }
    var tab by remember {
        mutableStateOf(
            when {
                case != null -> Tab.CASE
                startOnAtlas -> Tab.ATLAS
                else -> Tab.HOME
            },
        )
    }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(case) {
        if (case != null) {
            opened = case
            tab = Tab.CASE
        }
    }
    StudioTheme(dark) {
        val palette = LocalPalette.current
        Column(Modifier.fillMaxSize().background(palette.canvas)) {
            FlowRow(
                Modifier.fillMaxWidth().background(palette.surface)
                    .padding(horizontal = Space.l, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Heading("Drift Studio", Modifier.padding(end = Space.m))
                Tab.entries.filter { it != Tab.CASE || opened != null }.forEach {
                    Choice(it.label, it == tab, role = Role.Tab) { tab = it }
                }
            }
            Rule()
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        canOpen = picker != null,
                        problem = problem,
                        onOpen = {
                            picker?.pick { view ->
                                if (view.story == null && !view.ok) {
                                    val why = view.failure?.text ?: "cannot read ${view.name}"
                                    problem = "$why. Choose a .driftcase file made by drift solve."
                                } else {
                                    problem = null
                                    opened = view
                                    tab = Tab.CASE
                                }
                            }
                        },
                        onSample = { tab = Tab.INVESTIGATION },
                        onAtlas = { tab = Tab.ATLAS },
                        onUpgrade = { tab = Tab.UPGRADE },
                        onContract = { tab = Tab.CONTRACT },
                        onBisect = { tab = Tab.BISECT },
                    )

                    Tab.CASE -> opened?.let { CaseScreen(it) }

                    Tab.ATLAS -> AtlasScreen(dataset, measure)

                    Tab.INVESTIGATION -> StudioScreen(samples)

                    Tab.UPGRADE -> UpgradeScreen(files)

                    Tab.CONTRACT -> ContractScreen(files, samples)

                    Tab.BISECT -> BisectScreen(files, samples)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AtlasScreen(
    dataset: Dataset = AtlasData.dataset,
    measure: (Dataset) -> Device = { Devices.measure(it) },
) {
    var state by remember { mutableStateOf(AtlasState(dataset)) }
    var predict by remember { mutableStateOf(PredictState(dataset)) }
    var mode by remember { mutableStateOf(Mode.MATRIX) }
    var millis by remember { mutableStateOf<Long?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dataset) {
        withFrameNanos { }
        val start = TimeSource.Monotonic.markNow()
        try {
            val device = measure(dataset)
            millis = start.elapsedNow().inWholeMilliseconds
            state = state.copy(device = device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName ?: "unknown error"
        }
    }

    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxSize().background(palette.canvas)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Intro(
                    "Kotlin Across Targets",
                    ABOUT,
                    "pick a Kotlin version, then look for the rows marked as differing and " +
                        "select one to see its values.",
                )
                Muted(Glossary.PROBE, style = Type.body)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Muted("Kotlin Version", Modifier.padding(end = Space.xs), Type.body)
                dataset.versions.forEach {
                    Choice(it, it == state.version, Modifier.testTag("version:$it")) {
                        state = state.copy(version = it, selected = null)
                        predict = predict.withVersion(it)
                    }
                }
                Spacer(Modifier.width(Space.l))
                Mode.entries.forEach {
                    Choice(
                        it.label,
                        it == mode,
                        Modifier.testTag("mode:${it.name.lowercase()}"),
                        Role.Tab,
                    ) { mode = it }
                }
            }
            when (mode) {
                Mode.MATRIX -> MatrixPane(state, millis, failure) { state = it }

                Mode.PREDICT -> Box(Modifier.widthIn(max = ContentWidth)) {
                    PredictPane(predict) { predict = it }
                }
            }
            Rule()
            Muted(ATLAS_FOOTER)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatrixPane(
    state: AtlasState,
    millis: Long?,
    failure: String?,
    update: (AtlasState) -> Unit,
) {
    val palette = LocalPalette.current
    val view = AtlasView.of(state)
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Muted("How Much to Show", Modifier.padding(end = Space.xs), Type.body)
            DetailLevel.entries.forEach {
                Choice(it.label(), it == state.level) { update(state.copy(level = it)) }
            }
            Spacer(Modifier.width(Space.l))
            Choice(
                "Only Differences",
                state.divergentOnly,
                Modifier.testTag("filter:divergent"),
                Role.Checkbox,
            ) { update(state.copy(divergentOnly = !state.divergentOnly)) }
        }
        Heading(
            "${view.divergent} of ${view.total} probes differ between targets " +
                "on kotlin ${view.version}",
            Modifier.testTag("count"),
        )
        if (view.hidden > 0) Muted("${view.hidden} identical probes hidden", style = Type.body)
        DevicePanel(view, millis, failure)
    }
    val scroll = rememberScrollState()
    Column {
        Row(Modifier.fillMaxWidth().padding(top = Space.s)) {
            Spacer(Modifier.width(NAME_WIDTH))
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                view.groups.forEach {
                    Muted(
                        it.name,
                        Modifier.width(CELL_WIDTH * it.targets.size).testTag("group:${it.name}"),
                    )
                }
                Muted("Device", Modifier.width(CELL_WIDTH * 4))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = Space.s)) {
            StudioText("Probe", Modifier.width(NAME_WIDTH), Type.body, weight = FontWeight.SemiBold)
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                view.targets.forEach {
                    StudioText(
                        it,
                        Modifier.width(CELL_WIDTH),
                        Type.monoSmall,
                        weight = FontWeight.SemiBold,
                    )
                }
                StudioText(
                    view.deviceLabel ?: "this device",
                    Modifier.width(CELL_WIDTH).testTag("device-header"),
                    Type.monoSmall,
                    weight = FontWeight.SemiBold,
                )
                StudioText(
                    "Device vs",
                    Modifier.width(CELL_WIDTH * 3),
                    Type.monoSmall,
                    weight = FontWeight.SemiBold,
                )
            }
        }
        Rule()
        var area = ""
        view.rows.forEach { r ->
            if (r.area != area) {
                area = r.area
                Heading(
                    area.replaceFirstChar { it.uppercase() },
                    Modifier.padding(top = Space.l, bottom = Space.xs),
                    Type.body,
                )
            }
            val picked = state.selected == r.id
            Column(if (picked) Modifier.background(palette.accentTint) else Modifier) {
                Row(Modifier.fillMaxWidth()) {
                    Row(Modifier.width(NAME_WIDTH)) {
                        Marker(r.divergent)
                        Column {
                            StudioText(
                                r.name,
                                Modifier.testTag("row:${r.id}").pressable(picked) {
                                    update(state.copy(selected = if (picked) null else r.id))
                                }.heightIn(min = MinTarget).padding(vertical = Space.m),
                                Type.body,
                                weight = if (r.divergent) FontWeight.SemiBold else null,
                            )
                            Muted(r.badge, Modifier.testTag("badge:${r.id}"))
                        }
                    }
                    Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                        r.cells.forEach {
                            StudioText(
                                it.short,
                                Modifier.width(CELL_WIDTH).padding(vertical = Space.xs)
                                    .testTag("cell:${r.id}:${it.target}"),
                                Type.monoSmall,
                            )
                        }
                        val device = r.device?.short.orEmpty()
                        StudioText(
                            device,
                            Modifier.width(CELL_WIDTH).padding(vertical = Space.xs)
                                .testTag("cell:${r.id}:device"),
                            Type.monoSmall,
                        )
                        StudioText(
                            r.deviceVerdict.orEmpty(),
                            Modifier.width(CELL_WIDTH * 3).padding(vertical = Space.xs)
                                .testTag("vs:${r.id}"),
                            Type.small,
                        )
                    }
                }
                r.notes.forEach {
                    Muted(
                        it,
                        Modifier.widthIn(max = ContentWidth)
                            .padding(start = Space.l, bottom = Space.xs),
                    )
                }
            }
            Rule()
        }
    }
    state.selected?.let { id ->
        Section("Selected Probe") {
            Panel {
                Column {
                    AtlasView.drill(state, id).forEach { StudioText(it, style = Type.monoSmall) }
                }
            }
        }
    }
    state.device?.let { Export(it) }
}

@Composable
private fun Marker(divergent: Boolean) {
    val palette = LocalPalette.current
    Box(Modifier.width(Space.l).padding(top = 20.dp)) {
        if (divergent) {
            Box(
                Modifier.size(8.dp).background(palette.warn, BarShape)
                    .semantics { contentDescription = "differs between targets" },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePanel(view: AtlasView, millis: Long?, failure: String?) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Muted("This Device")
            when {
                failure != null -> StudioText(
                    "Could not measure this device: $failure. The recorded columns are unaffected.",
                    Modifier.testTag("device-error"),
                    Type.body,
                    LocalPalette.current.danger,
                )

                view.deviceSummary.isEmpty() ->
                    StudioText("Measuring this device", style = Type.body)

                else -> {
                    val (perTarget, head) = view.deviceSummary.partition { line ->
                        view.targets.any { line.startsWith("$it:") }
                    }
                    StudioText(head.first(), weight = FontWeight.SemiBold)
                    head.drop(1).forEach { Muted(it, style = Type.body) }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.xl),
                        modifier = Modifier.padding(top = Space.xs),
                    ) {
                        perTarget.forEach { Muted(it, style = Type.monoSmall) }
                    }
                    millis?.let { Muted("measured in $it ms") }
                }
            }
        }
    }
}

@Composable
private fun Export(device: Device) {
    val palette = LocalPalette.current
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Muted(
            "The raw measurement of this device, for adding it to the Atlas build.",
            style = Type.body,
        )
        Choice(
            "Show Device Transcript",
            null,
            Modifier.testTag("export"),
            Role.Button,
        ) { shown = true }
        if (shown) {
            BasicTextField(
                device.transcript,
                {},
                Modifier.fillMaxWidth().height(160.dp)
                    .background(palette.surface, ControlShape)
                    .border(1.dp, palette.hairline, ControlShape)
                    .padding(Space.m)
                    .testTag("export-text"),
                readOnly = true,
                textStyle = Type.monoSmall.merge(color = palette.text),
                cursorBrush = SolidColor(palette.accentText),
            )
        }
    }
}

@Composable
private fun PredictPane(state: PredictState, update: (PredictState) -> Unit) {
    val view = PredictView.of(state)
    val score = state.score
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Muted(PREDICT_ABOUT, style = Type.body)
        Muted(
            "Score: ${score.correct} correct, ${score.wrong} wrong, ${score.skipped} skipped",
            Modifier.testTag("score"),
            Type.body,
        )
        if (view == null) {
            Muted(
                "No probe differs between targets on kotlin ${state.version}.",
                style = Type.body,
            )
            return@Column
        }
        Heading("Probe ${view.index + 1} of ${view.count}: ${view.probeId}")
        Panel {
            Column {
                view.source.forEach { StudioText(it, style = Type.monoSmall) }
            }
        }
        StudioText(view.question)
        Heading(view.prompt, Modifier.testTag("prompt"))
        val reveal = view.reveal
        if (reveal == null) {
            view.choices.forEachIndexed { i, c ->
                Panel(Modifier.testTag("choice:$i"), onClick = { update(state.guess(i)) }) {
                    Column {
                        StudioText(c.label, weight = FontWeight.SemiBold)
                        c.lines.forEach { StudioText(it, style = Type.monoSmall) }
                    }
                }
            }
            Choice(
                "I Don't Know",
                null,
                Modifier.testTag("choice:unknown").widthIn(min = 120.dp),
                Role.Button,
            ) { update(state.guess(DONT_KNOW)) }
        } else {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Heading(
                        reveal.verdict.replaceFirstChar { it.uppercase() },
                        Modifier.testTag("verdict"),
                    )
                    reveal.results.forEach { StudioText(it, style = Type.monoSmall) }
                    Muted(reveal.badge, style = Type.body)
                    reveal.notes.forEach { Muted(it, style = Type.body) }
                }
            }
            Choice("Next Probe", null, Modifier.testTag("next"), Role.Button) {
                update(state.next())
            }
        }
    }
}

private val NAME_WIDTH = 260.dp

private val CELL_WIDTH = 160.dp
