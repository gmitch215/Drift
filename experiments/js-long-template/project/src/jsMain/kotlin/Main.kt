inline fun wrap(block: () -> Any?): String = "ok:" + block()

fun plain(block: () -> Any?): String = "ok:" + block()

fun main() {
    val v = 9007199254740993L
    println("A " + "$v")
    println("D " + wrap { "$v" })
    println("F " + v.also { println("G " + "$it") })
    println("P " + plain { "$v" })
}
