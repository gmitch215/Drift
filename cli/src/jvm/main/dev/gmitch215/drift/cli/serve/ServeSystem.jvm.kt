package dev.gmitch215.drift.cli.serve

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

private const val IO_TIMEOUT = 5000
private const val MAX_FILE = 512L * 1024 * 1024

private class SocketConnection(private val socket: Socket) : Connection {
	override fun read(buffer: ByteArray, offset: Int, length: Int) = try {
		socket.getInputStream().read(buffer, offset, length)
	} catch (_: IOException) {
		-1
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) = try {
		socket.getOutputStream().write(buffer, offset, length)
		socket.getOutputStream().flush()
		true
	} catch (_: IOException) {
		false
	}

	override fun close() {
		try {
			socket.close()
		} catch (_: IOException) {
		}
	}
}

private class JvmAcceptor(private val sockets: List<ServerSocket>) : Acceptor {
	private val queue = LinkedBlockingQueue<Connection>()
	private val closed = AtomicBoolean(false)

	override val port = sockets.first().localPort
	override val addresses = sockets.map {
		val address = it.inetAddress
		when {
			address.isAnyLocalAddress -> "0.0.0.0:${it.localPort}"
			address.isLoopbackAddress && address.address.size == 16 -> "[::1]:${it.localPort}"
			address.address.size == 16 -> "[${address.hostAddress.substringBefore('%')}]:$port"
			else -> "${address.hostAddress}:${it.localPort}"
		}
	}

	init {
		for (socket in sockets) {
			Thread {
				while (!closed.get()) {
					try {
						val accepted = socket.accept()
						accepted.soTimeout = IO_TIMEOUT
						queue.put(SocketConnection(accepted))
					} catch (_: IOException) {
						if (socket.isClosed) break
					}
				}
			}.apply {
				isDaemon = true
				start()
			}
		}
	}

	override fun accept(timeoutMillis: Int): Connection? {
		val next = queue.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
		return next
	}

	override fun close() {
		if (!closed.getAndSet(true)) {
			sockets.forEach { runCatching { it.close() } }
			queue.forEach { it.close() }
		}
	}
}

private object JvmFiles : SiteFiles {
	override val separator = File.separatorChar
	override val ignoreCase = System.getProperty("os.name").lowercase().startsWith("windows")

	override fun canonical(path: String) =
		runCatching { Path.of(path).toRealPath().toString() }.getOrNull()

	override fun isDirectory(path: String) = File(path).isDirectory

	override fun read(path: String): ByteArray? {
		val file = File(path)
		if (!file.isFile || file.length() > MAX_FILE) return null
		return runCatching { file.readBytes() }.getOrNull()
	}
}

private class JvmChild(private val process: Process) : Child {
	override fun stop() {
		process.destroy()
		if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
	}
}

private fun matches(key: PrivateKey, cert: X509Certificate): Boolean = try {
	val algorithm = when (key.algorithm) {
		"RSA" -> "SHA256withRSA"
		"EC" -> "SHA256withECDSA"
		else -> key.algorithm
	}
	val probe = "drift".toByteArray()
	val signed = Signature.getInstance(algorithm).run {
		initSign(key)
		update(probe)
		sign()
	}
	Signature.getInstance(algorithm).run {
		initVerify(cert.publicKey)
		update(probe)
		verify(signed)
	}
} catch (_: GeneralSecurityException) {
	false
}

private fun pemBlock(text: String, label: String): ByteArray? {
	val begin = "-----BEGIN $label-----"
	val start = text.indexOf(begin)
	if (start < 0) return null
	val end = text.indexOf("-----END $label-----", start)
	if (end < 0) return null
	val body = text.substring(start + begin.length, end).filterNot { it.isWhitespace() }
	return Base64.getDecoder().decode(body)
}

