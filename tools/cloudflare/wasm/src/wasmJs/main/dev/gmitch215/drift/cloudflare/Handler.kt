package dev.gmitch215.drift.cloudflare

@OptIn(ExperimentalJsExport::class)
@JsExport
fun transcript(target: String): String = probeTranscript(target)

fun main() {}
