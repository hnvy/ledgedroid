package app.ledgedroid.core

/**
 * How to undo the last change: the file's contents before it, and right after it. Undoing writes
 * [before] back, but only if the file still holds exactly [after], so a change made since (in another
 * app, or synced from elsewhere) is never thrown away.
 */
class UndoRecord(val description: String, val before: ByteArray, val after: ByteArray) {
    companion object {
        const val CHANGED_SINCE =
            "The journal has changed since then, so it can't be undone here. Use a backup or a text editor instead."

        /**
         * Undo [record] on [store] by writing the old contents back, with the same checks and restore
         * as any change. (Shortening the file to undo an append isn't possible through every file app.)
         */
        fun undo(store: JournalStore, record: UndoRecord, saveRecovery: (ByteArray) -> String?) {
            val now = try {
                store.read()
            } catch (e: Exception) {
                throw EntryException("The journal couldn't be read, so nothing was undone.")
            }
            if (!now.contentEquals(record.after)) throw EntryException(CHANGED_SINCE)
            SafeWrite.replace(store, now, record.before, saveRecovery)
        }
    }
}

/** The journal file, as far as writing it safely is concerned. */
interface JournalStore {
    fun read(): ByteArray

    /** Replace the whole file with [bytes]. */
    fun overwrite(bytes: ByteArray)

    /** Add [bytes] to the end of the file. Returns false if the file can't be appended to. */
    fun append(bytes: ByteArray): Boolean

    /** The bytes from [offset] to the end, or null if the file is shorter than that. */
    fun readFrom(offset: Int): ByteArray? =
        read().let { if (it.size < offset) null else it.copyOfRange(offset, it.size) }
}

/**
 * Writing the journal so that a failure never loses it. After writing, the file is read back and must
 * hold exactly the new bytes. If writing fails part way (the app is stopped, storage is full, the file's
 * app has an error), the original bytes are written back. If even that fails, [saveRecovery] keeps a
 * copy of the original somewhere else and returns where, for the message.
 */
object SafeWrite {
    const val READ_ONLY =
        "Ledgedroid only has permission to read this file. Open it again from the menu (Open journal file) to allow changing it."

    /** Replace the file, which held [before], with [after]. Throws [EntryException] with what happened. */
    fun replace(store: JournalStore, before: ByteArray, after: ByteArray, saveRecovery: (ByteArray) -> String?) {
        val cause = try {
            store.overwrite(after)
            if (readOrNull(store)?.contentEquals(after) == true) return
            "the file didn't read back as written"
        } catch (e: SecurityException) {
            if (readOrNull(store)?.contentEquals(before) == true) throw EntryException(READ_ONLY)
            "no permission to write it"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        restore(store, before, cause, saveRecovery)
    }

    /**
     * Add [added] to the end of the file, which held [before], writing all of it if appending fails.
     * Only the end of the file is read back to check: if it holds exactly [added] after [before]'s length,
     * the append worked (a provider that truncates on append, or a write cut short, would fail this).
     */
    fun append(store: JournalStore, before: ByteArray, added: ByteArray, saveRecovery: (ByteArray) -> String?) {
        val appended = try {
            // If reading just the end fails for any reason, read the whole file rather than assume the
            // append failed, which would mean writing the whole file again.
            store.append(added) && (runCatching { store.readFrom(before.size) }.getOrNull()
                ?: readOrNull(store)?.let {
                    if (it.size >= before.size) it.copyOfRange(
                        before.size,
                        it.size
                    ) else null
                })
                ?.contentEquals(added) == true
        } catch (e: SecurityException) {
            if (readOrNull(store)?.contentEquals(before) == true) throw EntryException(READ_ONLY)
            false
        } catch (e: Exception) {
            false
        }
        // A failed or unsupported append may have written part of the text, or nothing; writing the whole
        // file fixes both, and puts the original back if that fails too.
        if (!appended) replace(store, before, before + added, saveRecovery)
    }

    private fun restore(
        store: JournalStore,
        before: ByteArray,
        cause: String,
        saveRecovery: (ByteArray) -> String?
    ): Nothing {
        if (readOrNull(store)?.contentEquals(before) == true) {
            throw EntryException("Couldn't save the change ($cause). The journal file wasn't changed.")
        }
        val restored = try {
            store.overwrite(before)
            readOrNull(store)?.contentEquals(before) == true
        } catch (e: Exception) {
            false
        }
        if (restored) {
            throw EntryException("Couldn't save the change ($cause). The journal was put back exactly as it was, so nothing was changed.")
        }
        val where = try {
            saveRecovery(before)
        } catch (e: Exception) {
            null
        }
        throw EntryException(
            "Couldn't save the change ($cause), and the journal file couldn't be put back, so it may be damaged. " +
                    if (where != null) "A copy of the journal from before the change was saved $where." else "A copy of it couldn't be saved either."
        )
    }

    private fun readOrNull(store: JournalStore): ByteArray? = try {
        store.read()
    } catch (e: Exception) {
        null
    }
}
