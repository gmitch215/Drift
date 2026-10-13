package dev.gmitch215.drift.ci

import dev.gmitch215.drift.model.Run

/** What an importer made of a saved log or summary; importers never throw. */
sealed interface Import {
    data class Done(val run: Run) : Import

    data class Refused(val reason: String) : Import
}
