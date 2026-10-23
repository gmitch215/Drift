package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(transcript, millis) => { globalThis.driftDevice = { transcript, millis }; }")
private external fun publishDevice(transcript: String, millis: Int)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.location.hash")
private external fun locationHash(): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.navigator.userAgent")
private external fun userAgent(): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    "(name, text) => { const blob = new Blob([text], { type: 'text/plain' }); " +
        "const url = URL.createObjectURL(blob); const link = document.createElement('a'); " +
        "link.href = url; link.download = name; " +
        "document.body.appendChild(link); link.click(); link.remove(); " +
        "setTimeout(() => URL.revokeObjectURL(url), 1000); }",
)
private external fun download(name: String, text: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    "(accept, done) => { const input = document.createElement('input'); input.type = 'file'; " +
        "input.accept = accept; input.onchange = () => { const file = input.files[0]; " +
        "if (!file) return; if (file.size > 134217728) { done(file.name, ''); return; } " +
        "const reader = new FileReader(); " +
        "reader.onload = () => done(file.name, reader.result.substring(" +
        "reader.result.indexOf(',') + 1)); " +
        "reader.onerror = () => done(file.name, ''); reader.readAsDataURL(file); }; " +
        "input.click(); }",
)
private external fun chooseFile(accept: String, done: (String, String) -> Unit)

private val browserFilePicker = FilePicker { extension, onPicked ->
    chooseFile(extension) { name, data ->
        onPicked(
            try {
                if (data.isEmpty()) {
                    PickedFile(name, null, "the browser could not read it (limit 128 MB)")
                } else {
                    PickedFile(name, Base64.decode(data))
                }
            } catch (e: IllegalArgumentException) {
                PickedFile(name, null, "the browser could not read it")
            },
        )
    }
}

private val browserPicker = CasePicker { onOpened ->
    browserFilePicker.pick(".driftcase") {
        val bytes = it.bytes
        onOpened(
            if (bytes == null) {
                CaseView.unreadable(it.name, it.problem.orEmpty())
            } else {
                CaseView.of(it.name, bytes)
            },
        )
    }
}

private val browserSaver = FileSaver { name, text ->
    try {
        download(name, text)
        null
    } catch (e: Throwable) {
        e.message ?: "the browser refused the download"
    }
}

private val browserMeasure = DeviceMeasure { dataset, progress ->
    val start = TimeSource.Monotonic.markNow()
    Devices.measure(dataset, runtime = Devices.browser(userAgent()), progress = progress).also {
        publishDevice(it.transcript, start.elapsedNow().inWholeMilliseconds.toInt())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        StudioApp(
            measure = browserMeasure,
            picker = browserPicker,
            files = browserFilePicker,
            saver = browserSaver,
            startOnAtlas = locationHash() == "#atlas",
        )
    }
}
