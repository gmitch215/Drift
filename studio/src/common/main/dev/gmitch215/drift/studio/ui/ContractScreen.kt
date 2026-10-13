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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import dev.gmitch215.drift.contract.AssertionResult
import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.contract.ContractReport
import dev.gmitch215.drift.model.Capsule

private const val SHOWN = 8

@Composable
fun ContractScreen(files: FilePicker? = null, samples: List<Sample> = samples()) {
    var contract by remember { mutableStateOf<Load.Ok<Contract>?>(null) }
    var capsule by remember { mutableStateOf<Load.Ok<Capsule>?>(null) }
    var contractProblem by remember { mutableStateOf<String?>(null) }
    var capsuleProblem by remember { mutableStateOf<String?>(null) }

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
                "Check a Contract",
                CONTRACT_ABOUT,
                "choose a contract and a capsule; the result appears when both are chosen.",
            )
            val first = samples.first()
            FileSlot(
                "1. Choose a Contract",
                "contract",
                contract?.name,
                files != null,
                listOfNotNull(
                    ContractStory.sampleContract(first)?.let { made ->
                        "Use a Sample Contract" to {
                            contract = Load.Ok("Sample contract from ${first.name}", made)
                            contractProblem = null
                        }
                    },
                ),
            ) {
                files?.pick(Loads.EXTENSION) {
                    when (val read = Loads.contract(it)) {
                        is Load.Ok -> {
                            contract = read
                            contractProblem = null
                        }

                        is Load.Bad -> contractProblem = read.message
                    }
                }
            }
            Problem(contractProblem, "contract:problem")
            FileSlot(
                "2. Choose a Capsule to Check",
                "capsule",
                capsule?.name,
                files != null,
                listOf<Pair<String, () -> Unit>>(
                    "Use Passing Run $GREEN_RUN" to {
                        capsule = Load.Ok("Passing Run $GREEN_RUN", first.green)
                        capsuleProblem = null
                    },
                ) + samples.map { s ->
                    "Use ${s.name}" to {
                        capsule = Load.Ok(s.name, s.red)
                        capsuleProblem = null
                    }
                },
            ) {
                files?.pick(Loads.EXTENSION) {
                    when (val read = Loads.capsule(it)) {
                        is Load.Ok -> {
                            capsule = read
                            capsuleProblem = null
                        }

                        is Load.Bad -> capsuleProblem = read.message
                    }
                }
            }
            Problem(capsuleProblem, "capsule:problem")
            val loaded = contract
            val target = capsule
            if (loaded != null && target != null) {
                ContractOutcome(loaded.value.verify(target.value))
            }
        }
    }
}

@Composable
private fun ContractOutcome(report: ContractReport) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Muted("Result")
            Heading(ContractStory.headline(report), Modifier.testTag("contract:headline"))
            Muted(
                "${report.holds} hold, ${report.breaks} break, ${report.unverifiable} " +
                    "could not be checked.",
                style = Type.body,
            )
        }
    }
    ContractStory.groups.forEach { status ->
        val rows = report.results.filter { it.finding.status == status }
        if (rows.isNotEmpty()) {
            Section("${ContractStory.title(status)} (${rows.size})") {
                Muted(ContractStory.about(status), style = Type.body)
                Requirements(rows, status.id)
            }
        }
    }
    Disclosure("Technical Details") {
        Panel { StudioText(report.text(), Modifier.testTag("contract:text"), Type.monoSmall) }
    }
}

@Composable
private fun Requirements(rows: List<AssertionResult>, tag: String) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all) rows else rows.take(SHOWN)
    shown.forEachIndexed { i, row ->
        val words = ContractStory.words(row)
        Panel(Modifier.testTag("requirement:$tag:$i")) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                StudioText(words.ask, weight = FontWeight.SemiBold)
                StudioText(words.found)
                Muted(words.why, style = Type.body)
            }
        }
    }
    if (rows.size > SHOWN) {
        Choice(
            if (all) "Show Fewer" else "Show ${rows.size - SHOWN} More",
            null,
            Modifier.testTag("more:$tag"),
            Role.Button,
        ) { all = !all }
    }
}
