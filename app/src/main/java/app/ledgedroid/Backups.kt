package app.ledgedroid

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.ledgedroid.core.BackupNames
import app.ledgedroid.core.BackupSchedule
import app.ledgedroid.core.BackupSettings
import app.ledgedroid.core.JournalStore
import app.ledgedroid.core.backupDue
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread

/** The journal file, through Android's document access. */
class UriStore(private val cr: ContentResolver, private val uri: Uri) : JournalStore {
    override fun read(): ByteArray =
        cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("the file couldn't be opened")

    override fun overwrite(bytes: ByteArray) {
        cr.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IOException("the file couldn't be opened for writing")
    }

    override fun append(bytes: ByteArray): Boolean =
        cr.openOutputStream(uri, "wa")?.use { it.write(bytes) } != null

    /**
     * Passes over the start of the file instead of keeping it, so checking an append reads little into
     * memory. Seeking is tried first (instant for real files); some providers give a pipe, which can't
     * seek and on older Android versions throws instead of skipping, so then the start is read and
     * thrown away. Either way this never fails just because seeking isn't possible.
     */
    override fun readFrom(offset: Int): ByteArray? =
        (cr.openInputStream(uri) ?: throw IOException("the file couldn't be opened")).use { s ->
            var left = offset.toLong()
            var canSeek = true
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val skipped = if (canSeek) {
                    try {
                        s.skip(left)
                    } catch (e: IOException) {
                        canSeek = false
                        0L
                    }
                } else 0L
                if (skipped > 0) {
                    left -= skipped
                    continue
                }
                val n = s.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) return null // the file is shorter than offset
                left -= n
            }
            s.readBytes()
        }
}

data class BackupFile(val name: String, val uri: Uri, val time: LocalDateTime?)

sealed interface BackupResult {
    data class Made(val name: String) : BackupResult
    data object Unchanged : BackupResult

    /** No folder has been chosen for backups, or access to it was lost. */
    data object NoFolder : BackupResult
    data class Failed(val message: String) : BackupResult
}

/**
 * Backups of the journal, saved as copies in the folder that contains it. Android only lets the app
 * create files in a folder the person has chosen, so they pick the journal's folder once in Settings.
 */
