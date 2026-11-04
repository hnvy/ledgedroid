package app.ledgedroid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import app.ledgedroid.ui.Store

/** Opening the add-transaction form from outside the app: the widget, the quick settings tile and the launcher shortcut. */
object AddEntry {
    const val ACTION_ADD = "app.ledgedroid.action.ADD_TRANSACTION"
    const val EXTRA_TEMPLATE = "app.ledgedroid.extra.TEMPLATE"

    fun intent(context: Context, templateId: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_ADD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (templateId != null) putExtra(EXTRA_TEMPLATE, templateId) }

    fun pendingIntent(context: Context, requestCode: Int, templateId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent(context, templateId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** A home screen widget with an add button and the first few templates. */
class AddWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        private val TEMPLATE_VIEWS = intArrayOf(R.id.widget_template_1, R.id.widget_template_2, R.id.widget_template_3)

        /** Redraw every widget, eg after the templates change. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AddWidget::class.java))
            if (ids.isNotEmpty()) {
                val views = views(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        private fun views(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences("ledgedroid", Context.MODE_PRIVATE)
            val templates = Store.readTemplates(prefs)
            return RemoteViews(context.packageName, R.layout.widget_add).apply {
                setOnClickPendingIntent(R.id.widget_add, AddEntry.pendingIntent(context, 0))
                TEMPLATE_VIEWS.forEachIndexed { i, id ->
                    val t = templates.getOrNull(i)
                    if (t == null) {
                        setViewVisibility(id, View.GONE)
                    } else {
                        setViewVisibility(id, View.VISIBLE)
                        setTextViewText(id, t.name)
                        setOnClickPendingIntent(id, AddEntry.pendingIntent(context, i + 1, t.id))
                    }
                }
            }
        }
    }
}

/** A quick settings tile that opens the add-transaction form. */
class AddTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(AddEntry.pendingIntent(this, 100))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(AddEntry.intent(this))
        }
    }
}
