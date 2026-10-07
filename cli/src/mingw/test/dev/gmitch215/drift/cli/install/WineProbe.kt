@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress

internal actual fun underWine() =
	GetProcAddress(GetModuleHandleW("ntdll.dll"), "wine_get_version") != null
