package dev.gmitch215.drift.cli.serve

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmServeTest {
	private val system = systemServe()
	private val root: File = Files.createTempDirectory("drift-serve").toFile()
	private val dist = File(root, "dist").apply { mkdirs() }
	private val running = mutableListOf<Pair<Server, Acceptor>>()

	init {
		File(dist, "index.html").writeText("<title>Drift Studio</title>")
		File(dist, "app.wasm").writeBytes(ByteArray(300000) { it.toByte() })
		File(root, "secret.txt").writeText("outside")
		val link = File(dist, "leak.txt").toPath()
		runCatching { Files.createSymbolicLink(link, File(root, "secret.txt").toPath()) }
	}

	@AfterTest
	fun cleanUp() {
		running.forEach { (server, acceptor) ->
			server.stop()
			acceptor.close()
		}
		root.deleteRecursively()
	}

	private fun start(
		bind: Bind = Bind.LOOPBACK,
		tls: TlsFiles? = null,
		policy: HostPolicy = HostPolicy(),
	): Acceptor {
		val acceptor = system.listen(bind, 0, tls)
		val site = StaticSite(dist.path, system.files, policy)
		val server = Server(system, site, Limits(headMillis = 3000))
		running += server to acceptor
		Thread { server.run(acceptor, 2000) }.apply {
			isDaemon = true
			start()
		}
		return acceptor
	}

	private fun exchange(socket: Socket, request: String): String {
		socket.getOutputStream().write(request.encodeToByteArray())
		socket.getOutputStream().flush()
		val out = ByteArrayOutputStream()
		socket.getInputStream().copyTo(out)
		return out.toByteArray().decodeToString()
	}

	private fun get(
		port: Int,
		target: String,
		host: String = "localhost:$port",
		method: String = "GET",
	): String = Socket("127.0.0.1", port).use {
		it.soTimeout = 4000
		exchange(it, "$method $target HTTP/1.1\r\nHost: $host\r\nAccept: */*\r\n\r\n")
	}

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
		val wasm = get(acceptor.port, "/app.wasm")
		assertTrue("Content-Type: application/wasm" in wasm)
		assertEquals(300000, wasm.substringAfter("\r\n\r\n").length)
	}

	@Test
	fun theServerRefusesTraversalAndForeignHostsOnARealSocket() {
		val acceptor = start()
		assertTrue(get(acceptor.port, "/../secret.txt").startsWith("HTTP/1.1 400"))
		assertTrue(get(acceptor.port, "/%2e%2e/secret.txt").startsWith("HTTP/1.1 400"))
		assertTrue(get(acceptor.port, "/leak.txt").startsWith("HTTP/1.1 404"))
		assertTrue(get(acceptor.port, "/", host = "evil.example").startsWith("HTTP/1.1 403"))
		val foreign = get(acceptor.port, "/", host = "evil.example:${acceptor.port}")
		assertTrue(foreign.startsWith("HTTP/1.1 403"))
	}

	@Test
	fun anIdleConnectionDoesNotBlockTheOthers() {
		val acceptor = start()
		val idle = Socket("127.0.0.1", acceptor.port)
		try {
			val done = CountDownLatch(4)
			val statuses = java.util.concurrent.ConcurrentLinkedQueue<String>()
			repeat(4) {
				Thread {
					statuses += get(acceptor.port, "/").lineSequence().first()
					done.countDown()
				}.start()
			}
			assertTrue(done.await(5, TimeUnit.SECONDS))
			assertEquals(List(4) { "HTTP/1.1 200 OK" }, statuses.toList())
		} finally {
			idle.close()
		}
	}

	@Test
	fun stoppingTheServerReleasesThePort() {
		val acceptor = start()
		val port = acceptor.port
		assertTrue(get(port, "/").startsWith("HTTP/1.1 200"))
		running.single().first.stop()
		Thread.sleep(600)
		assertFailsWith<java.io.IOException> { Socket("127.0.0.1", port).close() }
	}

	@Test
	fun theListenerIsNotReachableOnAnotherInterface() {
		val acceptor = start()
		val lan = NetworkInterface.getNetworkInterfaces().toList()
			.filter { it.isUp && !it.isLoopback }
			.flatMap { it.inetAddresses.toList() }.firstOrNull { it.address.size == 4 }
			?: return
		assertFailsWith<java.io.IOException> {
			Socket().use { it.connect(InetSocketAddress(lan, acceptor.port), 1000) }
		}
	}

	@Test
	fun bindAllListensOnTheWildcardAddress() {
		val acceptor = start(Bind.ALL, policy = HostPolicy(anyAddress = true))
		val addresses = acceptor.addresses.toString()
		assertTrue(acceptor.addresses.single().startsWith("0.0.0.0:"), addresses)
		assertTrue(get(acceptor.port, "/", host = "10.0.0.5").startsWith("HTTP/1.1 200"))
	}

	@Test
	fun aBusyPortIsAClearError() {
		val acceptor = start()
		val failure = assertFailsWith<ServeFailure> {
			system.listen(Bind.LOOPBACK, acceptor.port, null)
		}
		assertTrue("pass --port 0" in failure.message.orEmpty(), failure.message)
	}

	@Test
	fun localhostResolvesAndAnUnknownNameDoesNot() {
		assertTrue(system.resolve("localhost").any { it == "127.0.0.1" || it == "::1" })
		assertEquals(emptyList(), system.resolve("no-such-host.invalid"))
	}

	@Test
	fun filesResolveSymlinksAndReadWholeFiles() {
		val files = system.files
		val index = File(dist, "index.html")
		assertEquals(index.canonicalPath, files.canonical(index.path))
		assertNull(files.canonical(File(dist, "nope").path))
		assertTrue(files.isDirectory(dist.path))
		assertEquals("<title>Drift Studio</title>", files.read(index.path)?.decodeToString())
		assertNull(files.read(dist.path))
		val link = File(dist, "leak.txt")
		if (Files.isSymbolicLink(link.toPath())) {
			assertEquals(File(root, "secret.txt").canonicalPath, files.canonical(link.path))
		}
	}

	@Test
	fun modeAndPrivateDirectoriesUseUnixPermissions() {
		if (File.separatorChar == '\\') return
		val file = File(root, "k.pem").apply { writeText("x") }
		val owner = PosixFilePermissions.fromString("rw-------")
		Files.setPosixFilePermissions(file.toPath(), owner)
		assertEquals(384, system.mode(file.path))
		assertNull(system.mode(File(root, "missing").path))
		val nested = File(root, "a/b/c")
		assertTrue(system.makePrivateDirectory(nested.path))
		assertEquals(448, system.mode(nested.path))
		assertTrue(!system.makePrivateDirectory(File(file, "x").path))
	}

	@Test
	fun childProcessesStartAndStop() {
		if (File.separatorChar == '\\') return
		val child = assertNotNull(system.start(listOf("sleep", "30")))
		child.stop()
		assertNull(system.start(listOf("/no/such/program")))
	}

	@Test
	fun spawnRunsOnAnotherThreadAndInterruptStartsClear() {
		val latch = CountDownLatch(1)
		var name = ""
		system.spawn {
			name = Thread.currentThread().name
			latch.countDown()
		}
		assertTrue(latch.await(2, TimeUnit.SECONDS))
		assertTrue(name != Thread.currentThread().name)
		system.sleep(1)
		assertTrue(!system.interrupted())
	}

	private fun pem(label: String, der: ByteArray) = "-----BEGIN $label-----\n" +
			Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
			"\n-----END $label-----\n"

	private fun keytool(): Pair<File, File> {
		val store = File(root, "scratch.p12")
		val bin = File(System.getProperty("java.home"), "bin/keytool").path
		val process = ProcessBuilder(
			bin, "-genkeypair", "-alias", "drift", "-keyalg", "RSA", "-keysize", "2048",
			"-validity", "2", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
			"-storetype", "PKCS12",
			"-keystore", store.path, "-storepass", "changeit",
		).redirectErrorStream(true).start()
		val log = process.inputStream.readBytes().decodeToString()
		assertEquals(0, process.waitFor(), log)
		val keys = KeyStore.getInstance("PKCS12").apply {
			store.inputStream().use { load(it, "changeit".toCharArray()) }
		}
		val der = keys.getCertificate("drift").encoded
		val cert = File(root, "cert.pem").apply { writeText(pem("CERTIFICATE", der)) }
		val key = File(root, "key.pem").apply {
			writeText(pem("PRIVATE KEY", keys.getKey("drift", "changeit".toCharArray()).encoded))
		}
		return cert to key
	}

	private fun trusting(cert: File): SSLContext {
		val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
		val parsed = CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream())
		store.setCertificateEntry("drift", parsed)
		val algorithm = TrustManagerFactory.getDefaultAlgorithm()
		val factory = TrustManagerFactory.getInstance(algorithm).apply { init(store) }
		return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
	}

	@Test
	fun httpsServesPemFilesAndOnlyTrustedClientsConnect() {
		val (cert, key) = keytool()
		val acceptor = start(tls = TlsFiles(cert.path, key.path))
		val trusted = trusting(cert).socketFactory.createSocket("localhost", acceptor.port)
		trusted.use {
			it.soTimeout = 4000
			val reply = exchange(it, "GET / HTTP/1.1\r\nHost: localhost:${acceptor.port}\r\n\r\n")
			assertTrue(reply.startsWith("HTTP/1.1 200 OK"), reply)
			assertTrue(reply.endsWith("<title>Drift Studio</title>"))
		}
		val defaults = SSLContext.getDefault().socketFactory
		val untrusted = defaults.createSocket("localhost", acceptor.port)
		assertFailsWith<SSLHandshakeException> {
			untrusted.use {
				it.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
				it.getInputStream().read()
			}
		}
		val plain = Socket("127.0.0.1", acceptor.port).use {
			exchange(it.apply { soTimeout = 3000 }, "GET / HTTP/1.1\r\n\r\n")
		}
		assertTrue(!plain.startsWith("HTTP/1.1 200"), plain)
	}

	@Test
	fun brokenTlsMaterialIsExplainedBeforeAnythingListens() {
		val (cert, key) = keytool()
		val rsaText = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n"
		val rsa = File(root, "rsa.pem").apply { writeText(rsaText) }
		val garbage = File(root, "garbage.pem").apply { writeText("not pem") }
		val other = File(root, "other.pem").apply {
			val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
			writeText(pem("PRIVATE KEY", generator.generateKeyPair().private.encoded))
		}
		val badKey = File(root, "bad.pem").apply { writeText(pem("PRIVATE KEY", ByteArray(20))) }
		fun failure(c: File, k: File) = assertFailsWith<ServeFailure> {
			system.listen(Bind.LOOPBACK, 0, TlsFiles(c.path, k.path))
		}.message.orEmpty()
		assertTrue("not an unencrypted PKCS#8 PEM" in failure(cert, rsa))
		assertTrue("openssl pkcs8 -topk8" in failure(cert, rsa))
		val noCert = failure(garbage, key)
		assertTrue("cannot read the certificate" in noCert || "holds no certificate" in noCert)
		assertTrue("cannot read the certificate" in failure(File(root, "missing"), key))
		assertTrue("cannot read the key" in failure(cert, File(root, "missing")))
		assertTrue("does not match" in failure(cert, other))
		assertTrue("not an RSA, EC or Ed25519 key" in failure(cert, badKey))
	}
}
