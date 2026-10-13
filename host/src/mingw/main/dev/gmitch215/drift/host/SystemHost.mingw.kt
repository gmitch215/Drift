@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.host

import dev.gmitch215.drift.model.Attribute
import dev.gmitch215.drift.model.Stability
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetFileAttributesW
import platform.windows.GetStdHandle
import platform.windows.GetSystemDirectoryW
import platform.windows.GlobalMemoryStatusEx
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.MAX_PATH
import platform.windows.MEMORYSTATUSEX
import platform.windows.PROCESS_INFORMATION
import platform.windows.PeekNamedPipe
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.STD_INPUT_HANDLE
import platform.windows.SetHandleInformation
import platform.windows.Sleep
import platform.windows.TRUE
import platform.windows.TerminateProcess
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject

private fun memory(): List<Attribute> = memScoped {
    val status = alloc<MEMORYSTATUSEX>()
    status.dwLength = sizeOf<MEMORYSTATUSEX>().convert()
    if (GlobalMemoryStatusEx(status.ptr) == 0) {
        emptyList()
    } else {
        listOf(
            Attribute("hw.memory.total", status.ullTotalPhys.toString(), "windows"),
            Attribute(
                "hw.memory.available",
                status.ullAvailPhys.toString(),
                "windows",
                Stability.VOLATILE,
            ),
        )
    }
}

private fun environment(): Map<String, String>? {
    val block = GetEnvironmentStringsW() ?: return null
    try {
        val chars = ArrayList<Char>()
        var i = 0
        while (!(block[i] == 0.toUShort() && block[i + 1] == 0.toUShort())) {
            chars += block[i].toInt().toChar()
            i++
        }
        return parseEnvBlock(chars.toCharArray())
    } finally {
        FreeEnvironmentStringsW(block)
    }
}

private fun isFile(path: String): Boolean {
    val attributes = memScoped { GetFileAttributesW(path.wcstr.getPointer(this)) }
    val directory = attributes and FILE_ATTRIBUTE_DIRECTORY.convert()
    return attributes != INVALID_FILE_ATTRIBUTES && directory == 0u
}

private fun systemDirectory(): String = memScoped {
    val buffer = allocArray<UShortVar>(MAX_PATH)
    GetSystemDirectoryW(buffer, MAX_PATH.convert())
    buffer.toKStringFromUtf16()
}

// PeekNamedPipe keeps reads from blocking; a grandchild holding the pipe open cannot stall them
private class WindowsChild(
    private val read: HANDLE?,
    private val process: HANDLE?,
    private val thread: HANDLE?,
) : ChildProcess {
    override fun read(buf: ByteArray, millis: Int): Int = memScoped {
        val available = alloc<DWORDVar>()
        if (PeekNamedPipe(read, null, 0u, null, available.ptr, null) == 0) {
            Sleep(millis.toUInt())
            return@memScoped -1
        }
        if (available.value == 0u) {
            Sleep(millis.toUInt())
            return@memScoped 0
        }
        val count = alloc<DWORDVar>()
        val want = minOf(available.value.toInt(), buf.size)
        val ok = buf.usePinned {
            ReadFile(read, it.addressOf(0), want.convert(), count.ptr, null)
        }
        if (ok == 0) -1 else count.value.toInt()
    }

    override fun exitCode(): Int? {
        if (WaitForSingleObject(process, 0u) != WAIT_OBJECT_0) return null
        return memScoped {
            val code = alloc<DWORDVar>()
            GetExitCodeProcess(process, code.ptr)
            code.value.toInt()
        }
    }

    override fun kill() {
        TerminateProcess(process, TIMED_OUT_EXIT_CODE.convert())
    }

    override fun close() {
        CloseHandle(read)
        CloseHandle(process)
        CloseHandle(thread)
    }
}

// stdout and stderr share one pipe; .bat and .cmd programs run through cmd.exe.
private fun createProcessRun(
    argv: List<String>,
    timeoutMillis: Long?,
    onOutput: (String) -> Unit,
): CommandResult? = memScoped {
    val commandLine = windowsLaunchLine(
        argv,
        environment().orEmpty(),
        systemDirectory(),
        ::isFile,
    )
    val security = alloc<SECURITY_ATTRIBUTES>()
    security.nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
    security.bInheritHandle = TRUE
    val read = alloc<HANDLEVar>()
    val write = alloc<HANDLEVar>()
    if (CreatePipe(read.ptr, write.ptr, security.ptr, 0u) == 0) return@memScoped null
    SetHandleInformation(read.value, HANDLE_FLAG_INHERIT.convert(), 0u)
    val startup = alloc<STARTUPINFOW>()
    startup.cb = sizeOf<STARTUPINFOW>().convert()
    startup.dwFlags = STARTF_USESTDHANDLES.convert()
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE)
    startup.hStdOutput = write.value
    startup.hStdError = write.value
    val process = alloc<PROCESS_INFORMATION>()
    // no console window for console children of a GUI host
    val started = CreateProcessW(
        null,
        commandLine.wcstr.getPointer(this),
        null,
        null,
        TRUE,
        CREATE_NO_WINDOW.convert(),
        null,
        null,
        startup.ptr,
        process.ptr,
    )
    CloseHandle(write.value)
    if (started == 0) {
        CloseHandle(read.value)
        return@memScoped null
    }
    pumpUntilExit(
        WindowsChild(read.value, process.hProcess, process.hThread),
        timeoutMillis,
        onOutput,
    )
}

actual fun systemHost(): Host = NativeHost("mingw", ::createProcessRun, ::memory, ::environment)
