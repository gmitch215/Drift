@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.window.ComposeUIViewController
import dev.gmitch215.drift.host.systemHost
import dev.gmitch215.drift.scan.atlas.Atlas
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

private const val RECORD_ENV = "DRIFT_RECORD"

fun mainViewController() = run {
	val host = systemHost()
	host.env()[RECORD_ENV]?.let { path ->
		val file = fopen(path, "w")
		if (file != null) {
			fputs(Atlas.record(host), file)
			fclose(file)
		}
	}
	ComposeUIViewController { StudioApp() }
}
