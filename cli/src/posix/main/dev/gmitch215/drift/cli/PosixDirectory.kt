@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.closedir
import platform.posix.opendir

internal fun isPosixDirectory(path: String): Boolean {
    val dir = opendir(path) ?: return false
    closedir(dir)
    return true
}
