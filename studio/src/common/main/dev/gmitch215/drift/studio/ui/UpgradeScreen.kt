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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.model.Capsule
import dev.gmitch215.drift.upgrade.Finding
import dev.gmitch215.drift.upgrade.Group
import dev.gmitch215.drift.upgrade.Targets

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpgradeScreen(files: FilePicker? = null, samples: List<Load.Ok<Capsule>> = upgradeSamples()) {
    var capsule by remember { mutableStateOf<Load.Ok<Capsule>?>(null) }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UpgradeCheckResult?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun run(value: String) {
        text = value
        result = capsule?.let { UpgradeStory.check(it.value, value) }
    }

    fun choose(next: Load.Ok<Capsule>) {
        capsule = next
        problem = null
        result = null
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
                "Check an Upgrade",
                UPGRADE_ABOUT,
                "choose a capsule, type the versions to move to, then press Check the Upgrade.",
            )
            FileSlot(
                "1. Choose a Capsule",
                "upgrade",
                capsule?.name,
                files != null,
                samples.map { s -> "Use the Sample ${s.value.label}" to { choose(s) } },
            ) {
                files?.pick(Loads.EXTENSION) {
                    when (val read = Loads.capsule(it)) {
                        is Load.Ok -> choose(read)
                        is Load.Bad -> problem = read.message
                    }
                }
            }
            Problem(problem, "upgrade:problem")
            Section("2. Choose Versions to Move To") {
                Muted(
                    "Type name=version pairs separated by commas. Names: " +
                        "${Targets.aliases.keys.joinToString(", ")}, " +
                        "or an attribute path such as tool.php.version.",
                    style = Type.body,
                )
                TargetField(text) { text = it }
                val presets = capsule?.let { UpgradeStory.presetsFor(it.value) }.orEmpty()
                if (presets.isNotEmpty()) {
                    Muted("Common moves", style = Type.small)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        presets.forEachIndexed { i, preset ->
                            Choice(
                                preset.label,
                                null,
                                Modifier.testTag("preset:$i"),
                                Role.Button,
                            ) { run(preset.text) }
                        }
                    }
                }
                Choice(
                    "Check the Upgrade",
                    null,
                    Modifier.testTag("upgrade:check"),
                    Role.Button,
                ) {
                    if (capsule == null) {
                        problem = "Choose a capsule first."
                    } else {
                        problem = null
                        run(text)
                    }
                }
            }
            when (val shown = result) {
                null -> Unit

                is UpgradeCheckResult.Bad ->
                    StudioText(
                        shown.message,
                        Modifier.testTag("upgrade:bad"),
                        Type.body,
                        palette.danger,
                    )

                is UpgradeCheckResult.Done -> UpgradeResult(shown.view)
            }
        }
    }
}

@Composable
private fun TargetField(text: String, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    BasicTextField(
        text,
        onChange,
        Modifier.fillMaxWidth().heightIn(min = MinTarget)
            .background(palette.surface, ControlShape)
            .border(1.dp, palette.control, ControlShape)
            .padding(horizontal = Space.m, vertical = Space.m)
            .semantics { contentDescription = "Versions to move to" }
            .testTag("upgrade:targets"),
        singleLine = true,
        textStyle = Type.mono.merge(color = palette.text),
        cursorBrush = SolidColor(palette.accentText),
        decorationBox = { field ->
            if (text.isEmpty()) Muted("php=8.3, drupal=11", style = Type.mono)
            field()
        },
    )
}

@Composable
private fun UpgradeResult(view: UpgradeView) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("Result")
            Heading(view.headline, Modifier.testTag("upgrade:headline"))
            Muted(UPGRADE_CAVEAT, style = Type.body)
            Muted(
                "${view.report.touched} of ${view.report.rules} rules read a setting you moved.",
                style = Type.body,
            )
        }
    }
    Group.entries.forEach { group ->
        val findings = view.report.of(group)
        val words = UpgradeStory.words(group)
        Section("${words.title} (${findings.size})") {
            Muted(words.about, style = Type.body)
            if (findings.isEmpty()) Muted(words.none, style = Type.body)
            findings.forEachIndexed { i, finding -> FindingItem(finding, "${group.id}:$i") }
        }
    }
    Disclosure("Technical Details") {
        Panel { StudioText(view.report.text(), Modifier.testTag("upgrade:text"), Type.monoSmall) }
    }
}

@Composable
private fun FindingItem(finding: Finding, tag: String) {
    val uri = LocalUriHandler.current
    Panel(Modifier.testTag("finding:$tag")) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            StudioText(finding.rule.title, weight = FontWeight.SemiBold)
            Muted(finding.rule.mechanism, style = Type.body)
            finding.crossings.forEach { StudioText(UpgradeStory.boundary(it), style = Type.body) }
            UpgradeStory.evidence(finding)?.let { Muted(it, style = Type.body) }
            UpgradeStory.needs(finding)?.let { Muted(it, style = Type.body) }
            Muted(UpgradeStory.severity(finding), style = Type.body)
            finding.rule.provenance.forEach { source ->
                if (source.url.startsWith("https://")) {
                    Box(
                        Modifier.heightIn(min = MinTarget)
                            .pressable(null, Role.Button) { uri.openUri(source.url) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        StudioText(
                            "Source: ${source.url}",
                            style = Type.monoSmall,
                            color = LocalPalette.current.accentText,
                        )
                    }
                }
            }
        }
    }
}
