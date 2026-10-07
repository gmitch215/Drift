@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.gmitch215.drift.cli

import kotlin.js.ExperimentalWasmJsInterop

actual fun writeTextFile(path: String, text: String): Boolean = false

actual fun writeBinaryFile(path: String, bytes: ByteArray): Boolean = false

actual fun readBinaryFile(path: String): ByteArray? = null

@JsFun("(s) => { if (typeof process !== 'undefined') process.stdout.write(s) }")
private external fun nodeWrite(text: String)

actual fun writeStdout(text: String) = nodeWrite(text)
