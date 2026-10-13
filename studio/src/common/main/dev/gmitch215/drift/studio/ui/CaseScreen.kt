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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight

const val CASE_ABOUT =
    "A case is a saved investigation. Drift compared a passing and a failing environment, then " +
        "re-ran the failing command with differences changed one at a time. Studio reads that " +
        "record and does not re-run anything."

@Composable
fun CaseScreen(case: CaseView) {
    val palette = LocalPalette.current
    val story = case.story
    Box(
        Modifier.fillMaxSize().background(palette.canvas).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth()
                .padding(horizontal = Space.l, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Intro(
                    "Case Summary",
                    CASE_ABOUT,
                    if (story == null) {
                        "open the technical details to see why this file could not be summarized."
                    } else {
                        "read the result, then try the step under What to Try."
                    },
                )
                Muted(case.name, style = Type.mono)
            }
            if (story == null) {
                Panel {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Heading(
                            "Drift Could Not Summarize This File",
                            Modifier.testTag("case-headline"),
                        )
                        Muted(
                            "The file may be damaged, or it may not be a solved case.",
                            style = Type.body,
                        )
                    }
                }
            } else {
                Panel {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Muted("What Drift Found")
                        Heading(story.headline, Modifier.testTag("case-headline"))
                        Muted("How Sure Drift Is", Modifier.padding(top = Space.s))
                        StudioText(story.sureness, Modifier.testTag("case-sureness"))
                        Muted("What to Try", Modifier.padding(top = Space.s))
                        StudioText(story.next, Modifier.testTag("case-next"))
                    }
                }
                if (story.differences.isNotEmpty()) {
                    Section("What Differed") {
                        Muted(
                            "Settings that differ between the passing and the failing " +
                                "environment. The cause is listed first.",
                            style = Type.body,
                        )
                        Panel {
                            Column(
                                Modifier.testTag("case-differences"),
                                verticalArrangement = Arrangement.spacedBy(Space.m),
                            ) {
                                story.differences.forEach { DifferenceRow(it) }
                            }
                        }
                    }
                }
            }
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Muted("File Check")
                    val failure = case.failure
                    Heading(
                        if (failure == null) {
                            "This file is intact: every check passed"
                        } else {
                            "This file did not pass its checks"
                        },
                        Modifier.testTag("case-status"),
                    )
                    if (failure != null) {
                        Muted(
                            "Treat the result above with care; the technical details show " +
                                "which check failed.",
                            style = Type.body,
                        )
                    }
                }
            }
            Disclosure("Technical Details") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    case.verdict?.let {
                        Section("Recorded Verdict") { StudioText(it, style = Type.body) }
                    }
                    if (story != null && story.experiments.isNotEmpty()) {
                        Section("Experiments") {
                            Panel {
                                Column(Modifier.testTag("case-experiments")) {
                                    story.experiments.forEach {
                                        StudioText(it, style = Type.monoSmall)
                                    }
                                }
                            }
                        }
                    }
                    Section("Checks") {
                        Panel {
                            Column(Modifier.testTag("case-checks")) {
                                case.lines.forEach {
                                    StudioText(
                                        it.text,
                                        style = Type.monoSmall,
                                        color = if (it.failed) palette.danger else palette.text,
                                        weight = if (it.failed) FontWeight.SemiBold else null,
                                    )
                                }
                            }
                        }
                    }
                    if (story != null && story.notes.isNotEmpty()) {
                        Section("Limits of This Result") {
                            story.notes.forEach { Muted(it, style = Type.body) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DifferenceRow(row: Difference) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        StudioText(Plain.title(row.path), weight = FontWeight.SemiBold)
        StudioText(
            "${Plain.value(row.before)} -> ${Plain.value(row.after)}",
            style = Type.mono,
        )
        Muted(row.path + if (row.cause) "  (named as the cause)" else "", style = Type.monoSmall)
    }
}
