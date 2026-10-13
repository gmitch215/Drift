package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role

const val NO_FILES = "Choosing a file is available in the desktop and web apps; use a sample here."

/** One input: its title, the chooser button, sample buttons and what is chosen so far. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileSlot(
    title: String,
    tag: String,
    chosen: String?,
    canPick: Boolean,
    samples: List<Pair<String, () -> Unit>>,
    onPick: () -> Unit,
) {
    Section(title) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (canPick) {
                Choice(
                    "Choose a File",
                    null,
                    Modifier.testTag("$tag:pick"),
                    Role.Button,
                    onPick,
                )
            }
            samples.forEachIndexed { i, (label, use) ->
                Choice(label, null, Modifier.testTag("$tag:sample:$i"), Role.Button, use)
            }
        }
        if (!canPick) Muted(NO_FILES, style = Type.body)
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (chosen == null) {
                Muted("Nothing chosen yet.", Modifier.testTag("$tag:chosen"), Type.body)
            } else {
                StudioText("Chosen: $chosen", Modifier.testTag("$tag:chosen"), Type.mono)
            }
        }
    }
}

/** A failure to read a chosen file, in the danger color. */
@Composable
internal fun Problem(text: String?, tag: String) {
    if (text != null) {
        StudioText(text, Modifier.testTag(tag), Type.body, LocalPalette.current.danger)
    }
}
