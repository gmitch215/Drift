package dev.gmitch215.drift.cli

import com.github.ajalt.clikt.core.main
import dev.gmitch215.drift.cli.command.DriftCommand

fun main(args: Array<String>) = DriftCommand().main(args)
