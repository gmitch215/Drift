package dev.gmitch215.drift.cli.serve

/** An in-memory tree: files, directories derived from file paths, and symlinks. */
internal class FakeFiles(
	val tree: MutableMap<String, ByteArray> = linkedMapOf(),
	val links: MutableMap<String, String> = linkedMapOf(),
	override val ignoreCase: Boolean = false,
) : SiteFiles {
	override val separator = '/'

	fun put(path: String, text: String) {
		tree[path] = text.encodeToByteArray()
	}

	private fun normalize(path: String, depth: Int = 0): String? {
		if (depth > 8) return null
		val out = ArrayList<String>()
		for (part in path.split('/')) {
			when (part) {
				"", "." -> Unit

				".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)

				else -> {
					out += part
					val here = "/" + out.joinToString("/")
					val target = links[here]
					if (target != null) {
						val resolved = normalize(target, depth + 1) ?: return null
						out.clear()
						out += resolved.split('/').filter { it.isNotEmpty() }
					}
				}
			}
		}
		return "/" + out.joinToString("/")
	}

	override fun canonical(path: String): String? {
		val real = normalize(path) ?: return null
		val known = real in tree || tree.keys.any { it.startsWith(real.trimEnd('/') + "/") }
		return real.takeIf { known || real == "/" }
	}

	override fun isDirectory(path: String) =
		path !in tree && tree.keys.any { it.startsWith(path.trimEnd('/') + "/") }

	override fun read(path: String) = tree[path]
}

internal class FakeConnection(request: String) : Connection {
	private var input = request.encodeToByteArray()
	private var at = 0
	var chunk = Int.MAX_VALUE
	val output = StringBuilder()
	var closed = false

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (at >= input.size) return -1
		val n = minOf(length, input.size - at, chunk)
		input.copyInto(buffer, offset, at, at + n)
		at += n
		return n
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
		output.append(buffer.decodeToString(offset, offset + length))
		return true
	}

	override fun close() {
		closed = true
	}

	val status get() = output.lineSequence().first()
	val head get() = output.toString().substringBefore("\r\n\r\n")
	val body get() = output.toString().substringAfter("\r\n\r\n", "")
}

internal class FakeAcceptor(
	override val addresses: List<String> = listOf("127.0.0.1:8080", "[::1]:8080"),
	override val port: Int = 8080,
	private val queue: MutableList<Connection> = mutableListOf(),
	private val onEmpty: () -> Unit = {},
) : Acceptor {
	var closed = 0

	override fun accept(timeoutMillis: Int): Connection? {
		if (queue.isEmpty()) {
			onEmpty()
			return null
		}
		return queue.removeAt(0)
	}

	override fun close() {
		closed++
	}
}

internal class FakeChild : Child {
	var stopped = 0

	override fun stop() {
		stopped++
	}
}

internal class FakeServeSystem(
	override val files: FakeFiles = FakeFiles(),
	override val tlsUnavailable: String? = null,
	private val connections: List<String> = emptyList(),
	private val failListen: String? = null,
) : ServeSystem {
	val listened = mutableListOf<Triple<Bind, Int, TlsFiles?>>()
	val modes = mutableMapOf<String, Int>()
	val names = mutableMapOf<String, List<String>>()
	val started = mutableListOf<List<String>>()
	val directories = mutableListOf<String>()
	val children = mutableListOf<FakeChild>()
	val served = mutableListOf<FakeConnection>()
	var acceptor: FakeAcceptor? = null
	var trapped = 0
	var done = false
	var startFails = false
	var hostPort = 8080

	override fun listen(bind: Bind, port: Int, tls: TlsFiles?): Acceptor {
		failListen?.let { throw ServeFailure(it) }
		listened += Triple(bind, port, tls)
		val real = if (port == 0) hostPort else port
		served += connections.map { FakeConnection(it) }
		return FakeAcceptor(
			listOf("127.0.0.1:$real", "[::1]:$real"),
			real,
			served.toMutableList<Connection>(),
			onEmpty = { done = true },
		).also { acceptor = it }
	}

	override fun spawn(block: () -> Unit): Boolean {
		block()
		return true
	}

	override fun sleep(millis: Int) = Unit

	override fun trapInterrupt() {
		trapped++
	}

	override fun interrupted() = done

	override fun resolve(name: String) = names[name].orEmpty()

	override fun mode(path: String) = modes[path]

	override fun makePrivateDirectory(path: String): Boolean {
		directories += path
		return true
	}

	override fun start(argv: List<String>): Child? {
		started += argv
		return if (startFails) null else FakeChild().also { children += it }
	}
}
