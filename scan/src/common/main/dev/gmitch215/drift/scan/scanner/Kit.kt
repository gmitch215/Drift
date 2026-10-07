package dev.gmitch215.drift.scan.scanner

object Kit {
	private const val MARK = "@@ "

	private fun quote(arg: String) = "'" + arg.replace("'", "'\\''") + "'"

	fun sh(scanners: List<Scanner> = Scanners.all): String = buildString {
		append("#!/bin/sh\n")
		append("run() {\n")
		append("\ttool=\$1; shift\n")
		append("\tout=\$(\"\$@\" 2>&1); code=\$?\n")
		append("\tprintf '$MARK%s exit=%s\\n%s\\n${MARK}end\\n' \"\$tool\" \"\$code\" \"\$out\"\n")
		append("}\n")
		for (s in scanners) append("run ${s.tool} ${s.command.joinToString(" ") { quote(it) }}\n")
	}

	fun ps1(scanners: List<Scanner> = Scanners.all): String = buildString {
		append("function Run-Tool(\$tool, \$exe, \$cmdArgs) {\n")
		append("\t\$out = \"\"; \$code = 127\n")
		append("\tif (Get-Command \$exe -ErrorAction SilentlyContinue) {\n")
		append("\t\t\$out = (& \$exe @cmdArgs 2>&1 | Out-String); \$code = \$LASTEXITCODE\n")
		append("\t}\n")
		append("\tWrite-Output \"$MARK\$tool exit=\$code\"\n")
		append("\tWrite-Output \$out.TrimEnd()\n")
		append("\tWrite-Output \"${MARK}end\"\n")
		append("}\n")
		for (s in scanners) {
			val args = s.command.drop(1).joinToString(",") { "'$it'" }
			append("Run-Tool '${s.tool}' '${s.command.first()}' @($args)\n")
		}
	}

	fun parse(text: String): Map<String, KitEntry> {
		val result = mutableMapOf<String, KitEntry>()
		var tool: String? = null
		var exit = 0
		val body = mutableListOf<String>()
		for (raw in text.replace("\r", "").lines()) {
			if (raw.startsWith(MARK + "end")) {
				tool?.let { result[it] = KitEntry(exit, body.joinToString("\n")) }
				tool = null
				body.clear()
			} else if (raw.startsWith(MARK) && tool == null) {
				val parts = raw.removePrefix(MARK).split(" ")
				tool = parts[0].removePrefix("tool=")
				exit = parts.getOrNull(1)?.removePrefix("exit=")?.toIntOrNull() ?: 0
			} else if (tool != null) {
				body += raw
			}
		}
		return result
	}
}
