package app.ledgedroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.ledgedroid.core.JournalParser
import app.ledgedroid.core.Reminders
import app.ledgedroid.ui.Store
import org.json.JSONObject
import java.time.LocalDate
import kotlin.concurrent.thread

/** What the person wants to be told about (Settings › Notifications). */
data class AlertSettings(
    val reminders: Boolean = false,
) {
    val any: Boolean get() = reminders
}

/**
 * Reminders for recurring transactions (templates with a schedule), as notifications. Checked about four
 * times a day in the background and whenever the app opens; each due date is told about once.
 */
object Alerts {
    private const val PREFS = "ledgedroid"
    private const val CHANNEL_REMINDERS = "reminders"
    private const val JOB_ID = 4202
    private const val KEY_SETTINGS = "alertSettings"
    private const val KEY_REMINDED = "remindedDue"

    fun settings(context: Context): AlertSettings {
        val o = prefs(context).getString(KEY_SETTINGS, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return AlertSettings()
        return AlertSettings(o.optBoolean("reminders"))
    }

    fun setSettings(context: Context, s: AlertSettings) {
        prefs(context).edit().putString(KEY_SETTINGS, JSONObject().put("reminders", s.reminders).toString()).apply()
        schedule(context, s)
    }

    /** Whether notifications can be shown (Android 13 and later ask the person first). */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun schedule(context: Context, s: AlertSettings = settings(context)) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!s.any) {
            scheduler.cancel(JOB_ID); return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AlertsJobService::class.java))
                .setPeriodic(6 * 3_600_000L)
                .setPersisted(true)
                .build()
        )
    }

    /** Read the journal and templates and tell about anything due. Returns how many notifications were posted. */
    fun check(context: Context, today: LocalDate = LocalDate.now()): Int {
        val s = settings(context)
        if (!s.any || !allowed(context)) return 0
        val p = prefs(context)
        val uri = p.getString("journalUri", null)?.let(Uri::parse)
        val journal = uri?.let { u ->
            runCatching {
                val text = context.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@runCatching null
                JournalParser(today).parse(text, IncludeFolder.resolver(context, u))
            }.getOrNull()
        }
        channels(context)
        var posted = 0
        if (s.reminders) {
            val reminded = readDates(p.getString(KEY_REMINDED, null))
            val due = Reminders.due(Store.readTemplates(p), journal, today, reminded)
            for (d in due) {
                val name = d.template.draft.payee.ifEmpty { d.template.name }
                notify(context, CHANNEL_REMINDERS, ("r:" + d.template.id).hashCode(),
                    "${d.template.name} is due", "$name, ${d.template.schedule?.describe()?.lowercase()}. Tap to add it.",
                    AddEntry.intent(context, d.template.id))
                reminded[d.template.id] = d.date
                posted++
            }
            p.edit().putString(KEY_REMINDED, writeDates(reminded)).apply()
        }
        return posted
    }

    private fun notify(context: Context, channel: String, id: Int, title: String, text: String, open: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Budget alerts were taken out: their channel goes too, so it doesn't linger in Android's settings.
        runCatching { nm.deleteNotificationChannel("budget") }
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Recurring transactions that are due" })
    }

    private fun readDates(s: String?): MutableMap<String, LocalDate> {
        val o = s?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HashMap()
        return o.keys().asSequence().mapNotNull { k -> runCatching { k to LocalDate.parse(o.getString(k)) }.getOrNull() }.toMap(HashMap())
    }

    private fun writeDates(m: Map<String, LocalDate>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v.toString()) } }.toString()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class AlertsJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "ledgedroid-alerts") {
            runCatching { Alerts.check(this) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
