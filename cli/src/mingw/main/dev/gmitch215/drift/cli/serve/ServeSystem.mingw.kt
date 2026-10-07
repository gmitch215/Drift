@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.serve

import dev.gmitch215.drift.cli.readBinaryFile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.INVALID_SOCKET
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.SOCKET
import platform.posix.WSADATA
import platform.posix.WSAGetLastError
import platform.posix.WSAStartup
import platform.posix.accept
import platform.posix.bind
import platform.posix.closesocket
import platform.posix.fd_set
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.signal
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateFileW
import platform.windows.CreateThread
import platform.windows.GetFileAttributesW
import platform.windows.GetFinalPathNameByHandleW
import platform.windows.OPEN_EXISTING
import platform.windows.Sleep
import platform.windows.addrinfo
import platform.windows.freeaddrinfo
import platform.windows.getaddrinfo
import platform.windows.select
import kotlin.concurrent.AtomicInt

private const val IO_MILLIS = 5000
private const val BACKLOG = 128
private const val SOCKADDR_IN6 = 28
private const val SOCK_STREAM_ID = 1
private const val IPPROTO_TCP_ID = 6
private const val IPPROTO_IPV6_ID = 41
private const val IPV6_V6ONLY_ID = 27
private const val SOL_SOCKET_ID = 0xffff
private const val SO_REUSEADDR_ID = 4
private const val SO_RCVTIMEO_ID = 0x1006
private const val SO_SNDTIMEO_ID = 0x1005
private const val INVALID_ATTRIBUTES = 0xFFFFFFFFu
private const val DIRECTORY = 0x10u
private const val FILE_SHARE_ALL = 7u
private const val BACKUP_SEMANTICS = 0x02000000u
private const val PATH_BUFFER = 32768

private val interrupt = AtomicInt(0)
private var started = false

private fun startup() {
	if (started) return
	memScoped { WSAStartup(0x0202u, alloc<WSADATA>().ptr) }
	started = true
}

private fun option(fd: SOCKET, level: Int, name: Int, value: Int) = memScoped {
	val v = alloc<IntVar>()
	v.value = value
	setsockopt(fd, level, name, v.ptr.reinterpret(), sizeOf<IntVar>().convert()) == 0
}

private class SocketConnection(private val fd: SOCKET) : Connection {
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (length <= 0) return 0
		val n = buffer.usePinned { recv(fd, it.addressOf(offset), length, 0) }
		return if (n <= 0) -1 else n
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
		var at = 0
		while (at < length) {
			val n = buffer.usePinned { send(fd, it.addressOf(offset + at), length - at, 0) }
			if (n <= 0) return false
			at += n
		}
		return true
	}

	override fun close() {
		closesocket(fd)
	}
}

private class SocketAcceptor(
	private val sockets: List<SOCKET>,
	override val addresses: List<String>,
	override val port: Int,
) : Acceptor {
	private var closed = false

	override fun accept(timeoutMillis: Int): Connection? = memScoped {
		val set = alloc<fd_set>()
		set.fd_count = sockets.size.convert()
		for (i in sockets.indices) set.fd_array[i] = sockets[i]
		val wait = alloc<timeval>()
		wait.tv_sec = (timeoutMillis / 1000).convert()
		wait.tv_usec = ((timeoutMillis % 1000) * 1000).convert()
		if (select(0, set.ptr, null, null, wait.ptr) <= 0) return null
		if (set.fd_count.toInt() <= 0) return null
		val client = accept(set.fd_array[0], null, null)
		if (client == INVALID_SOCKET) return null
		option(client, SOL_SOCKET_ID, SO_RCVTIMEO_ID, IO_MILLIS)
		option(client, SOL_SOCKET_ID, SO_SNDTIMEO_ID, IO_MILLIS)
		SocketConnection(client)
	}

	override fun close() {
		if (closed) return
		closed = true
		sockets.forEach { closesocket(it) }
	}
}

private fun listener(v6: Boolean, all: Boolean, port: Int): SOCKET = memScoped {
	startup()
	val fd = socket(if (v6) AF_INET6 else AF_INET, SOCK_STREAM_ID, IPPROTO_TCP_ID)
	if (fd == INVALID_SOCKET) throw ServeFailure("socket failed with ${WSAGetLastError()}")
	val bound = if (v6) {
		option(fd, IPPROTO_IPV6_ID, IPV6_V6ONLY_ID, 1)
		val address = allocArray<ByteVar>(SOCKADDR_IN6)
		for (i in 0 until SOCKADDR_IN6) address[i] = 0
		address[0] = AF_INET6.toByte()
		address[2] = (port shr 8).toByte()
		address[3] = port.toByte()
		if (!all) address[8 + 15] = 1
		bind(fd, address.reinterpret(), SOCKADDR_IN6.convert())
	} else {
		val address = alloc<sockaddr_in>()
		address.sin_family = AF_INET.convert()
		address.sin_port = swap(port).convert()
		val raw = address.sin_addr.ptr.reinterpret<ByteVar>()
		if (!all) {
			raw[0] = 127
			raw[3] = 1
		}
		bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
	}
	if (bound != 0 || listen(fd, BACKLOG) != 0) {
		val code = WSAGetLastError()
		closesocket(fd)
		throw ServeFailure(if (code == 10048) "address already in use" else "error $code")
	}
	fd
}

