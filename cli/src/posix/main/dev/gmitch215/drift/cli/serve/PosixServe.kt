@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.serve

import dev.gmitch215.drift.cli.readBinaryFile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.IPV6_V6ONLY
import platform.posix.POLLIN
import platform.posix.SIGINT
import platform.posix.SIGPIPE
import platform.posix.SIGTERM
import platform.posix.SIG_IGN
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.free
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_tVar
import platform.posix.realpath
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.signal
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.stat
import platform.posix.strerror
import platform.posix.timeval
import platform.posix.usleep
import kotlin.concurrent.AtomicInt

private const val IO_SECONDS = 5
private const val BACKLOG = 128
private const val SOCK_STREAM_ID = 1
private const val IPPROTO_IPV6_ID = 41

private val interrupt = AtomicInt(0)

private fun reason() = strerror(errno)?.toKString() ?: "error $errno"

private fun option(fd: Int, level: Int, name: Int, value: Int) = memScoped {
	val v = alloc<kotlinx.cinterop.IntVar>()
	v.value = value
	setsockopt(fd, level, name, v.ptr, sizeOf<kotlinx.cinterop.IntVar>().convert()) == 0
}

private fun timeouts(fd: Int) = memScoped {
	val tv = alloc<timeval>()
	tv.tv_sec = IO_SECONDS.convert()
	tv.tv_usec = 0.convert()
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
	setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, tv.ptr, sizeOf<timeval>().convert())
}

private class FdConnection(private val fd: Int, private val tls: TlsHandshake?) : Connection {
	private var session: TlsSession? = null
	private var started = false

	private fun session(): TlsSession? {
		if (tls == null) return null
		if (!started) {
			started = true
			session = tls.accept(fd)
		}
		return session
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (length <= 0) return 0
		val ssl = session()
		if (tls != null && ssl == null) return -1
		val n = buffer.usePinned {
			ssl?.read(it.addressOf(offset), length)
				?: recv(fd, it.addressOf(offset), length.convert(), 0).toInt()
		}
		return if (n <= 0) -1 else n
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
		val ssl = session()
		if (tls != null && ssl == null) return false
		var at = 0
		while (at < length) {
			val n = buffer.usePinned {
				ssl?.write(it.addressOf(offset + at), length - at)
					?: send(fd, it.addressOf(offset + at), (length - at).convert(), 0).toInt()
			}
			if (n <= 0) return false
			at += n
		}
		return true
	}

	override fun close() {
		session?.close()
		session = null
		close(fd)
	}
}

private class PosixAcceptor(
	private val fds: List<Int>,
	override val addresses: List<String>,
	override val port: Int,
	private val tls: TlsHandshake?,
) : Acceptor {
	private var closed = false

	override fun accept(timeoutMillis: Int): Connection? = memScoped {
		val set = allocArray<pollfd>(fds.size)
		for (i in fds.indices) {
			set[i].fd = fds[i]
			set[i].events = POLLIN.convert()
			set[i].revents = 0
		}
		if (poll(set, fds.size.convert(), timeoutMillis) <= 0) return null
		for (i in fds.indices) {
			if (set[i].revents.toInt() and POLLIN == 0) continue
			val client = accept(fds[i], null, null)
			if (client < 0) return null
			timeouts(client)
			return FdConnection(client, tls)
		}
		null
	}

	override fun close() {
		if (closed) return
		closed = true
		fds.forEach { close(it) }
	}
}

private fun listener(v6: Boolean, all: Boolean, port: Int): Int = memScoped {
	val fd = socket(if (v6) AF_INET6 else AF_INET, SOCK_STREAM_ID, 0)
	if (fd < 0) return -1
	option(fd, SOL_SOCKET, SO_REUSEADDR, 1)
	val bound = if (v6) {
		option(fd, IPPROTO_IPV6_ID, IPV6_V6ONLY, 1)
		val address = alloc<sockaddr_in6>()
		address.sin6_family = AF_INET6.convert()
		address.sin6_port = swap(port).convert()
		if (!all) address.sin6_addr.ptr.reinterpret<ByteVar>()[15] = 1
		bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert())
	} else {
		val address = alloc<sockaddr_in>()
		address.sin_family = AF_INET.convert()
		address.sin_port = swap(port).convert()
		address.sin_addr.s_addr = (if (all) 0x00000000 else 0x0100007f).convert()
		bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
	}
	if (bound != 0 || listen(fd, BACKLOG) != 0) {
		val saved = reason()
		close(fd)
		throw ServeFailure(saved)
	}
	fd
}

private fun localPort(fd: Int): Int = memScoped {
	val address = alloc<sockaddr_in>()
	val size = alloc<socklen_tVar>()
	size.value = sizeOf<sockaddr_in>().convert()
	getsockname(fd, address.ptr.reinterpret(), size.ptr)
	swap(address.sin_port.toInt())
}

private object PosixFiles : SiteFiles {
	override val separator = '/'
	override val ignoreCase = false

