package dev.gmitch215.drift.tools

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = "usage: freeze <raw-dir> <fixtures-dir> | capsules <fixtures-dir>"

fun main(args: Array<String>) {
	when (args.firstOrNull()) {
		"freeze" -> if (args.size == 3) {
			Freeze.run(Path.of(args[1]), Path.of(args[2])).forEach {
				println("${it.name}: ${it.rawBytes} -> ${it.outBytes} bytes")
			}
		} else {
			usage()
		}

		"capsules" -> if (args.size == 2) {
			Capsules.generate(Path.of(args[1])).forEach { println("capsule $it") }
		} else {
			usage()
		}

		else -> usage()
	}
}

private fun usage(): Nothing {
	System.err.println(USAGE)
	exitProcess(2)
}
