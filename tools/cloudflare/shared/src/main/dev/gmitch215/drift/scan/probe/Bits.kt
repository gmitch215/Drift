package dev.gmitch215.drift.scan.probe

internal fun bits(d: Double): String = d.toRawBits().toULong().toString(16)

internal fun bits(f: Float): String = f.toRawBits().toUInt().toString(16)
