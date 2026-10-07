package dev.gmitch215.drift.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.scan.atlas.Atlas
import dev.gmitch215.drift.studio.ui.StudioApp
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (intent.getBooleanExtra(RECORD, false)) {
			thread { File(filesDir, TRANSCRIPT).writeText(Atlas.record(systemHost())) }
		}
		setContent { StudioApp() }
	}

	private companion object {
		const val RECORD = "record"
		const val TRANSCRIPT = "atlas-android.txt"
	}
}
