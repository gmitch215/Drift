package dev.gmitch215.drift.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Desktop
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    var opened by mutableStateOf(caseFromArgs(args))
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler { event ->
                event.files.firstOrNull()?.let { opened = loadCase(it.path) }
            }
        }
    }
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
            StudioApp(
                measure = Devices.measurer(Runtime.version().feature().toString()),
                case = opened,
                picker = fileDialogPicker,
                files = fileDialogFilePicker,
                saver = fileDialogSaver,
            )
        }
    }
}

private fun loadIcon(): BufferedImage? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use(ImageIO::read)