internal fun sslContext(tls: TlsFiles): SSLContext {
	val certificates = try {
		val factory = CertificateFactory.getInstance("X.509")
		File(tls.cert).inputStream().use { factory.generateCertificates(it) }
	} catch (e: Exception) {
		throw ServeFailure("cannot read the certificate ${tls.cert}: ${e.message}")
	}
	if (certificates.isEmpty()) throw ServeFailure("${tls.cert} holds no certificate")
	val keyText = try {
		File(tls.key).readText()
	} catch (e: IOException) {
		throw ServeFailure("cannot read the key ${tls.key}: ${e.message}")
	}
	val der = pemBlock(keyText, "PRIVATE KEY") ?: throw ServeFailure(
		"${tls.key} is not an unencrypted PKCS#8 PEM (BEGIN PRIVATE KEY); " +
			"convert it with: openssl pkcs8 -topk8 -nocrypt -in old.pem -out ${tls.key}",
	)
	val key = listOf("RSA", "EC", "Ed25519").firstNotNullOfOrNull {
		val spec = PKCS8EncodedKeySpec(der)
		runCatching { KeyFactory.getInstance(it).generatePrivate(spec) }.getOrNull()
	} ?: throw ServeFailure("${tls.key} is not an RSA, EC or Ed25519 key")
	if (!matches(key, certificates.first() as X509Certificate)) {
		throw ServeFailure("the key ${tls.key} does not match ${tls.cert}")
	}
	val store = KeyStore.getInstance("PKCS12")
	store.load(null, null)
	try {
		store.setKeyEntry("drift", key, CharArray(0), certificates.toTypedArray())
		val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
		factory.init(store, CharArray(0))
		return SSLContext.getInstance("TLS").apply { init(factory.keyManagers, null, null) }
	} catch (e: GeneralSecurityException) {
		throw ServeFailure("cannot use the key ${tls.key} with ${tls.cert}: ${e.message}")
	}
}

private object JvmServe : ServeSystem {
	private val signalled = AtomicBoolean(false)

	override val files = JvmFiles
	override val tlsUnavailable: String? = null

	override fun listen(bind: Bind, port: Int, tls: TlsFiles?): Acceptor {
		val factory = tls?.let { sslContext(it).serverSocketFactory }
		fun open(address: InetAddress?, at: Int): ServerSocket {
			val socket = factory?.createServerSocket(at, BACKLOG, address)
				?: ServerSocket(at, BACKLOG, address)
			return socket
		}
		val sockets = mutableListOf<ServerSocket>()
		try {
			if (bind == Bind.ALL) {
				sockets += open(null, port)
			} else {
				sockets += open(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
				runCatching { open(InetAddress.getByName("::1"), sockets.first().localPort) }
					.onSuccess { sockets += it }
			}
		} catch (e: IOException) {
			sockets.forEach { runCatching { it.close() } }
			throw ServeFailure(
				"cannot listen on port $port: ${e.message}; pass --port 0 to take a free port",
			)
		}
		return JvmAcceptor(sockets)
	}

	override fun spawn(block: () -> Unit) = try {
		Thread(block).apply {
			isDaemon = true
			start()
		}
		true
	} catch (_: OutOfMemoryError) {
		false
	}

	override fun sleep(millis: Int) = Thread.sleep(millis.toLong())

	override fun trapInterrupt() {
		val main = Thread.currentThread()
		Runtime.getRuntime().addShutdownHook(
			Thread {
				signalled.set(true)
				main.join(DRAIN_MILLIS)
			},
		)
	}

	override fun interrupted() = signalled.get()

	override fun resolve(name: String) = try {
		InetAddress.getAllByName(name).map { it.hostAddress.substringBefore('%') }
	} catch (_: UnknownHostException) {
		emptyList()
	}

	override fun mode(path: String): Int? = try {
		Files.getPosixFilePermissions(Path.of(path)).sumOf { MODES.getValue(it) }
	} catch (_: Exception) {
		null
	}

	override fun makePrivateDirectory(path: String) = try {
		val posix = Path.of(path).fileSystem.supportedFileAttributeViews().contains("posix")
		if (posix) {
			Files.createDirectories(
				Path.of(path),
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
			)
		} else {
			Files.createDirectories(Path.of(path))
		}
		true
	} catch (_: IOException) {
		false
	}

	override fun start(argv: List<String>): Child? = try {
		val builder = ProcessBuilder(argv).redirectErrorStream(true)
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
		JvmChild(builder.start())
	} catch (_: IOException) {
		null
	}

	private const val BACKLOG = 128
	private const val DRAIN_MILLIS = 8000L
	private val MODES = mapOf(
		PosixFilePermission.OWNER_READ to 256,
		PosixFilePermission.OWNER_WRITE to 128,
		PosixFilePermission.OWNER_EXECUTE to 64,
		PosixFilePermission.GROUP_READ to 32,
		PosixFilePermission.GROUP_WRITE to 16,
		PosixFilePermission.GROUP_EXECUTE to 8,
		PosixFilePermission.OTHERS_READ to 4,
		PosixFilePermission.OTHERS_WRITE to 2,
		PosixFilePermission.OTHERS_EXECUTE to 1,
	)
}

actual fun systemServe(): ServeSystem = JvmServe
