@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.install

import dev.gmitch215.drift.cli.INVALID_ATTRIBUTES
import dev.gmitch215.drift.cli.PATH_BUFFER
import dev.gmitch215.drift.cli.fileAttributes
import dev.gmitch215.drift.cli.isWindowsDirectory
import dev.gmitch215.drift.cli.wideString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix._wfopen
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateProcessW
import platform.windows.DETACHED_PROCESS
import platform.windows.DWORDVar
import platform.windows.DeleteFileW
import platform.windows.GetCurrentProcess
import platform.windows.GetModuleFileNameW
import platform.windows.GetTokenInformation
import platform.windows.HANDLEVar
import platform.windows.HKEYVar
import platform.windows.MoveFileExW
import platform.windows.OpenProcessToken
import platform.windows.PROCESS_INFORMATION
import platform.windows.RegCloseKey
import platform.windows.RegDeleteValueW
import platform.windows.RegOpenKeyExW
import platform.windows.RegQueryValueExW
import platform.windows.RegSetValueExW
import platform.windows.RemoveDirectoryW
import platform.windows.STARTUPINFOW
import platform.windows.SendMessageTimeoutW

private const val READ_ONLY = 0x01u
private const val MOVE_REPLACE = 1u
private const val TOKEN_QUERY = 0x0008u
private const val TOKEN_ELEVATION = 20
private const val KEY_READ_WRITE = 0x000F003Fu
private const val SETTING_CHANGE = 0x001Au
private const val ABORT_IF_HUNG = 2u
private const val NOT_FOUND = 2

private val userRoot get() = (0x80000001.toInt().toLong()).toCPointer<platform.windows.HKEY__>()
private val machineRoot get() = (0x80000002.toInt().toLong()).toCPointer<platform.windows.HKEY__>()
private const val USER_SUBKEY = "Environment"
private const val MACHINE_SUBKEY =
    "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"

