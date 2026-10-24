package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.studio.StudioState

const val FOOTER = "Shows recorded captures from public CI runs; nothing here is a live diagnosis."

const val INVESTIGATION_ABOUT =
    "These are recorded CI runs, one passing and one failing. Drift lists what differs between " +
        "their environments and ranks the differences by how likely each is to explain the " +
        "failure. Each run was saved as a capsule."

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(samples: List<Sample> = samples()) {
    var sampleIndex by remember { mutableStateOf(0) }
    val sample = samples[sampleIndex]

    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxSize().background(palette.canvas)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth()
                .padding(horizontal = Space.l, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Intro(
                "What Changed Between Two Runs",
                INVESTIGATION_ABOUT,
                "read the result below, then select a difference to see the evidence behind it.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Muted("Failing Run to Explain", style = Type.small)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    samples.forEachIndexed { i, s ->
                        Choice(s.name, i == sampleIndex) { sampleIndex = i }
                    }
                }
                Muted("Each is compared with passing run $GREEN_RUN.", style = Type.small)
            }
            key(sampleIndex) { InvestigationResult(sample.state) }
            Rule()
            Muted(FOOTER)
        }
    }
}

/** The ranked result for one pair of capsules; it keeps its own selection and detail level. */
@Composable
internal fun InvestigationResult(state: StudioState, more: @Composable () -> Unit = {}) {
    var showAll by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val view = StudioView.of(state, if (showAll) DetailLevel.DETAIL else DetailLevel.SUMMARY)
    val reading = Plain.reading(state)
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("Result")
            Heading(reading.headline, Modifier.testTag("summary"))
            reading.evidence?.let { StudioText(it, Modifier.testTag("evidence")) }
            Muted(reading.caution, style = Type.body)
        }
    }
    state.performance?.ranges?.takeIf { it.isNotEmpty() }?.let {
        Section("Run Time and Memory Ranges") { RangeChart(it) }
    }
    Section("Differences Ranked by Evidence") {
        Muted(Glossary.TIER, style = Type.body)
        if (view.candidates.isEmpty()) {
            Muted("No difference between these runs is ranked.", style = Type.body)
        }
        view.candidates.forEach { row ->
            CandidateItem(state, row, row.path == selected) {
                selected = if (selected == row.path) null else row.path
            }
        }
        if (view.hidden > 0 || showAll) {
            val label = "Show ${view.hidden} More Differences"
            Choice(
                if (showAll) "Show Fewer Differences" else label,
                null,
                Modifier.testTag("show-all"),
                Role.Button,
            ) { showAll = !showAll }
        }
    }
    Disclosure("Technical Details") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Section("How Drift Narrowed It Down") {
                val funnel = StudioView.of(state).funnel
                Muted(
                    "Of ${funnel[0].count} facts in the capsules, ${funnel[1].count} " +
                        "differ, ${funnel[2].count} can be ranked and " +
                        "${funnel[3].count} are tied at the top.",
                    style = Type.body,
                )
                FunnelPlot(StudioView.of(state))
            }
            val extras = StudioView.of(state, DetailLevel.FULL).extras
            if (extras.isNotEmpty()) {
                Section("Other Findings") {
                    Panel {
                        Column {
                            extras.forEach { StudioText(it, style = Type.monoSmall) }
                        }
                    }
                }
            }
            more()
        }
    }
}

internal fun DetailLevel.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun CandidateItem(
    state: StudioState,
    row: CandidateRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val candidate = state.relevant.first { it.path == row.path }
    val diff = state.changed.changes.firstOrNull { it.path == row.path }
    Panel(Modifier.testTag("candidate:${row.path}"), selected, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            StudioText(Plain.title(row.path), weight = FontWeight.SemiBold)
            diff?.let { StudioText(Plain.change(it), style = Type.mono) }
            Muted(Plain.level(candidate.tier), style = Type.body)
            if (selected) {
                StudioText(Plain.tier(candidate.tier).phrase)
                candidate.matches.forEach {
                    StudioText("Rule: ${it.title}", weight = FontWeight.SemiBold)
                    Muted(it.mechanism, style = Type.body)
                }
                if (candidate.matches.isNotEmpty()) Muted(Glossary.RULE, style = Type.body)
                Muted("Technical Details", Modifier.padding(top = Space.s))
                StudioView.drill(state, row.path).forEach {
                    StudioText(it, style = Type.monoSmall)
                }
            }
        }
    }
}

@Composable
fun FunnelPlot(view: StudioView) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        view.funnel.forEach { stage ->
            Row(
                Modifier.fillMaxWidth().testTag("funnel:${stage.label}")
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${stage.label}: ${stage.count}"
                    },
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Muted(
                    stage.label.replaceFirstChar { it.uppercase() },
                    Modifier.widthIn(min = 80.dp),
                    Type.body,
                )
                Box(Modifier.weight(1f)) {
                    val share =
                        if (view.maxCount == 0) 0f else stage.count.toFloat() / view.maxCount
                    Box(
                        Modifier.fillMaxWidth(share).height(8.dp)
                            .background(palette.accent, BarShape),
                    )
                }
                StudioText(
                    stage.count.toString(),
                    Modifier.widthIn(min = 48.dp),
                    Type.mono,
                    weight = FontWeight.SemiBold,
                )
            }
        }
    }
}
