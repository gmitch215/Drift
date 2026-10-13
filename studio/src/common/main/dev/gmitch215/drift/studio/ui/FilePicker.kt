package dev.gmitch215.drift.studio.ui

import dev.gmitch215.drift.contract.Contract
import dev.gmitch215.drift.model.Capsule

/** The platform file chooser for files that end in an extension; [onPicked] gets the result. */
fun interface FilePicker {
    fun pick(extension: String, onPicked: (PickedFile) -> Unit)
}

/** A chosen file: its bytes, or [problem] when they could not be read. */
class PickedFile(val name: String, val bytes: ByteArray?, val problem: String? = null)

/** A file read into a value Studio shows, or the sentence that says why it could not be. */
sealed interface Load<out T> {
    class Ok<T>(val name: String, val value: T) : Load<T>

    class Bad(val message: String) : Load<Nothing>
}

object Loads {
    const val EXTENSION = ".json"

    fun capsule(file: PickedFile): Load<Capsule> =
        read(file, "a capsule file made by drift capture") { Capsule.parse(it) }

    fun contract(file: PickedFile): Load<Contract> =
        read(file, "a contract file made by drift contract create") { Contract.parse(it) }

    private fun <T> read(file: PickedFile, what: String, parse: (String) -> T): Load<T> {
        val bytes = file.bytes
            ?: return Load.Bad(
                "cannot read ${file.name}: ${file.problem ?: "no data"}. $CHOOSE$what.",
            )
        return try {
            Load.Ok(file.name, parse(bytes.decodeToString(throwOnInvalidSequence = true)))
        } catch (e: Exception) {
            val why = e.message ?: "not a valid file"
            Load.Bad("cannot read ${file.name}: $why. $CHOOSE$what.")
        }
    }

    private const val CHOOSE = "Choose "
}