private object MingwInstall : NativeInstall({ path, mode ->
    memScoped { _wfopen(path.wcstr.ptr.reinterpret(), mode.wcstr.ptr.reinterpret()) }
}) {
    override fun markExecutable(path: String) = true

    override fun selfPath(): String? = memScoped {
        val buffer = allocArray<UShortVar>(PATH_BUFFER)
        val n = GetModuleFileNameW(null, buffer.reinterpret(), PATH_BUFFER.convert()).toInt()
        if (n <= 0 || n >= PATH_BUFFER) null else wideString(buffer, n)
    }

    override fun isPrivileged(): Boolean = memScoped {
        val token = alloc<HANDLEVar>()
        if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY.convert(), token.ptr) == 0) {
            return false
        }
        val elevated = alloc<DWORDVar>()
        val size = alloc<DWORDVar>()
        val ok = GetTokenInformation(
            token.value,
            TOKEN_ELEVATION.convert(),
            elevated.ptr,
            sizeOf<DWORDVar>().convert(),
            size.ptr,
        )
        CloseHandle(token.value)
        ok != 0 && elevated.value != 0u
    }

    override fun exists(path: String) = fileAttributes(path) != INVALID_ATTRIBUTES

    override fun isDirectory(path: String) = isWindowsDirectory(path)

    override fun canWrite(directory: String): Boolean {
        val a = fileAttributes(directory)
        return a != INVALID_ATTRIBUTES && a and READ_ONLY == 0u
    }

    override fun rename(from: String, to: String): Boolean = memScoped {
        val source = from.wcstr.ptr.reinterpret<UShortVar>()
        MoveFileExW(source.reinterpret(), to.wcstr.ptr.reinterpret(), MOVE_REPLACE.convert()) != 0
    }

    override fun remove(path: String): Boolean = memScoped {
        DeleteFileW(path.wcstr.ptr.reinterpret()) != 0
    }

    override fun makeDirectory(path: String): Boolean = memScoped {
        CreateDirectoryW(path.wcstr.ptr.reinterpret(), null) != 0
    }

    override fun removeDirectory(path: String): Boolean = memScoped {
        RemoveDirectoryW(path.wcstr.ptr.reinterpret()) != 0
    }

    override fun readPath(machine: Boolean): RegistryValue? = memScoped {
        val key = alloc<HKEYVar>()
        if (open(machine, key.ptr) != 0) return null
        try {
            val type = alloc<DWORDVar>()
            val size = alloc<DWORDVar>()
            val name = "Path".wcstr.ptr.reinterpret<UShortVar>()
            val probe = RegQueryValueExW(
                key.value,
                name.reinterpret(),
                null,
                type.ptr,
                null,
                size.ptr,
            )
            if (probe != 0) return null
            val data = allocArray<UShortVar>(size.value.toInt() / 2 + 2)
            val got = RegQueryValueExW(
                key.value,
                name.reinterpret(),
                null,
                type.ptr,
                data.reinterpret(),
                size.ptr,
            )
            if (got != 0) return null
            val chars = size.value.toInt() / 2
            val end = if (chars > 0 && data[chars - 1] == 0.toUShort()) chars - 1 else chars
            RegistryValue(type.value.toInt(), wideString(data, end))
        } finally {
            RegCloseKey(key.value)
        }
    }

    override fun writePath(machine: Boolean, value: RegistryValue?): Boolean = memScoped {
        val key = alloc<HKEYVar>()
        if (open(machine, key.ptr) != 0) return false
        try {
            val name = "Path".wcstr.ptr.reinterpret<UShortVar>()
            if (value == null) {
                val code = RegDeleteValueW(key.value, name.reinterpret())
                code == 0 || code == NOT_FOUND
            } else {
                val wide = value.data.wcstr
                RegSetValueExW(
                    key.value,
                    name.reinterpret(),
                    0.convert(),
                    value.type.convert(),
                    wide.ptr.reinterpret(),
                    wide.size.convert(),
                ) == 0
            }
        } finally {
            RegCloseKey(key.value)
        }
    }

    override fun broadcastEnvironment() = memScoped {
        val result = alloc<platform.windows.ULONG_PTRVar>()
        SendMessageTimeoutW(
            0xffff.toLong().toCPointer(),
            SETTING_CHANGE.convert(),
            0.convert(),
            "Environment".wcstr.ptr.toLong().convert(),
            ABORT_IF_HUNG.convert(),
            5000.convert(),
            result.ptr,
        )
        Unit
    }

    override fun removeAfterExit(paths: List<String>, directories: List<String>): Boolean {
        val line = cleanupCommand(paths, directories) ?: return false
        return spawnCleanup(line)
    }

    private fun spawnCleanup(line: String): Boolean = memScoped {
        val startup = alloc<STARTUPINFOW>()
        startup.cb = sizeOf<STARTUPINFOW>().convert()
        val process = alloc<PROCESS_INFORMATION>()
        val ok = CreateProcessW(
            null,
            line.wcstr.ptr.reinterpret(),
            null,
            null,
            0,
            DETACHED_PROCESS.convert<UInt>() or CREATE_NO_WINDOW.convert<UInt>(),
            null,
            null,
            startup.ptr,
            process.ptr,
        ) != 0
        if (ok) {
            CloseHandle(process.hProcess)
            CloseHandle(process.hThread)
        }
        ok
    }

    private fun open(machine: Boolean, out: CPointer<HKEYVar>): Int = memScoped {
        val sub = (if (machine) MACHINE_SUBKEY else USER_SUBKEY).wcstr.ptr.reinterpret<UShortVar>()
        RegOpenKeyExW(
            if (machine) machineRoot else userRoot,
            sub.reinterpret(),
            0.convert(),
            KEY_READ_WRITE.convert(),
            out,
        ).convert()
    }
}

actual fun systemInstall(): InstallSystem = MingwInstall
