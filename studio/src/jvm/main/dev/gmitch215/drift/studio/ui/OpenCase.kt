package dev.gmitch215.drift.studio.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private const val MAX_CASE_BYTES = 128L * 1024 * 1024

fun readPicked(file: File): PickedFile = try {
    when {
        !file.isFile -> PickedFile(file.name, null, "not a file")
        file.length() > MAX_CASE_BYTES -> PickedFile(file.name, null, "file is too large")
        else -> PickedFile(file.name, file.readBytes())
    }
} catch (e: java.io.IOException) {
    PickedFile(file.name, null, e.message ?: "read error")
}

fun loadCase(path: String): CaseView {
    val picked = readPicked(File(path))
    val bytes = picked.bytes ?: return CaseView.unreadable(picked.name, picked.problem.orEmpty())
    return CaseView.of(picked.name, bytes)
}

fun caseFromArgs(args: Array<String>): CaseView? =
    args.firstOrNull { !it.startsWith("-") }?.let(::loadCase)

val fileDialogPicker = CasePicker { onOpened ->
    val dialog = FileDialog(null as Frame?, "Choose a Case File", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".driftcase") }
    dialog.isVisible = true
    dialog.file?.let { onOpened(loadCase(File(dialog.directory, it).path)) }
}

val fileDialogFilePicker = FilePicker { extension, onPicked ->
    val dialog = FileDialog(null as Frame?, "Choose a File", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(extension) }
    dialog.isVisible = true
    dialog.file?.let { onPicked(readPicked(File(dialog.directory, it))) }
}
