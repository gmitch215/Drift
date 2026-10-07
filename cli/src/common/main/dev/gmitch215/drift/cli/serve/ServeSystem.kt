package dev.gmitch215.drift.cli.serve

/** A failure the user can act on; the message is printed as is. */
internal class ServeFailure(message: String, val usage: Boolean = false) : Exception(message)

/** One accepted socket, already past the TLS handshake setup when the listener is TLS. */
interface Connection {
	/** Reads up to [length] bytes; -1 at the end of the stream, on an error or on a timeout. */
	fun read(buffer: ByteArray, offset: Int, length: Int): Int

	fun write(buffer: ByteArray, offset: Int, length: Int): Boolean

	fun close()
}

interface Acceptor {
	/** Every address being listened on, as host and port text. */
	val addresses: List<String>
	val port: Int

	/** The next connection, or null when none arrived within [timeoutMillis]. */
	fun accept(timeoutMillis: Int): Connection?

	fun close()
}

enum class Bind { LOOPBACK, ALL }

/** PEM files; the key is PKCS#8 on the JVM and anything OpenSSL reads on native. */
class TlsFiles(val cert: String, val key: String)

interface Child {
	fun stop()
}

interface SiteFiles {
	val separator: Char
	val ignoreCase: Boolean

	/** The absolute path with every symlink and `..` resolved, or null when it does not exist. */
	fun canonical(path: String): String?

	fun isDirectory(path: String): Boolean

	fun read(path: String): ByteArray?
}

/** Everything `drift serve` needs from the operating system. */
interface ServeSystem {
	val files: SiteFiles

	/** Why this build cannot serve TLS, or null when it can. */
	val tlsUnavailable: String?

	/** Binds loopback (IPv4, and IPv6 when available) or every interface; port 0 picks one. */
	fun listen(bind: Bind, port: Int, tls: TlsFiles?): Acceptor

	/** Runs [block] on another thread; false when no thread could be started. */
	fun spawn(block: () -> Unit): Boolean

	fun sleep(millis: Int)

	/** Makes SIGINT and SIGTERM set the flag that [ServeSystem.interrupted] reads. */
	fun trapInterrupt()

	fun interrupted(): Boolean

	/** Numeric addresses `getaddrinfo` returns for [name]; empty when it does not resolve. */
	fun resolve(name: String): List<String>

	/** Unix permission bits of [path], or null when unknown or not a unix file system. */
	fun mode(path: String): Int?

	/** Creates [path] and its parents, readable by the current user only. */
	fun makePrivateDirectory(path: String): Boolean

	/** Starts a long-running program; null when it cannot be started. */
	fun start(argv: List<String>): Child?
}

internal object UnsupportedServe : ServeSystem {
	override val files = object : SiteFiles {
		override val separator = '/'
		override val ignoreCase = false

		override fun canonical(path: String): String? = null

		override fun isDirectory(path: String) = false

		override fun read(path: String): ByteArray? = null
	}
	override val tlsUnavailable = "this build cannot serve at all"

	override fun listen(bind: Bind, port: Int, tls: TlsFiles?): Acceptor =
		throw ServeFailure("drift serve needs the native executable or the JVM build")

	override fun spawn(block: () -> Unit) = false

	override fun sleep(millis: Int) = Unit

	override fun trapInterrupt() = Unit

	override fun interrupted() = true

	override fun resolve(name: String): List<String> = emptyList()

	override fun mode(path: String): Int? = null

	override fun makePrivateDirectory(path: String) = false

	override fun start(argv: List<String>): Child? = null
}

expect fun systemServe(): ServeSystem
