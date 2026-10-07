package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

const val FOOTER = "Shows recorded captures from public CI runs; nothing here is a live diagnosis."

@Composable
fun StudioScreen(samples: List<Sample> = samples()) {
	var sampleIndex by remember { mutableStateOf(0) }
	var level by remember { mutableStateOf(DetailLevel.SUMMARY) }
	var selected by remember { mutableStateOf<String?>(null) }
	val sample = samples[sampleIndex]
	val view = StudioView.of(sample.state, level)

	Column(
		Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		BasicText("Drift Studio")
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			samples.forEachIndexed { i, s ->
				BasicText(
					(if (i == sampleIndex) "> " else "") + s.name,
					Modifier.clickable {
						sampleIndex = i
						selected = null
					},
				)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			DetailLevel.entries.forEach {
				BasicText(
					(if (it == level) "> " else "") + "level " + it.name.lowercase(),
					Modifier.clickable { level = it },
				)
			}
		}
		view.funnel.forEach { BasicText("${it.label}: ${it.count}") }
		FunnelPlot(view)
		BasicText(view.verdict)
		view.candidates.forEach {
			BasicText(it.text, Modifier.clickable { selected = it.path })
		}
		if (view.hidden > 0) BasicText("+${view.hidden} more")
		view.extras.forEach { BasicText(it) }
		selected?.let { path ->
			BasicText("Selected candidate")
			StudioView.drill(sample.state, path).forEach { BasicText("  $it") }
		}
		BasicText(FOOTER)
	}
}

@Composable
fun FunnelPlot(view: StudioView) {
	Canvas(Modifier.fillMaxWidth().height(80.dp)) {
		val slot = size.width / view.funnel.size
		view.funnel.forEachIndexed { i, stage ->
			val h = if (view.maxCount == 0) 0f else size.height * stage.count / view.maxCount
			drawRect(
				Color(0xFF3366CC),
				Offset(i * slot + slot * 0.1f, size.height - h),
				Size(slot * 0.8f, h),
			)
		}
	}
}
