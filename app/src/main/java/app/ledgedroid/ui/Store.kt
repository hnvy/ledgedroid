package app.ledgedroid.ui

import android.content.SharedPreferences
import app.ledgedroid.core.AccountListMode
import app.ledgedroid.core.AccountType
import app.ledgedroid.core.DraftPosting
import app.ledgedroid.core.PeriodPreset
import app.ledgedroid.core.ReportInterval
import app.ledgedroid.core.ReportKind
import app.ledgedroid.core.Status
import app.ledgedroid.core.Template
import app.ledgedroid.core.TransactionDraft
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A report the person saved to come back to with one tap: every option, including the search. */
data class SavedView(val id: String, val name: String, val options: Options)

/** Templates and saved views, kept as JSON in shared preferences. */
internal object Store {
    private const val KEY_TEMPLATES = "templates"
    private const val KEY_VIEWS = "savedViews"
    private const val KEY_VIEWS_SEEDED = "savedViewsSeeded"
    private const val KEY_BEFORE_VIEW = "optionsBeforeView"
    private const val KEY_INCOME_SEEDED = "incomeViewSeeded"
    private const val KEY_BUDGET_EXAMPLE_REMOVED = "budgetExampleRemoved"
    private const val KEY_ACCOUNT_TYPES = "accountTypes"

