@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.mkdir

actual fun writeTextFile(path: String, text: String): Boolean =
	posixWriteText(path, text) { mkdir(it) }

actual fun writeBinaryFile(path: String, bytes: ByteArray): Boolean =
	posixWriteBytes(path, bytes) { mkdir(it) }
