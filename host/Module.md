# Module host

Operating system access. `Host` is the interface the rest of Drift reads facts through, with one implementation per target (JVM, macOS, Linux, Windows, Wasm, and Android and iOS when the mobile targets are enabled) and a fake for tests.
