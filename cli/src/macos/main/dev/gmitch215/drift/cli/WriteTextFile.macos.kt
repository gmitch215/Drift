@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.mkdir

actual fun writeTextFile(path: String, text: String): Boolean =
	posixWriteText(path, text) { mkdir(it, 511.convert()) }

actual fun writeBinaryFile(path: String, bytes: ByteArray): Boolean =
	posixWriteBytes(path, bytes) { mkdir(it, 511.convert()) }
