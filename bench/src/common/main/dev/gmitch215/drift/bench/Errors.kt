package dev.gmitch215.drift.bench

enum class Problem {
    MISSING_FIELD,
    BAD_VALUE,
    BAD_TYPE,
    BAD_DIMENSION,
    UNKNOWN_PLACEHOLDER,
    DUPLICATE_ID,
    TOO_FEW_VARIANTS,
    INCONSISTENT,
    NO_SALT,
}

class TemplateException(val problem: Problem, val where: String, detail: String) :
    Exception("$where: $detail ($problem)")

class SealedException(id: String) :
    Exception("$id is in the sealed test split and is never run, fit or scored here")

object Dimensions {
    val all = listOf(
        "os",
        "kernel",
        "cpu",
        "cpu-limit",
        "memory",
        "runtime",
        "compiler",
        "build",
        "userland",
        "env",
        "locale",
        "limits",
        "network",
        "browser",
    )

    val runtimes = mapOf(
        "sh" to ("prog.sh" to "sh"),
        "python3" to ("prog.py" to "python3"),
        "node" to ("prog.js" to "node"),
        "java" to ("Prog.java" to "java"),
        "go" to ("prog.go" to "go run"),
        "ruby" to ("prog.rb" to "ruby"),
        "php" to ("prog.php" to "php"),
        "dotnet" to (
            "prog.fsx" to
                "sh -c 'dotnet /usr/share/dotnet/sdk/*/FSharp/fsi.dll \"\$@\"' sh"
            ),
        "rust" to ("prog.rs" to "sh -c 'rustc -o /tmp/prog \"\$@\" && /tmp/prog' sh"),
        "r" to ("prog.R" to "Rscript"),
        "dart" to ("prog.dart" to "dart"),
        "julia" to ("prog.jl" to "julia"),
        "elixir" to ("prog.exs" to "elixir"),
    )

    val directions = listOf("lower", "higher", "changed", "added", "removed")
}
