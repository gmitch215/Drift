package dev.gmitch215.drift.host

enum class Capability { PROCESS, FILES, ENV }

interface Host {
    val platform: String
    val capabilities: Set<Capability>
}
