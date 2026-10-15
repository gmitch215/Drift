package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight

private const val SEPARATES = "Separates the failing capsules from the passing ones."

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetScreen(files: FilePicker? = null, samples: List<SetSample> = setSamples()) {
    val entries = remember { mutableStateListOf<SetEntry>() }
    var problem by remember { mutableStateOf<String?>(null) }

    fun add(failing: Boolean) {
        files?.pick(Loads.EXTENSION) {
            when (val read = Loads.capsule(it)) {
                is Load.Ok -> {
                    entries += SetEntry(read.name, failing, read.value)
                    problem = null
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
                "Find What Separates Passing from Failing",
                SET_ABOUT,
                "add passing and failing capsules, or use a sample set; the result appears once " +
                    "the set has both.",
            )
            Section("1. Build the Set") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (files != null) {
                        Choice(
                            "Add a Passing Capsule",
                            null,
                            Modifier.testTag("set:pass"),
                            Role.Button,
                        ) { add(false) }
                        Choice(
                            "Add a Failing Capsule",
                            null,
                            Modifier.testTag("set:fail"),
                            Role.Button,
                        ) { add(true) }
                    }
                    samples.forEachIndexed { i, sample ->
                        Choice(sample.name, null, Modifier.testTag("set:sample:$i"), Role.Button) {
                            entries.clear()
                            entries += sample.entries
                            problem = null
                        }
                    }
                    if (entries.isNotEmpty()) {
                        Choice("Clear the Set", null, Modifier.testTag("set:clear"), Role.Button) {
                            entries.clear()
                            problem = null
                        }
                    }
                }
                if (files == null) Muted(NO_FILES, style = Type.body)
                Problem(problem, "set:problem")
                if (entries.isEmpty()) {
                    Muted("Nothing added yet.", Modifier.testTag("set:count"), Type.body)
                } else {
                    val failing = entries.count { it.failing }
                    val noun = if (entries.size == 1) "capsule" else "capsules"
                    Muted(
                        "${entries.size} $noun: $failing failing, ${entries.size - failing} " +
                            "passing.",
                        Modifier.testTag("set:count"),
                        Type.body,
                    )
                }
                entries.forEachIndexed { i, entry ->
                    val outcome = if (entry.failing) "Failing" else "Passing"
                    StudioText(
                        "$outcome: ${entry.name}",
                        Modifier.testTag("set:entry:$i"),
                        Type.mono,
                    )
                }
            }
            if (entries.isNotEmpty()) {
                val view = remember(entries.toList()) { SetStory.of(entries.toList()) }
                if (view == null) {
                    Panel { StudioText(SetStory.NEED_BOTH, Modifier.testTag("set:headline")) }
                } else {
                    SetOutcome(view)
                }
            }
        }
    }
}

@Composable
private fun SetOutcome(view: SetView) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("Result")
            Heading(view.headline, Modifier.testTag("set:headline"))
            Muted(SetStory.CAVEAT, style = Type.body)
        }
    }
    Section("Setting Values Ranked by How Well They Split the Set (${view.rows.size})") {
        view.rows.forEachIndexed { i, row ->
            Panel(Modifier.testTag("split:$i")) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    StudioText(row.title, weight = FontWeight.SemiBold)
                    StudioText(row.value, style = Type.mono)
                    Muted(row.counts, style = Type.body)
                    if (row.separates) {
                        Muted(SEPARATES, style = Type.body)
                    }
                }
            }
        }
    }
    Disclosure("Technical Details") {
        Panel { StudioText(view.result.text(), Modifier.testTag("set:text"), Type.monoSmall) }
    }
}
