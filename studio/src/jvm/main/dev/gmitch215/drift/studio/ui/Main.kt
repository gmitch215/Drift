package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun main() {
	val icon = loadIcon()
	if (icon != null && Taskbar.isTaskbarSupported()) {
		val taskbar = Taskbar.getTaskbar()
		if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = icon
	}
	application {
		Window(
			onCloseRequest = ::exitApplication,
			title = "Drift Studio",
			icon = icon?.let { BitmapPainter(it.toComposeImageBitmap()) },
		) {
			StudioApp()
		}
	}
}

private fun loadIcon(): BufferedImage? =
	Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use(ImageIO::read)
