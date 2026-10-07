package dev.gmitch215.drift.host

private object WasmHost : Host {
	override val platform = "wasm"
	override val capabilities = emptySet<Capability>()
	override val os = "unknown"
	override val arch = "wasm32"

	override fun env(): Map<String, String> = emptyMap()

	override fun readText(path: String): String? = null

	override fun run(argv: List<String>): CommandResult? = null
}

actual fun systemHost(): Host = WasmHost
