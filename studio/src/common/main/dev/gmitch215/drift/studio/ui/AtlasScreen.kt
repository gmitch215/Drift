package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.gmitch215.drift.scan.atlas.Dataset
import kotlin.time.TimeSource

const val ATLAS_FOOTER =
	"Matrix cells are recorded results for the listed Kotlin versions; the this-device column " +
		"is measured now. A difference between targets is a result, not a diagnosis."

private enum class Mode(val label: String) { MATRIX("matrix"), PREDICT("predict") }

private enum class Tab(val label: String) { ATLAS("Atlas"), INVESTIGATION("Investigation") }

@Composable
fun StudioApp(
	dataset: Dataset = AtlasData.dataset,
	samples: List<Sample> = samples(),
	measure: (Dataset) -> Device = { Devices.measure(it) },
) {
	var tab by remember { mutableStateOf(Tab.ATLAS) }
	Column {
		Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Tab.entries.forEach {
				BasicText((if (it == tab) "> " else "") + it.label, Modifier.clickable { tab = it })
			}
		}
		when (tab) {
			Tab.ATLAS -> AtlasScreen(dataset, measure)
			Tab.INVESTIGATION -> StudioScreen(samples)
		}
	}
}

@Composable
fun AtlasScreen(
	dataset: Dataset = AtlasData.dataset,
	measure: (Dataset) -> Device = { Devices.measure(it) },
) {
	var state by remember { mutableStateOf(AtlasState(dataset)) }
	var predict by remember { mutableStateOf(PredictState(dataset)) }
	var mode by remember { mutableStateOf(Mode.MATRIX) }
	var millis by remember { mutableStateOf<Long?>(null) }

	LaunchedEffect(dataset) {
		withFrameNanos { }
		val start = TimeSource.Monotonic.markNow()
		val device = measure(dataset)
		millis = start.elapsedNow().inWholeMilliseconds
		state = state.copy(device = device)
	}

	Column(
		Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		BasicText("Drift Atlas")
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			BasicText("Kotlin")
			dataset.versions.forEach {
				BasicText(
					(if (it == state.version) "> " else "") + it,
					Modifier.testTag("version:$it").clickable {
						state = state.copy(version = it, selected = null)
						predict = predict.withVersion(it)
					},
				)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Mode.entries.forEach {
				BasicText(
					(if (it == mode) "> " else "") + it.label,
					Modifier.testTag("mode:${it.label}").clickable { mode = it },
				)
			}
		}
		when (mode) {
			Mode.MATRIX -> MatrixPane(state, millis) { state = it }
			Mode.PREDICT -> PredictPane(predict) { predict = it }
		}
		BasicText(ATLAS_FOOTER)
	}
}

