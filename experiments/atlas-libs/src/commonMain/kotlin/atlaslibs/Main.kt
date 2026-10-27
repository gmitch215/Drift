package atlaslibs

val probes: List<Probe> = datetimeProbes + jsonProbes + ioProbes + coroutinesProbes

suspend fun main() {
    println("@@BEGIN")
    for (probe in probes) {
        println("@@ ${probe.id}")
        val context = ProbeContext()
        try {
            probe.body(context)
        } catch (e: Throwable) {
            context.line("probe-failed", e::class.simpleName)
        }
        print(context.transcript())
    }
    println("@@END")
}