class Backups(context: Context) {
    private val app = context.applicationContext
    private val cr = app.contentResolver
    private val prefs = app.getSharedPreferences("ledgedroid", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ settings

    fun settings() = BackupSettings(
        schedule = BackupSchedule.entries.firstOrNull { it.name == prefs.getString(KEY_SCHEDULE, null) }
            ?: BackupSchedule.OFF,
        beforeChange = prefs.getBoolean(KEY_BEFORE_CHANGE, true),
        keep = prefs.getInt(KEY_KEEP, 10),
        skipUnchanged = prefs.getBoolean(KEY_SKIP_UNCHANGED, true),
    )

    fun saveSettings(s: BackupSettings) {
        prefs.edit()
            .putString(KEY_SCHEDULE, s.schedule.name)
            .putBoolean(KEY_BEFORE_CHANGE, s.beforeChange)
            .putInt(KEY_KEEP, s.keep)
            .putBoolean(KEY_SKIP_UNCHANGED, s.skipUnchanged)
            .apply()
        BackupScheduler.update(app, s.schedule)
    }

    /** The journal the app has saved access to, or null with none opened. */
    fun journal(): Uri? = prefs.getString(KEY_JOURNAL, null)?.let(Uri::parse)

    fun journalName(journal: Uri): String =
        displayName(journal) ?: journal.lastPathSegment?.substringAfterLast('/') ?: "journal"

    /** The backup folder chosen for [journal], if the app still has permission to write there. */
    fun folder(journal: Uri): Uri? = prefs.getString(KEY_FOLDER + journal, null)?.let(Uri::parse)?.takeIf { tree ->
        cr.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    /**
     * Use [tree] for [journal]'s backups. Returns a warning if the folder doesn't seem to contain the
     * journal, or throws if the app can't keep access to it.
     */
    fun setFolder(journal: Uri, tree: Uri): String? {
        cr.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        // Let go of the folder used before, unless another setting still uses it.
        val old = prefs.getString(KEY_FOLDER + journal, null)
        prefs.edit().putString(KEY_FOLDER + journal, tree.toString()).apply()
        if (old != null && old != tree.toString() && prefs.all.none { (k, v) -> k.startsWith(KEY_FOLDER) && v == old }) {
            runCatching {
                cr.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        val name = journalName(journal)
        return if (children(tree).none { it.first == name }) {
            "This folder doesn't contain $name, so backups won't be next to it. They'll still be saved here."
        } else null
    }

    fun folderName(tree: Uri): String? =
        runCatching {
            displayName(
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree)
                )
            )
        }.getOrNull()

    fun lastBackupAt(journal: Uri): Long? = prefs.getLong(KEY_LAST + journal, 0L).takeIf { it > 0 }

    /** The last scheduled backup's failure, if it failed. */
    fun lastError(journal: Uri): String? = prefs.getString(KEY_ERROR + journal, null)

    // ------------------------------------------------------------------ backing up

    /** [journal]'s backups, oldest first. */
    fun list(journal: Uri): List<BackupFile> {
        val tree = folder(journal) ?: return emptyList()
        val name = journalName(journal)
        return children(tree)
            .filter { BackupNames.isBackupOf(name, it.first) }
            .sortedBy { it.first }
            .map { (file, uri) -> BackupFile(file, uri, BackupNames.timeOf(name, file)) }
    }

    /**
     * Save a copy of [journal]: [bytes] if given (the journal as it is before a change), else the file
     * as it is now. Unless [force], nothing is saved if it's the same as the latest backup. Old
     * backups beyond the number to keep are deleted.
     */
    fun backUp(journal: Uri, bytes: ByteArray? = null, force: Boolean = false): BackupResult {
        val tree = folder(journal) ?: return BackupResult.NoFolder
        val settings = settings()
        return try {
            val data = bytes ?: UriStore(cr, journal).read()
            val name = journalName(journal)
            val existing = list(journal)
            val latest = existing.lastOrNull()
            if (!force && settings.skipUnchanged && latest != null && readOrNull(latest.uri)?.contentEquals(data) == true) {
                markDone(journal)
                return BackupResult.Unchanged
            }
            var file = BackupNames.name(name, LocalDateTime.now())
            // Two backups in the same second (eg "Back up now" then a change) get the next second's name.
            var at = LocalDateTime.now()
            while (existing.any { it.name == file }) {
                at = at.plusSeconds(1); file = BackupNames.name(name, at)
            }
            val created = createFile(tree, file) ?: throw IOException("the backup file couldn't be created")
            val ok = try {
                cr.openOutputStream(created, "w")?.use { it.write(data) } != null && readOrNull(created)?.contentEquals(
                    data
                ) == true
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                runCatching { DocumentsContract.deleteDocument(cr, created) }
                throw IOException("the backup didn't save correctly")
            }
            trim(journal, settings.keep)
            markDone(journal)
            BackupResult.Made(displayName(created) ?: file)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            prefs.edit().putString(KEY_ERROR + journal, message).apply()
            BackupResult.Failed(message)
        }
    }

    /** The scheduled backup, if one is due. */
    fun runScheduledIfDue(): BackupResult? {
        val journal = journal() ?: return null
        val s = settings()
        if (!backupDue(s.schedule, lastBackupAt(journal), System.currentTimeMillis())) return null
        return backUp(journal)
    }

    /**
     * Keep a copy of the journal from before a failed change that couldn't be undone. Saved in the
     * backup folder if there is one (with a name that trimming never deletes), else in the app's files.
     * Returns where, as words for a message.
     */
    fun saveRecovery(journal: Uri, bytes: ByteArray): String? {
        val name = journalName(journal)
        val file = BackupNames.name(name, LocalDateTime.now()).removeSuffix(".bak") + ".recovered"
        folder(journal)?.let { tree ->
            val ok = runCatching {
                val created = createFile(tree, file) ?: return@runCatching false
                cr.openOutputStream(created, "w")
                    ?.use { it.write(bytes) } != null && readOrNull(created)?.contentEquals(bytes) == true
            }.getOrDefault(false)
            if (ok) return "in ${folderName(tree) ?: "the backup folder"} as $file"
        }
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "recovered").apply { mkdirs() }
        val out = File(dir, file)
        out.writeBytes(bytes)
        return if (out.readBytes().contentEquals(bytes)) "at ${out.absolutePath}" else null
    }

    private fun markDone(journal: Uri) {
        prefs.edit().putLong(KEY_LAST + journal, System.currentTimeMillis()).remove(KEY_ERROR + journal).apply()
    }

    private fun trim(journal: Uri, keep: Int) {
        val all = list(journal)
        val delete = BackupNames.toDelete(journalName(journal), all.map { it.name }, keep).toSet()
        all.filter { it.name in delete }.forEach { runCatching { DocumentsContract.deleteDocument(cr, it.uri) } }
    }

    // ------------------------------------------------------------------ documents

    /** The files directly in [tree], as name and document URI. */
    private fun children(tree: Uri): List<Pair<String, Uri>> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Pair<String, Uri>>()
        cr.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out.add(name to DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        return out
    }

    private fun createFile(tree: Uri, name: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // A text MIME type would make some providers add ".txt" to the name.
        return DocumentsContract.createDocument(cr, parent, "application/octet-stream", name)
    }

    private fun readOrNull(uri: Uri): ByteArray? =
        runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        /** The same key the view model saves the journal under. */
        const val KEY_JOURNAL = "journalUri"
        private const val KEY_SCHEDULE = "backupSchedule"
        private const val KEY_BEFORE_CHANGE = "backupBeforeChange"
        private const val KEY_KEEP = "backupKeep"
        private const val KEY_SKIP_UNCHANGED = "backupSkipUnchanged"
        private const val KEY_FOLDER = "backupFolder:"
        private const val KEY_LAST = "backupLast:"
        private const val KEY_ERROR = "backupError:"

        fun formatTime(millis: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    }
}

/** Runs scheduled backups in the background, even when the app isn't open. */
object BackupScheduler {
    private const val JOB_ID = 4201

    /**
     * Check every few hours whether a backup is due (see [backupDue]); checking often keeps a daily
     * backup close to a day apart even when Android delays jobs. The job survives restarts.
     */
    fun update(context: Context, schedule: BackupSchedule) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (schedule == BackupSchedule.OFF) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
            .setPeriodic(6 * 3_600_000L)
            .setPersisted(true)
            .setRequiresBatteryNotLow(true)
            .build()
        scheduler.schedule(job)
    }
}

/** After an app update, make sure the scheduled backup job is still there. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BackupScheduler.update(context, Backups(context).settings().schedule)
        // Reminders too.
        Alerts.schedule(context)
    }
}

class BackupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "ledgedroid-backup") {
            runCatching { Backups(this).runScheduledIfDue() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
