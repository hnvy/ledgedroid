package app.ledgedroid.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How often to back up the journal on a schedule. */
enum class BackupSchedule(val label: String, val hours: Long?) {
    OFF("Off", null),
    DAILY("Daily", 24),
    WEEKLY("Weekly", 24 * 7),
    MONTHLY("Monthly", 24 * 30),
}

data class BackupSettings(
    val schedule: BackupSchedule = BackupSchedule.OFF,
    /** Back up before each change made in the app (add, edit, delete). */
    val beforeChange: Boolean = true,
    /** How many backups to keep; older ones are deleted. 0 keeps all. */
    val keep: Int = 10,
    /** Don't make a new backup if the journal is the same as in the latest one. */
    val skipUnchanged: Boolean = true,
) {
    val enabled: Boolean get() = schedule != BackupSchedule.OFF || beforeChange

    companion object {
        val KEEP_CHOICES = listOf(3, 5, 10, 20, 50, 0)
    }
}

/**
 * Names of backup files, next to the journal: "ledger.journal" is backed up as
 * "ledger.journal.2026-10-27_193012.bak". The time stamp makes names sort oldest first, and the .bak
 * ending keeps hledger and editors from mistaking them for journals. Only files named exactly like
 * this are ever deleted when trimming old backups.
 */
object BackupNames {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    private val STAMP_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}_\\d{6}")

    fun name(journal: String, at: LocalDateTime): String = "${safe(journal)}.${at.format(STAMP)}.bak"

    fun isBackupOf(journal: String, file: String): Boolean {
        val prefix = safe(journal) + "."
        if (!file.startsWith(prefix) || !file.endsWith(".bak") || file.length < prefix.length + ".bak".length) return false
        return STAMP_REGEX.matches(file.substring(prefix.length, file.length - ".bak".length))
    }

    /** The time a backup was made, from its name. */
    fun timeOf(journal: String, file: String): LocalDateTime? {
        if (!isBackupOf(journal, file)) return null
        val stamp = file.substring(safe(journal).length + 1, file.length - ".bak".length)
        return runCatching { LocalDateTime.parse(stamp, STAMP) }.getOrNull()
    }

    /** The backups of [journal] among [files] to delete so only the newest [keep] remain (0 keeps all). */
    fun toDelete(journal: String, files: Collection<String>, keep: Int): List<String> {
        if (keep <= 0) return emptyList()
        val backups = files.filter { isBackupOf(journal, it) }.sorted()
        return backups.dropLast(keep)
    }

    /** A file name can't contain a slash. */
    private fun safe(journal: String) = journal.replace('/', '_').ifBlank { "journal" }
}

/**
 * Whether a scheduled backup is due, [lastAt] being when the journal was last backed up or found
 * unchanged (epoch millis). An hour's slack lets a daily check that runs a little early still count.
 */
fun backupDue(schedule: BackupSchedule, lastAt: Long?, now: Long): Boolean {
    val hours = schedule.hours ?: return false
    if (lastAt == null) return true
    return now - lastAt >= (hours - 1) * 3_600_000L
}