    /** Account types set in Settings, by account name. */
    fun readAccountTypes(prefs: SharedPreferences): Map<String, AccountType> {
        val text = prefs.getString(KEY_ACCOUNT_TYPES, null) ?: return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = LinkedHashMap<String, AccountType>()
            for (name in o.keys()) {
                val type = enumValues<AccountType>().firstOrNull { it.name == o.optString(name) } ?: continue
                out[name] = type
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun writeAccountTypes(prefs: SharedPreferences, types: Map<String, AccountType>) {
        val o = JSONObject()
        types.forEach { (name, type) -> o.put(name, type.name) }
        prefs.edit().putString(KEY_ACCOUNT_TYPES, o.toString()).apply()
    }

    fun readTemplates(prefs: SharedPreferences): List<Template> = readList(prefs, KEY_TEMPLATES) { o ->
        Template(o.getString("id"), o.getString("name"), draftFromJson(o.getJSONObject("draft")))
    }

    fun writeTemplates(prefs: SharedPreferences, templates: List<Template>) =
        writeList(prefs, KEY_TEMPLATES, templates) { t ->
            JSONObject().put("id", t.id).put("name", t.name).put("draft", draftToJson(t.draft))
        }

    /**
     * Saved views. The first time, this includes the income statement, which used to have a button of
     * its own above the report; it's added once, so deleting it keeps it deleted. The monthly budget
     * report that earlier versions added as an example is taken out again, unless it was changed.
     */
    fun readViews(prefs: SharedPreferences): List<SavedView> {
        var views = readViewList(prefs)
        var changed = false
        if (!prefs.getBoolean(KEY_INCOME_SEEDED, false)) {
            if (views.none { it.options == INCOME_VIEW.options }) views = listOf(INCOME_VIEW) + views
            changed = true
        }
        if (!prefs.getBoolean(KEY_BUDGET_EXAMPLE_REMOVED, false)) {
            views = views.filterNot { it == OLD_BUDGET_EXAMPLE }
            changed = true
        }
        if (changed) {
            writeViews(prefs, views)
            prefs.edit()
                .putBoolean(KEY_VIEWS_SEEDED, true)
                .putBoolean(KEY_INCOME_SEEDED, true)
                .putBoolean(KEY_BUDGET_EXAMPLE_REMOVED, true)
                .apply()
        }
        return views
    }

    private fun readViewList(prefs: SharedPreferences) = readList(prefs, KEY_VIEWS) { o ->
        SavedView(o.getString("id"), o.getString("name"), optionsFromJson(o.getJSONObject("options")))
    }

    fun writeViews(prefs: SharedPreferences, views: List<SavedView>) = writeList(prefs, KEY_VIEWS, views) { v ->
        JSONObject().put("id", v.id).put("name", v.name).put("options", optionsToJson(v.options))
    }

    /** The report shown before a saved one was chosen, to go back to. */
    fun readOptionsBeforeView(prefs: SharedPreferences): Options? =
        prefs.getString(KEY_BEFORE_VIEW, null)?.let { text ->
            runCatching { optionsFromJson(JSONObject(text)) }.getOrNull()
        }

    fun writeOptionsBeforeView(prefs: SharedPreferences, options: Options?) {
        val edit = prefs.edit()
        if (options == null) edit.remove(KEY_BEFORE_VIEW) else edit.putString(KEY_BEFORE_VIEW, optionsToJson(options).toString())
        edit.apply()
    }

    /** hledger is: revenues and expenses, which had the Income button above the report before. */
    private val INCOME_VIEW = SavedView(
        id = "income-statement",
        name = "Income statement",
        options = Options(query = "--incomestatement"),
    )

    /** hledger bal --auto --budget register budget not:<unbudgeted> -M -H (list mode, hledger's default) */
    /**
     * The example earlier versions added (hledger bal --auto --budget register budget not:<unbudgeted>
     * -M -H, in list mode), to take out again if it's exactly as added.
     */
    private val OLD_BUDGET_EXAMPLE = SavedView(
        id = "example-budget",
        name = "Monthly budget",
        options = Options(
            mode = AccountListMode.FLAT,
            query = "--auto --budget register budget not:<unbudgeted> -M -H",
        ),
    )

    // ------------------------------------------------------------------ JSON

    private fun <T> readList(prefs: SharedPreferences, key: String, item: (JSONObject) -> T): List<T> {
        val text = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i -> runCatching { item(array.getJSONObject(i)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun <T> writeList(prefs: SharedPreferences, key: String, items: List<T>, item: (T) -> JSONObject) {
        val array = JSONArray()
        items.forEach { array.put(item(it)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun draftToJson(d: TransactionDraft): JSONObject = JSONObject()
        .put("status", d.status.name)
        .put("payee", d.payee)
        .put("note", d.note)
        .put("comment", d.comment)
        .put("postings", JSONArray().also { a ->
            d.postings.forEach { p ->
                a.put(JSONObject().put("account", p.account).put("amount", p.amount).put("commodity", p.commodity))
            }
        })

    private fun draftFromJson(o: JSONObject): TransactionDraft {
        val ps = o.optJSONArray("postings") ?: JSONArray()
        return TransactionDraft(
            date = LocalDate.now(),
            status = enumOr(o.optString("status"), Status.UNMARKED),
            payee = o.optString("payee"),
            note = o.optString("note"),
            comment = o.optString("comment"),
            postings = (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                DraftPosting(p.optString("account"), p.optString("amount"), p.optString("commodity"))
            }.ifEmpty { listOf(DraftPosting(), DraftPosting()) },
        )
    }

    private fun optionsToJson(o: Options): JSONObject = JSONObject()
        .put("kind", o.kind.name)
        .put("preset", o.preset.name)
        .put("customStart", o.customStart?.toString())
        .put("customEnd", o.customEnd?.toString())
        .put("mode", o.mode.name)
        .put("depth", o.depth ?: 0)
        .put("showEmpty", o.showEmpty)
        .put("sortByAmount", o.sortByAmount)
        .put("invert", o.invert)
        .put("endBalance", o.endBalance)
        .put("atCost", o.atCost)
        .put("auto", o.auto)
        .put("interval", o.interval.name)
        .put("periodTable", o.periodTable)
        .put("multiPeriod", o.multiPeriod)
        .put("query", o.query)

    private fun optionsFromJson(o: JSONObject): Options = migrateKind(storedOptionsFromJson(o), o.optString("budgetPattern"))

    private fun storedOptionsFromJson(o: JSONObject): Options = Options(
        kind = enumOr(o.optString("kind"), ReportKind.BALANCE),
        preset = enumOr(o.optString("preset"), PeriodPreset.ALL),
        customStart = o.optDate("customStart"),
        customEnd = o.optDate("customEnd"),
        mode = enumOr(o.optString("mode"), AccountListMode.TREE),
        depth = o.optInt("depth", 0).takeIf { it > 0 },
        showEmpty = o.optBoolean("showEmpty"),
        sortByAmount = o.optBoolean("sortByAmount"),
        invert = o.optBoolean("invert"),
        endBalance = o.optBoolean("endBalance"),
        atCost = o.optBoolean("atCost"),
        auto = o.optBoolean("auto"),
        interval = enumOr(o.optString("interval"), ReportInterval.MONTHLY),
        periodTable = if (o.has("periodTable")) o.optBoolean("periodTable") else o.optBoolean("budgetTable"),
        multiPeriod = o.optBoolean("multiPeriod"),
        query = o.optString("query"),
    )

    private fun JSONObject.optDate(key: String): LocalDate? =
        if (isNull(key)) null else runCatching { LocalDate.parse(getString(key)) }.getOrNull()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
