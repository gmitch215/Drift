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
import androidx.compose.ui.semantics.Role

const val HOME_ABOUT =
    "Drift finds out why a program behaves differently in two environments, for example why a " +
        "CI run failed after it had passed. Studio shows what differed, which difference Drift " +
        "thinks caused the failure, and how sure it is."

const val OPEN_ABOUT =
    "Open a .driftcase file, a saved investigation made by drift solve, and read what it found."

const val SAMPLE_ABOUT =
    "See how Drift ranks the differences between a passing and a failing CI run, using " +
        "recorded runs."

const val ATLAS_ABOUT =
    "Compare how the same Kotlin code behaves on each target, such as the JVM and native."

const val UPGRADE_ABOUT_HOME =
    "Check what an upgrade would break before you make it, using a saved snapshot of your " +
        "environment."

const val CONTRACT_ABOUT_HOME =
    "Check that an environment still meets a contract, the list of things a program needs " +
        "from it."

const val BISECT_ABOUT_HOME =
    "Plan how to narrow a passing and a failing environment down to the settings that matter."

const val NO_PICKER = "Opening a case file is available in the desktop and web apps."

/** The first screen: each way in has one line on what it does. */
@Composable
fun HomeScreen(
    canOpen: Boolean,
    problem: String?,
    onOpen: () -> Unit,
    onSample: () -> Unit,
    onAtlas: () -> Unit,
    onUpgrade: () -> Unit,
    onContract: () -> Unit,
    onBisect: () -> Unit,
    onSet: () -> Unit,
) {
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
            Intro("Welcome to Drift Studio", HOME_ABOUT, "choose one of the options below.")
            if (canOpen) {
                Way("Open a Case", OPEN_ABOUT, "Choose a Case File", "open", onOpen)
            } else {
                Panel { Muted(NO_PICKER, style = Type.body) }
            }
            problem?.let {
                StudioText(it, Modifier.testTag("home-problem"), Type.body, palette.danger)
            }
            Way("Try a Sample", SAMPLE_ABOUT, "Show a Sample", "sample", onSample)
            Way(
                "Check an Upgrade",
                UPGRADE_ABOUT_HOME,
                "Open the Upgrade Check",
                "upgrade",
                onUpgrade,
            )
            Way(
                "Check a Contract",
                CONTRACT_ABOUT_HOME,
                "Open the Contract Check",
                "contract",
                onContract,
            )
            Way("Plan a Bisect", BISECT_ABOUT_HOME, "Open the Bisect Plan", "bisect", onBisect)
            Way("Compare a Set", SET_ABOUT_HOME, "Open the Set View", "set", onSet)
            Way("Browse the Atlas", ATLAS_ABOUT, "Open the Atlas", "atlas", onAtlas)
            Rule()
            Muted(FOOTER)
        }
    }
}

@Composable
private fun Way(title: String, about: String, button: String, tag: String, onClick: () -> Unit) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Heading(title)
            Muted(about, style = Type.body)
            Choice(button, null, Modifier.testTag("home:$tag"), Role.Button, onClick)
        }
    }
}