private fun localPort(fd: SOCKET): Int = memScoped {
	val address = alloc<sockaddr_in>()
	val size = alloc<IntVar>()
	size.value = sizeOf<sockaddr_in>().convert()
	getsockname(fd, address.ptr.reinterpret(), size.ptr)
	swap(address.sin_port.toInt())
}

private fun wide(buffer: kotlinx.cinterop.CPointer<UShortVar>, length: Int) =
	CharArray(length) { buffer[it].toInt().toChar() }.concatToString()

private object MingwFiles : SiteFiles {
	override val separator = '\\'
	override val ignoreCase = true

	private fun attributes(path: String): UInt = memScoped {
		GetFileAttributesW(path.wcstr.ptr.reinterpret()).convert()
	}

	override fun canonical(path: String): String? = memScoped {
		val handle = CreateFileW(
			path.wcstr.ptr.reinterpret(),
			0u,
			FILE_SHARE_ALL,
			null,
			OPEN_EXISTING.convert(),
			BACKUP_SEMANTICS,
			null,
		)
		if (handle == platform.windows.INVALID_HANDLE_VALUE) return null
		val buffer = allocArray<UShortVar>(PATH_BUFFER)
		val size = PATH_BUFFER.convert<UInt>()
		val n = GetFinalPathNameByHandleW(handle, buffer.reinterpret(), size, 0u).toInt()
		CloseHandle(handle)
		if (n <= 0 || n >= PATH_BUFFER) return null
		val text = wide(buffer, n)
		when {
			text.startsWith("\\\\?\\UNC\\") -> "\\\\" + text.removePrefix("\\\\?\\UNC\\")
			text.startsWith("\\\\?\\") -> text.removePrefix("\\\\?\\")
			else -> text
		}
	}

	override fun isDirectory(path: String): Boolean {
		val a = attributes(path)
		return a != INVALID_ATTRIBUTES && a and DIRECTORY != 0u
	}

	override fun read(path: String): ByteArray? {
		if (isDirectory(path)) return null
		return readBinaryFile(path)
	}
}

private object MingwServe : ServeSystem {
	override val files = MingwFiles
	override val tlsUnavailable = "the Windows build has no TLS library"

	override fun listen(bind: Bind, port: Int, tls: TlsFiles?): Acceptor {
		if (tls != null) throw ServeFailure(tlsUnavailable)
		val all = bind == Bind.ALL
		val first = try {
			listener(false, all, port)
		} catch (e: ServeFailure) {
			throw ServeFailure(
				"cannot listen on port $port: ${e.message}; pass --port 0 to take a free port",
			)
		}
		val real = localPort(first)
		val sockets = mutableListOf(first)
		val addresses = mutableListOf((if (all) "0.0.0.0" else "127.0.0.1") + ":$real")
		try {
			sockets += listener(true, all, real)
			addresses += (if (all) "[::]" else "[::1]") + ":$real"
		} catch (_: ServeFailure) {
			// no IPv6 loopback here; IPv4 alone still serves localhost
		}
		return SocketAcceptor(sockets, addresses, real)
	}

	override fun spawn(block: () -> Unit): Boolean {
		val ref = StableRef.create(block)
		val handle = CreateThread(
			null,
			0u,
			staticCFunction { arg ->
				val holder = arg!!.asStableRef<() -> Unit>()
				try {
					holder.get().invoke()
				} finally {
					holder.dispose()
				}
				0u
			},
			ref.asCPointer(),
			0u,
			null,
		)
		if (handle == null) ref.dispose() else CloseHandle(handle)
		return handle != null
	}

	override fun sleep(millis: Int) = Sleep(millis.convert())

	override fun trapInterrupt() {
		interrupt.value = 0
		val handler = staticCFunction<Int, Unit> { interrupt.value = 1 }
		signal(SIGINT, handler)
		signal(SIGTERM, handler)
	}

	override fun interrupted() = interrupt.value != 0

	override fun resolve(name: String): List<String> = memScoped {
		startup()
		val hints = alloc<addrinfo>()
		platform.posix.memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
		hints.ai_family = AF_UNSPEC
		hints.ai_socktype = SOCK_STREAM_ID
		val result = alloc<CPointerVar<addrinfo>>()
		if (getaddrinfo(name, null, hints.ptr, result.ptr) != 0) return emptyList()
		val found = mutableListOf<String>()
		var item = result.value
		while (item != null) {
			val entry = item.pointed
			val address = entry.ai_addr
			if (address != null && entry.ai_family == AF_INET) {
				val v4 = address.reinterpret<sockaddr_in>().pointed
				val raw = v4.sin_addr.ptr.reinterpret<ByteVar>()
				found += addressText((0 until 4).map { raw[it].toInt() and 0xff })
			} else if (address != null && entry.ai_family == AF_INET6) {
				val raw = address.reinterpret<ByteVar>()
				found += addressText((0 until 16).map { raw[8 + it].toInt() and 0xff })
			}
			item = entry.ai_next
		}
		freeaddrinfo(result.value)
		found.distinct()
	}

	override fun mode(path: String): Int? = null

	override fun makePrivateDirectory(path: String): Boolean {
		val parts = path.split('\\')
		for (i in 2..parts.size) {
			val prefix = parts.take(i).joinToString("\\")
			memScoped { CreateDirectoryW(prefix.wcstr.ptr.reinterpret(), null) }
		}
		return MingwFiles.isDirectory(path)
	}

	override fun start(argv: List<String>): Child? = null
}

actual fun systemServe(): ServeSystem = MingwServe