@Composable
private fun MatrixPane(state: AtlasState, millis: Long?, update: (AtlasState) -> Unit) {
	val view = AtlasView.of(state)
	Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		DetailLevel.entries.forEach {
			BasicText(
				(if (it == state.level) "> " else "") + "level " + it.name.lowercase(),
				Modifier.clickable { update(state.copy(level = it)) },
			)
		}
		BasicText(
			(if (state.divergentOnly) "> " else "") + "divergent only",
			Modifier.testTag("filter:divergent").clickable {
				update(state.copy(divergentOnly = !state.divergentOnly))
			},
		)
	}
	BasicText(
		"${view.divergent} of ${view.total} probes differ between targets " +
			"on kotlin ${view.version}",
		Modifier.testTag("count"),
	)
	if (view.hidden > 0) BasicText("${view.hidden} identical probes hidden")
	if (view.deviceSummary.isEmpty()) {
		BasicText("measuring this device...")
	} else {
		view.deviceSummary.forEach { BasicText(it) }
		millis?.let { BasicText("measured in $it ms") }
	}
	val scroll = rememberScrollState()
	Row(Modifier.fillMaxWidth()) {
		BasicText("", Modifier.width(NAME_WIDTH))
		Row(Modifier.weight(1f).horizontalScroll(scroll)) {
			view.groups.forEach {
				BasicText(
					it.name,
					Modifier.width(CELL_WIDTH * it.targets.size).testTag("group:${it.name}"),
				)
			}
			BasicText("Device", Modifier.width(CELL_WIDTH * 2))
		}
	}
	Row(Modifier.fillMaxWidth()) {
		BasicText("probe", Modifier.width(NAME_WIDTH))
		Row(Modifier.weight(1f).horizontalScroll(scroll)) {
			view.targets.forEach { BasicText(it, Modifier.width(CELL_WIDTH)) }
			BasicText(
				view.deviceLabel ?: "this device",
				Modifier.width(CELL_WIDTH).testTag("device-header"),
			)
			BasicText("device vs", Modifier.width(CELL_WIDTH))
		}
	}
	var area = ""
	view.rows.forEach { r ->
		if (r.area != area) {
			area = r.area
			BasicText(area.uppercase())
		}
		Row(Modifier.fillMaxWidth()) {
			BasicText(
				(if (r.divergent) "* " else "  ") + r.name,
				Modifier.width(NAME_WIDTH).testTag("row:${r.id}").clickable {
					update(state.copy(selected = if (state.selected == r.id) null else r.id))
				},
			)
			Row(Modifier.weight(1f).horizontalScroll(scroll)) {
				r.cells.forEach {
					BasicText(
						it.short,
						Modifier.width(CELL_WIDTH).testTag("cell:${r.id}:${it.target}"),
					)
				}
				val device = r.device?.short.orEmpty()
				BasicText(device, Modifier.width(CELL_WIDTH).testTag("cell:${r.id}:device"))
				BasicText(
					r.deviceVerdict.orEmpty(),
					Modifier.width(CELL_WIDTH).testTag("vs:${r.id}"),
				)
			}
		}
		BasicText(r.badge, Modifier.padding(start = 16.dp).testTag("badge:${r.id}"))
		r.notes.forEach { BasicText(it, Modifier.padding(start = 16.dp)) }
	}
	state.selected?.let { id ->
		BasicText("Selected probe")
		AtlasView.drill(state, id).forEach { BasicText("  $it") }
	}
	state.device?.let { Export(it) }
}

@Composable
private fun Export(device: Device) {
	var shown by remember { mutableStateOf(false) }
	BasicText(
		"export transcript for atlas build",
		Modifier.testTag("export").clickable { shown = true },
	)
	if (shown) {
		BasicTextField(
			device.transcript,
			{},
			Modifier.fillMaxWidth().height(160.dp).testTag("export-text"),
			readOnly = true,
		)
	}
}

@Composable
private fun PredictPane(state: PredictState, update: (PredictState) -> Unit) {
	val view = PredictView.of(state)
	val score = state.score
	BasicText(
		"score: ${score.correct} correct, ${score.wrong} wrong, ${score.skipped} skipped",
		Modifier.testTag("score"),
	)
	if (view == null) {
		BasicText("no probe differs between targets on kotlin ${state.version}")
		return
	}
	BasicText("probe ${view.index + 1} of ${view.count}: ${view.probeId}")
	view.source.forEach { BasicText("  $it") }
	BasicText(view.question)
	BasicText(view.prompt, Modifier.testTag("prompt"))
	val reveal = view.reveal
	if (reveal == null) {
		view.choices.forEachIndexed { i, c ->
			Column(Modifier.testTag("choice:$i").clickable { update(state.guess(i)) }) {
				BasicText(c.label)
				c.lines.forEach { BasicText("  $it") }
			}
		}
		BasicText(
			"I don't know",
			Modifier.testTag("choice:unknown").clickable { update(state.guess(DONT_KNOW)) },
		)
	} else {
		BasicText(reveal.verdict, Modifier.testTag("verdict"))
		reveal.results.forEach { BasicText(it) }
		BasicText(reveal.badge)
		reveal.notes.forEach { BasicText(it) }
		BasicText("next probe", Modifier.testTag("next").clickable { update(state.next()) })
	}
}

private val NAME_WIDTH = 220.dp

private val CELL_WIDTH = 150.dp
