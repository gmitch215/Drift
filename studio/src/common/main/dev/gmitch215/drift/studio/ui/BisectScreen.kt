package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import dev.gmitch215.drift.model.Capsule

@Composable
fun BisectScreen(files: FilePicker? = null, samples: List<Sample> = samples()) {
    var green by remember { mutableStateOf<Load.Ok<Capsule>?>(null) }
    var red by remember { mutableStateOf<Load.Ok<Capsule>?>(null) }
    var greenProblem by remember { mutableStateOf<String?>(null) }
    var redProblem by remember { mutableStateOf<String?>(null) }

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
                "Plan a Bisect",
                BISECT_ABOUT,
                "choose a passing and a failing capsule; the plan appears when both are chosen.",
            )
            FileSlot(
                "1. Choose the Passing Capsule",
                "green",
                green?.name,
                files != null,
                listOf<Pair<String, () -> Unit>>(
                    "Use Passing Run $GREEN_RUN" to {
                        green = Load.Ok("Passing Run $GREEN_RUN", samples.first().green)
                        greenProblem = null
                    },
                ),
            ) {
                files?.pick(Loads.EXTENSION) {
                    when (val read = Loads.capsule(it)) {
                        is Load.Ok -> {
                            green = read
                            greenProblem = null
                        }

                        is Load.Bad -> greenProblem = read.message
                    }
                }
            }
            Problem(greenProblem, "green:problem")
            FileSlot(
                "2. Choose the Failing Capsule",
                "red",
                red?.name,
                files != null,
                samples.map { s ->
                    "Use ${s.name}" to {
                        red = Load.Ok(s.name, s.red)
                        redProblem = null
                    }
                },
            ) {
                files?.pick(Loads.EXTENSION) {
                    when (val read = Loads.capsule(it)) {
                        is Load.Ok -> {
                            red = read
                            redProblem = null
                        }

                        is Load.Bad -> redProblem = read.message
                    }
                }
            }
            Problem(redProblem, "red:problem")
            val passing = green
            val failing = red
            if (passing != null && failing != null) {
                val view =
                    remember(passing, failing) { BisectStory.of(passing.value, failing.value) }
                if (view == null) {
                    Panel {
                        StudioText(BisectStory.NONE, Modifier.testTag("bisect:headline"))
                    }
                } else {
                    BisectOutcome(view)
                }
            }
        }
    }
}

@Composable
private fun BisectOutcome(view: BisectView) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("Result")
            Heading(view.headline, Modifier.testTag("bisect:headline"))
        }
    }
    Section("Settings That Differ (${view.settings.size})") {
        Muted(BisectStory.ORDER, style = Type.body)
        view.settings.forEachIndexed { i, row -> SettingItem(row, Modifier.testTag("setting:$i")) }
    }
    view.first?.let { test ->
        Section("The First Test") {
            StudioText(test.sentence, Modifier.testTag("bisect:test"))
            test.settings.forEachIndexed { i, row ->
                SettingItem(row, Modifier.testTag("test-setting:$i"))
            }
            Muted(BisectStory.NOT_RUN, style = Type.body)
            Panel {
                Column(Modifier.testTag("bisect:commands")) {
                    test.commands.forEach { StudioText(it, style = Type.monoSmall) }
                }
            }
            Muted(BisectStory.OUTCOME, style = Type.body)
        }
    }
    Disclosure("Technical Details") {
        Panel {
            StudioText(view.plan.canonical(), Modifier.testTag("bisect:json"), Type.monoSmall)
        }
    }
}

@Composable
private fun SettingItem(row: SettingRow, modifier: Modifier) {
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            StudioText(row.title, weight = FontWeight.SemiBold)
            StudioText(row.change, style = Type.mono)
        }
    }
}
