@file:OptIn(ExperimentalForeignApi::class)

package dev.gmitch215.drift.cli.serve

import dev.gmitch215.drift.cli.readBinaryFile
import dev.gmitch215.drift.cli.writeBinaryFile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.close
import platform.posix.connect
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.symlink
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosixServeTest {
	private val system = systemServe()
	private val root = tempDir()
	private val dist = "$root/dist"
	private val made = mutableListOf<String>()
	private val running = mutableListOf<Pair<Server, Acceptor>>()

	init {
		system.makePrivateDirectory(dist)
		write("$dist/index.html", "<title>Drift Studio</title>")
		write("$root/secret.txt", "outside")
		symlink("$root/secret.txt", "$dist/leak.txt")
		made += "$dist/leak.txt"
	}

	private fun tempDir(): String {
		val base = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
		return memScoped {
			val pattern = "$base/drift-serveXXXXXX"
			val template = pattern.encodeToByteArray().copyOf(pattern.length + 1)
			template.usePinned { mkdtemp(it.addressOf(0)) }
			template.decodeToString(endIndex = template.size - 1)
		}
	}

	private fun write(path: String, text: String) {
		writeBinaryFile(path, text.encodeToByteArray())
		made += path
	}

	@AfterTest
	fun cleanUp() {
		running.forEach { (server, acceptor) ->
			server.stop()
			acceptor.close()
		}
		made.forEach { platform.posix.remove(it) }
		platform.posix.rmdir(dist)
		platform.posix.rmdir(root)
	}

	private fun start(bind: Bind = Bind.LOOPBACK, policy: HostPolicy = HostPolicy()): Acceptor {
		val acceptor = system.listen(bind, 0, null)
		val server = Server(system, StaticSite(dist, system.files, policy))
		running += server to acceptor
		system.spawn { server.run(acceptor, 2000) }
		return acceptor
	}

	private fun fetch(port: Int, request: String): String = memScoped {
		val fd = socket(AF_INET, 1, 0)
		val address = alloc<sockaddr_in>()
		address.sin_family = AF_INET.convert()
		address.sin_port = swap(port).convert()
		val raw = address.sin_addr.ptr.reinterpret<ByteVar>()
		raw[0] = 127
		raw[3] = 1
		if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
			close(fd)
			return "connect failed"
		}
		val bytes = request.encodeToByteArray()
		bytes.usePinned { send(fd, it.addressOf(0), bytes.size.convert(), 0) }
		val out = StringBuilder()
		val buffer = ByteArray(65536)
		while (true) {
			val n = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0).toInt() }
			if (n <= 0) break
			out.append(buffer.decodeToString(0, n))
		}
		close(fd)
		out.toString()
	}

	private fun get(
		port: Int,
		target: String,
		host: String = "localhost:$port",
		method: String = "GET",
	) = fetch(port, "$method $target HTTP/1.1\r\nHost: $host\r\nAccept: */*\r\n\r\n")

	@Test
	fun loopbackListenersAnswerGetAndHead() {
		val acceptor = start()
		val loopback = acceptor.addresses.all {
			it.startsWith("127.0.0.1:") || it.startsWith("[::1]")
		}
		assertTrue(loopback)
		val page = get(acceptor.port, "/")
		assertTrue(page.startsWith("HTTP/1.1 200 OK"), page)
		assertTrue(page.endsWith("<title>Drift Studio</title>"))
		val head = get(acceptor.port, "/", method = "HEAD")
		assertTrue(head.startsWith("HTTP/1.1 200 OK") && head.endsWith("\r\n\r\n"), head)
	}

	@Test
	fun traversalForeignHostsAndSymlinksAreRefused() {
		val acceptor = start()
		assertTrue(get(acceptor.port, "/../secret.txt").startsWith("HTTP/1.1 400"))
		assertTrue(get(acceptor.port, "/%2e%2e/secret.txt").startsWith("HTTP/1.1 400"))
		assertTrue(get(acceptor.port, "/leak.txt").startsWith("HTTP/1.1 404"))
		assertTrue(get(acceptor.port, "/", host = "evil.example").startsWith("HTTP/1.1 403"))
	}

	@Test
	fun anIdleConnectionDoesNotBlockOtherRequests() {
		val acceptor = start()
		val idle = memScoped {
			val fd = socket(AF_INET, 1, 0)
			val address = alloc<sockaddr_in>()
			address.sin_family = AF_INET.convert()
			address.sin_port = swap(acceptor.port).convert()
			val raw = address.sin_addr.ptr.reinterpret<ByteVar>()
			raw[0] = 127
			raw[3] = 1
			connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
			fd
		}
		val done = AtomicInt(0)
		val ok = AtomicInt(0)
		repeat(4) {
			system.spawn {
				if (get(acceptor.port, "/").startsWith("HTTP/1.1 200 OK")) ok.incrementAndGet()
				done.incrementAndGet()
			}
		}
		var waited = 0
		while (done.value < 4 && waited < 4000) {
			system.sleep(10)
			waited += 10
		}
		close(idle)
		assertEquals(4, ok.value)
	}

	@Test
	fun stoppingTheServerReleasesThePort() {
		val acceptor = start()
		val port = acceptor.port
		assertTrue(get(port, "/").startsWith("HTTP/1.1 200"))
		running.single().first.stop()
		system.sleep(400)
		assertEquals("connect failed", get(port, "/"))
	}

	@Test
	fun bindAllListensOnTheWildcardAddressAndABusyPortFails() {
		val acceptor = start(Bind.ALL, HostPolicy(anyAddress = true))
		assertTrue(acceptor.addresses.first().startsWith("0.0.0.0:"), acceptor.addresses.toString())
		val busy = runCatching { system.listen(Bind.ALL, acceptor.port, null) }.exceptionOrNull()
		val said = busy is ServeFailure && "pass --port 0" in busy.message.orEmpty()
		assertTrue(said, busy.toString())
	}

	@Test
	fun localhostResolvesAndAnUnknownNameDoesNot() {
		assertTrue(system.resolve("localhost").any { it == "127.0.0.1" || it == "::1" })
		assertEquals(emptyList(), system.resolve("no-such-host.invalid"))
	}

	@Test
	fun filesResolveSymlinksAndReadWholeFiles() {
		val files = system.files
		val real = assertNotNull(files.canonical("$dist/index.html"))
		assertTrue(real.endsWith("/dist/index.html"))
		assertNull(files.canonical("$dist/nope"))
		assertTrue(files.isDirectory(dist))
		val page = files.read("$dist/index.html")?.decodeToString()
		assertEquals("<title>Drift Studio</title>", page)
		assertNull(files.read(dist))
		val leak = assertNotNull(files.canonical("$dist/leak.txt"))
		assertTrue(leak.endsWith("/secret.txt") && !leak.contains("/dist/"), leak)
	}

	@Test
	fun modeAndPrivateDirectoriesUseUnixPermissions() {
		val nested = "$root/a/b"
		assertTrue(system.makePrivateDirectory(nested))
		assertEquals(448, system.mode(nested))
		platform.posix.rmdir(nested)
		platform.posix.rmdir("$root/a")
		assertNull(system.mode("$root/missing"))
		val file = "$root/k.pem"
		write(file, "x")
		platform.posix.chmod(file, 384.convert())
		assertEquals(384, system.mode(file))
		assertTrue(!system.makePrivateDirectory("$file/x"))
	}

	@Test
	fun childProcessesStartAndStop() {
		val child = assertNotNull(system.start(listOf("sleep", "30")))
		child.stop()
		assertNull(system.start(listOf("/no/such/program")))
	}

	@Test
	fun anExitedChildThatNobodyReapedIsNotAlive() {
		if (readBinaryFile("/proc/self/stat") == null) {
			println("no /proc here, so zombie detection was not exercised")
			return
		}
		val pipe = assertNotNull(platform.posix.popen("echo \$\$", "r"))
		val pid = memScoped {
			val text = allocArray<ByteVar>(32)
			assertNotNull(platform.posix.fgets(text, 32, pipe)).toKString().trim().toInt()
		}
		var waited = 0
		while (processAlive(pid) && waited < 2000) {
			system.sleep(10)
			waited += 10
		}
		assertTrue(!processAlive(pid))
		assertEquals(0, platform.posix.kill(pid, 0), "the child should be a zombie, not gone")
		platform.posix.pclose(pipe)
		assertTrue(!processAlive(pid))
	}

	@Test
	fun interruptStartsClearAndSpawnRuns() {
		assertTrue(!system.interrupted())
		val ran = AtomicInt(0)
		system.spawn { ran.value = 1 }
		var waited = 0
		while (ran.value == 0 && waited < 2000) {
			system.sleep(5)
			waited += 5
		}
		assertEquals(1, ran.value)
	}

	private fun certificate(): Pair<String, String>? {
		val cert = "$root/cert.pem"
		val key = "$root/key.pem"
		made += listOf(cert, key)
		val generated = shell(
			"openssl req -x509 -newkey rsa:2048 -nodes -keyout '$key' -out '$cert' -days 2 " +
				"-subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1 2>&1",
		)
		assertNotNull(readBinaryFile(cert), generated)
		return cert to key
	}

	private fun missing(vararg tools: String): String? = tools
		.firstOrNull { shell("command -v $it").isBlank() }?.let { "$it is not on PATH" }

	@Test
	fun httpsServesAPemPairAndOnlyTrustedClientsConnect() {
		val absent = system.tlsUnavailable ?: missing("openssl", "curl")
		if (absent != null) {
			println("the https test was not run: $absent")
			return
		}
		val (cert, key) = assertNotNull(certificate())
		val acceptor = system.listen(Bind.LOOPBACK, 0, TlsFiles(cert, key))
		val server = Server(system, StaticSite(dist, system.files, HostPolicy()))
		running += server to acceptor
		system.spawn { server.run(acceptor, 2000) }
		val url = "https://localhost:${acceptor.port}/"
		val reply = shell("curl -sS --max-time 4 --cacert '$cert' -i $url 2>&1")
		assertTrue(reply.startsWith("HTTP/1.1 200 OK"), reply)
		assertTrue(reply.endsWith("<title>Drift Studio</title>"), reply)
		val untrusted = shell("curl -sS --max-time 4 -i $url 2>&1")
		assertTrue(!untrusted.startsWith("HTTP/1.1 200"), untrusted)
		val again = shell("curl -sS --max-time 4 --cacert '$cert' -i $url 2>&1")
		assertTrue(again.startsWith("HTTP/1.1 200 OK"), again)
	}

	@Test
	fun brokenTlsMaterialIsExplainedBeforeAnythingListens() {
		val unavailable = system.tlsUnavailable
		if (unavailable != null) {
			assertTrue("OpenSSL" in unavailable, unavailable)
			assertTrue(refused(TlsFiles("a", "b")) is ServeFailure)
			return
		}
		val garbage = "$root/garbage.pem"
		write(garbage, "not pem")
		val badCert = refused(TlsFiles(garbage, garbage))
		val named = badCert is ServeFailure && "certificate" in badCert.message.orEmpty()
		assertTrue(named, badCert.toString())
		assertTrue(refused(TlsFiles("$root/missing", garbage)) is ServeFailure)
		val absent = missing("openssl")
		if (absent != null) {
			println("the key checks were not run: $absent")
			return
		}
		val (cert, key) = assertNotNull(certificate())
		val failure = refused(TlsFiles(cert, "$root/missing"))
		val mentionsKey = failure is ServeFailure && "key" in failure.message.orEmpty()
		assertTrue(mentionsKey, failure.toString())
		val swapped = refused(TlsFiles(cert, garbage))
		assertTrue(swapped is ServeFailure, swapped.toString())
		val mismatched = refused(TlsFiles(garbage, key))
		assertTrue(mismatched is ServeFailure, mismatched.toString())
	}

	private fun refused(files: TlsFiles) =
		runCatching { system.listen(Bind.LOOPBACK, 0, files) }.exceptionOrNull()

	private fun shell(command: String): String {
		val pipe = platform.posix.popen(command, "r") ?: return ""
		val out = StringBuilder()
		memScoped {
			val text = allocArray<ByteVar>(4096)
			while (platform.posix.fgets(text, 4096, pipe) != null) out.append(text.toKString())
		}
		platform.posix.pclose(pipe)
		return out.toString()
	}
}