	override fun canonical(path: String): String? {
		val resolved = realpath(path, null) ?: return null
		return try {
			resolved.toKString()
		} finally {
			free(resolved)
		}
	}

	override fun isDirectory(path: String): Boolean {
		val dir = opendir(path) ?: return false
		closedir(dir)
		return true
	}

	override fun read(path: String): ByteArray? {
		if (isDirectory(path)) return null
		return readBinaryFile(path)
	}
}

// an orphan that nobody reaps (pid 1 of a plain container) stays a zombie and still answers kill 0
internal fun processAlive(pid: Int): Boolean {
	if (platform.posix.kill(pid, 0) != 0) return false
	val stat = readBinaryFile("/proc/$pid/stat")?.decodeToString() ?: return true
	return stat.substringAfterLast(") ").firstOrNull() != 'Z'
}

private class PosixChild(private val pid: Int) : Child {
	override fun stop() {
		platform.posix.kill(pid, SIGTERM)
		for (i in 0 until 40) {
			if (!processAlive(pid)) return
			usleep(50000u)
		}
		platform.posix.kill(pid, platform.posix.SIGKILL)
	}
}

private fun quoted(text: String) = "'" + text.replace("'", "'\\''") + "'"

private object PosixServe : ServeSystem {
	override val files = PosixFiles
	override val tlsUnavailable: String?
		get() = try {
			openSsl()
			null
		} catch (e: ServeFailure) {
			e.message
		}

	override fun listen(bind: Bind, port: Int, tls: TlsFiles?): Acceptor {
		signal(SIGPIPE, SIG_IGN)
		val handshake = tls?.let { openSsl().context(it) }
		val all = bind == Bind.ALL
		val fds = mutableListOf<Int>()
		val addresses = mutableListOf<String>()
		val first = try {
			listener(false, all, port)
		} catch (e: ServeFailure) {
			throw ServeFailure(
				"cannot listen on port $port: ${e.message}; pass --port 0 to take a free port",
			)
		}
		val real = localPort(first)
		fds += first
		addresses += (if (all) "0.0.0.0" else "127.0.0.1") + ":$real"
		try {
			fds += listener(true, all, real)
			addresses += (if (all) "[::]" else "[::1]") + ":$real"
		} catch (_: ServeFailure) {
			// no IPv6 loopback here; IPv4 alone still serves localhost
		}
		return PosixAcceptor(fds, addresses, real, handshake)
	}

	override fun spawn(block: () -> Unit): Boolean {
		val ref = StableRef.create(block)
		return memScoped {
			val thread = alloc<pthread_tVar>()
			val rc = pthread_create(
				thread.ptr,
				null,
				staticCFunction { arg ->
					val holder = arg!!.asStableRef<() -> Unit>()
					try {
						holder.get().invoke()
					} finally {
						holder.dispose()
					}
					null
				},
				ref.asCPointer(),
			)
			if (rc != 0) {
				ref.dispose()
			} else {
				pthread_detach(thread.value)
			}
			rc == 0
		}
	}

	override fun sleep(millis: Int) {
		usleep((millis * 1000).convert())
	}

	override fun trapInterrupt() {
		interrupt.value = 0
		val handler = staticCFunction<Int, Unit> { interrupt.value = 1 }
		signal(SIGINT, handler)
		signal(SIGTERM, handler)
		signal(SIGPIPE, SIG_IGN)
	}

	override fun interrupted() = interrupt.value != 0

	override fun resolve(name: String): List<String> = memScoped {
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
				val v6 = address.reinterpret<sockaddr_in6>().pointed
				val raw = v6.sin6_addr.ptr.reinterpret<ByteVar>()
				found += addressText((0 until 16).map { raw[it].toInt() and 0xff })
			}
			item = entry.ai_next
		}
		freeaddrinfo(result.value)
		found.distinct()
	}

	override fun mode(path: String): Int? = memScoped {
		val info = alloc<stat>()
		if (stat(path, info.ptr) != 0) null else info.st_mode.toInt() and 4095
	}

	override fun makePrivateDirectory(path: String): Boolean {
		val parts = path.split('/')
		for (i in 1..parts.size) {
			val prefix = parts.take(i).joinToString("/")
			if (prefix.isNotEmpty()) mkdir(prefix, 448.convert())
		}
		return PosixFiles.isDirectory(path)
	}

	override fun start(argv: List<String>): Child? {
		val line = argv.joinToString(" ") { quoted(it) } + " >/dev/null 2>&1 & echo \$!"
		val pipe = platform.posix.popen(line, "r") ?: return null
		val pid = memScoped {
			val text = allocArray<ByteVar>(32)
			val read = platform.posix.fgets(text, 32, pipe)?.toKString()?.trim()?.toIntOrNull()
			platform.posix.pclose(pipe)
			read
		} ?: return null
		usleep(150000u)
		return if (processAlive(pid)) PosixChild(pid) else null
	}
}

actual fun systemServe(): ServeSystem = PosixServe
