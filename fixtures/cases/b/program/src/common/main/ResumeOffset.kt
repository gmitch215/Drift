package dev.gmitch215.drift.cases

fun resume(text: String, offset: Int, separator: String): Int = text.indexOf(separator, offset)

fun main() {
    val text = "line one\nline two"
    println("control=${resume(text, 0, "two")}")
    val cursor = resume(text, 40, "")
    println("cursor=$cursor length=${text.length}")
    if (cursor !in 0..text.length) println("FAIL cursor outside the text")
}
