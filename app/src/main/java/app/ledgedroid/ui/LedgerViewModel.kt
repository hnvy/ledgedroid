package app.ledgedroid.ui

import kotlinx.coroutines.flow.first
import app.ledgedroid.core.UndoHistory
import app.ledgedroid.core.TransactionPages
import app.ledgedroid.core.TransactionOrder
import app.ledgedroid.core.Intelligent
import app.ledgedroid.Alerts
import app.ledgedroid.AlertSettings
import app.ledgedroid.IncludeFolder
import app.ledgedroid.core.EditPlan
import app.ledgedroid.core.IncludeResolver
import app.ledgedroid.core.JournalEdits
import app.ledgedroid.core.Schedule
import app.ledgedroid.core.SharePattern
import app.ledgedroid.core.SharedEntry
import app.ledgedroid.core.ShareParse
import app.ledgedroid.core.Valuation
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ledgedroid.AddWidget
import app.ledgedroid.BackupFile
import app.ledgedroid.BackupResult
import app.ledgedroid.Backups
import app.ledgedroid.UriStore
import app.ledgedroid.core.BackupSettings
import app.ledgedroid.core.SafeWrite
import app.ledgedroid.core.Status
import app.ledgedroid.core.TransactionSearch
import app.ledgedroid.core.UndoRecord
import app.ledgedroid.core.AccountListMode
import app.ledgedroid.core.AccountType
import app.ledgedroid.core.AmountLayout
import app.ledgedroid.core.AmountSign
import app.ledgedroid.core.RegisterFilter
import app.ledgedroid.core.SignChoice
import app.ledgedroid.core.SignChoices
import app.ledgedroid.core.Accumulation
import app.ledgedroid.core.BalanceReport
import app.ledgedroid.core.BudgetOptions
import app.ledgedroid.core.BudgetReport
import app.ledgedroid.core.Budgets
import app.ledgedroid.core.CompoundReport
import app.ledgedroid.core.BranchColors
import app.ledgedroid.core.BudgetRow
import app.ledgedroid.core.CompoundSection
import app.ledgedroid.core.MixedAmount
import app.ledgedroid.core.MultiPeriodRow
import app.ledgedroid.core.UNBUDGETED
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.Journal
import app.ledgedroid.core.JournalException
import app.ledgedroid.core.JournalParser
import app.ledgedroid.core.PeriodPreset
import app.ledgedroid.core.Forecast
import app.ledgedroid.core.Pins
import app.ledgedroid.core.Query
import app.ledgedroid.core.ReportCommand
import app.ledgedroid.core.dropNames
import app.ledgedroid.core.withoutHiddenRows
import app.ledgedroid.core.RegisterReport
import app.ledgedroid.core.ReportInterval
import app.ledgedroid.core.ReportKind
import app.ledgedroid.core.ReportOptions
import app.ledgedroid.core.Reports
import app.ledgedroid.core.DraftPosting
import app.ledgedroid.core.Entry
import app.ledgedroid.core.EntryException
import app.ledgedroid.core.EntrySuggestions
import app.ledgedroid.core.MultiPeriodCompound
import app.ledgedroid.core.MultiPeriodReport
import app.ledgedroid.core.MultiPeriodReports
import app.ledgedroid.core.Styles
import app.ledgedroid.core.Template
import app.ledgedroid.core.Transaction
import app.ledgedroid.core.TransactionDraft
import app.ledgedroid.core.TransactionLocation
import app.ledgedroid.core.Timings
import android.util.Log

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface Source {
    /** No journal opened yet. */
    data object None : Source

    /** persisted is false when the file came from "Open with" and we only have temporary access. */
    data class File(val uri: Uri, val persisted: Boolean) : Source
}

sealed interface LoadState {
    data object Loading : LoadState

    /** No journal has been opened: the report screen asks for one. */
    data object NoJournal : LoadState
    data class Loaded(val journal: Journal, val name: String, val source: Source) : LoadState
    data class Failed(val name: String, val message: String, val line: Int, val excerpt: String) : LoadState
}

/**
 * Everything the person can change about the report. Saved between launches. [query] is the filter text,
 * which can also hold hledger options like --budget, -M or --auto (see [ReportCommand]); [resolve] gives
 * the options in effect.
 */
data class Options(
    val kind: ReportKind = ReportKind.BALANCE,
    val preset: PeriodPreset = PeriodPreset.ALL,
    val customStart: LocalDate? = null,
    /** Exclusive. */
    val customEnd: LocalDate? = null,
    val mode: AccountListMode = AccountListMode.TREE,
    val depth: Int? = null,
    val showEmpty: Boolean = false,
    val sortByAmount: Boolean = false,
    val invert: Boolean = false,
    val endBalance: Boolean = false,
    val atCost: Boolean = false,
    /** Apply auto posting rules (hledger --auto). */
    val auto: Boolean = false,
    /** Budget report and By period: column interval (-W, -M, -Q, -Y). */
    val interval: ReportInterval = ReportInterval.MONTHLY,
    /** Budget report and By period: show all periods as a table instead of one period at a time. */
    val periodTable: Boolean = false,
    /** Balance reports: a column per period (interval), like hledger balance -M. */
    val multiPeriod: Boolean = false,
    val query: String = "",
) {
    fun span(today: LocalDate): DateSpan =
        if (preset == PeriodPreset.CUSTOM) DateSpan(customStart, customEnd) else preset.span(today)

    /** Balance sheets always show end balances and income statements changes, like hledger's bs and is. */
    val accumulation: Accumulation
        get() = when (kind) {
            ReportKind.BALANCE, ReportKind.BUDGET -> if (endBalance) Accumulation.END_BALANCE else Accumulation.CHANGE
            ReportKind.BALANCE_SHEET -> Accumulation.END_BALANCE
            ReportKind.INCOME_STATEMENT -> Accumulation.CHANGE
        }

    fun reportOptions(today: LocalDate) =
        ReportOptions(mode, depth, showEmpty, sortByAmount, invert, accumulation, span(today), atCost)

    fun budgetOptions(today: LocalDate, rulePattern: String) = BudgetOptions(
        interval = interval, span = span(today), historical = endBalance, depth = depth,
        showUnbudgeted = showEmpty, invert = invert, atCost = atCost, descriptionPattern = rulePattern,
        tree = mode == AccountListMode.TREE,
    )

    /**
     * The options in effect: these, with any hledger options typed into the filter applied on top. The
     * returned options keep the filter text as [query]; the query terms to use are in the command.
     */
    fun resolve(today: LocalDate = LocalDate.now()): Resolved {
        val c = ReportCommand.parse(query, today)
        if (!c.hasOptions) return Resolved(this, c)
        var o = this
        c.kind?.let { o = o.copy(kind = it) }
        // A column interval in the filter also turns on columns, like hledger -M.
        c.interval?.let { o = o.copy(interval = it, multiPeriod = true) }
        c.span?.let { o = o.copy(preset = PeriodPreset.CUSTOM, customStart = it.start, customEnd = it.end) }
        c.depth?.let { o = o.copy(depth = it) }
        c.mode?.let { o = o.copy(mode = it) }
        if (c.historical) o = o.copy(endBalance = true)
        if (c.auto) o = o.copy(auto = true)
        if (c.empty) o = o.copy(showEmpty = true)
        if (c.sortByAmount) o = o.copy(sortByAmount = true)
        if (c.cost) o = o.copy(atCost = true)
        if (c.invert) o = o.copy(invert = true)
        return Resolved(o, c)
    }
}

/** Options in effect, and the hledger options and query terms read from the filter. */
data class Resolved(val options: Options, val command: ReportCommand)

/**
 * Budget and Net worth used to be buttons above the report, saved as the report kind. They're now typed into
 * the filter (--budget, --balancesheet), so older saved settings and saved reports are moved there.
 */
internal fun migrateKind(o: Options, oldBudgetPattern: String = ""): Options {
    fun withFlag(flag: String) = o.copy(kind = ReportKind.BALANCE, query = listOf(flag, o.query.trim()).filter { it.isNotEmpty() }.joinToString(" "))
    return when (o.kind) {
        ReportKind.BUDGET -> withFlag(
            if (oldBudgetPattern.isBlank()) "--budget"
            else "--budget=\"" + oldBudgetPattern.trim().replace("\"", "") + "\""
        )

        ReportKind.BALANCE_SHEET -> withFlag("--balancesheet")
        // The Accounts / Income buttons are gone; the income statement is a saved report instead.
        ReportKind.INCOME_STATEMENT -> withFlag("--incomestatement")
        else -> o
    }
}

/** A report, with the options it was made with (as in effect, see [Options.resolve]). */
data class ReportUi(
    val journal: Journal,
    val span: DateSpan,
    val accumulation: Accumulation,
    val single: BalanceReport?,
    val compound: CompoundReport?,
    val queryError: String?,
    val budget: BudgetReport? = null,
    val multi: MultiPeriodReport? = null,
    val multiCompound: MultiPeriodCompound? = null,
    val options: Options = Options(),
    val command: ReportCommand = ReportCommand(),
    /** The pinned accounts in this report, in the order they were pinned. */
    val pinned: List<PinnedLine> = emptyList(),
    /** How many pinned accounts are left out of [pinned] for being at 0 (with that setting on). */
    val pinnedAtZero: Int = 0,
)

/**
 * A pinned account at the top of a report, with its total including subaccounts: [amount] in a
 * single-period report, [row] with a column per period, or its [budget] row. [section] is the balance sheet
 * or income statement section it's in, for its transactions.
 */
data class PinnedLine(
    val account: String,
    val section: CompoundSection? = null,
    val amount: MixedAmount = MixedAmount.ZERO,
    val row: MultiPeriodRow? = null,
    val budget: BudgetRow? = null,
) {
    /**
     * Nothing to show: a balance of 0, 0 in every period (and in total), or a budget row with nothing
     * spent or budgeted in any period.
     */
    val isZero: Boolean
        get() = when {
            budget != null -> budget.cells.all { it.actual.isZero && (it.goal?.isZero ?: true) }
            row != null -> row.cells.all { it.isZero } && (row.total?.isZero ?: true)
            else -> amount.isZero
        }
}

/**
 * The hidden accounts list: [listed] accounts, of which [paused] ones are switched off for now (shown, but
 * kept on the list to hide again), and [on] whether hiding is on at all. [active] are the ones hidden now.
 */
data class HiddenAccounts(
    val listed: Set<String> = emptySet(),
    val paused: Set<String> = emptySet(),
    val on: Boolean = true,
) {
    val active: Set<String> get() = if (on) listed - paused else emptySet()
}

/**
 * Which account's postings to list. section is set when it was opened from a balance sheet or income
 * statement; span when it was opened from one period of the budget report. amountSign is the register's
 * In / Out (Charges / Payments...) choice and filter its advanced filters, kept with the screen so
 * they're still set after opening a transaction and coming back.
 */
data class RegisterTarget(
    val account: String,
    val includeSubaccounts: Boolean,
    val section: CompoundSection?,
    val span: DateSpan? = null,
    val amountSign: AmountSign = AmountSign.ALL,
    val filter: RegisterFilter = RegisterFilter.NONE,
)

data class RegisterUi(
    val target: RegisterTarget,
    val journal: Journal,
    val span: DateSpan,
    val accumulation: Accumulation,
    val report: RegisterReport,
    /** The two sides of the sign filter, named for the account's type. */
    val signChoices: List<SignChoice> = emptyList(),
) {
    /** The side being shown, or null for all postings. */
    val signChoice: SignChoice? get() = signChoices.firstOrNull { it.sign == target.amountSign }
}

/** The screens above the report, in the order they were opened. */
sealed interface Screen {
    data class Register(val target: RegisterTarget) : Screen
    data class TransactionDetail(val index: Int) : Screen
    data object Payees : Screen
    data class Payee(val name: String) : Screen
    data object Templates : Screen
    data object SavedViews : Screen
    data object Settings : Screen
    data object Search : Screen

    /**
     * The add-transaction form. editTemplateId is set when editing a template instead of adding a
     * transaction, and editing when changing a transaction already in the journal. The draft is kept
     * in the view model so it survives leaving the app.
     */
    data class Add(val editTemplateId: String? = null, val editing: TransactionLocation? = null) : Screen

    /** Adding a transaction one prompt at a time (Settings › Quick add), starting with templateId if set. */
    data class QuickAdd(val templateId: String? = null, val shared: SharedEntry? = null) : Screen

    /** Journal health, like hledger check. */
    data object Health : Screen

    /** Every transaction in the journal, filtered, a page at a time. */
    data object AllTransactions : Screen

    /** The patterns for reading shared text. */
    data object SharePatterns : Screen
}

/** A message shown at the bottom of the screen, with an Undo button if the change can be undone. */
data class Message(val text: String, val canUndo: Boolean = false, val id: Long = System.nanoTime(), val canRedo: Boolean = false)

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Failed(val message: String) : SaveState
}

/** What the settings screen shows about backups. journalName is null with no journal opened. */
data class BackupUi(
    val settings: BackupSettings = BackupSettings(),
    val journalName: String? = null,
    val folderName: String? = null,
    val lastBackupAt: Long? = null,
    val lastError: String? = null,
    val backups: List<BackupFile> = emptyList(),
    val noJournal: Boolean = false,
    val busy: Boolean = true,
)

class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ledgedroid", Context.MODE_PRIVATE)
    private val backups = Backups(app)

    private val _load = MutableStateFlow<LoadState>(LoadState.Loading)

    /**
     * Account types set in Settings, by account name (eg expns as an expense account), laid over every
     * journal read. Kept between launches. Declared before anything reads a journal.
     */
    private val _accountTypes = MutableStateFlow(Store.readAccountTypes(prefs))

    /**
     * Lightweight mode: the journal has changed (a change made in the app) since it was last read, so the
     * screens don't show it yet; ↻ (or Refresh) reads it. Declared before anything reads a journal, as
     * reading clears it.
     */
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale.asStateFlow()
    val accountTypes: StateFlow<Map<String, AccountType>> = _accountTypes.asStateFlow()

    /** Set [account]'s type, or with null go back to the journal's own (or guessed) type. */
    fun setAccountType(account: String, type: AccountType?) {
        val name = account.trim()
        if (name.isEmpty()) return
        val current: Map<String, AccountType> = _accountTypes.value
        val types: Map<String, AccountType> = if (type == null) current - name else current + (name to type)
        _accountTypes.value = types
        Store.writeAccountTypes(prefs, types)
        // The journal needn't be read again: every report follows the journal's types.
        _load.update { l -> if (l is LoadState.Loaded) l.copy(journal = l.journal.copy(assignedTypes = types)) else l }
    }
    val load: StateFlow<LoadState> = _load.asStateFlow()

    private val _options = MutableStateFlow(readOptions())
    val options: StateFlow<Options> = _options.asStateFlow()

    /** Screens opened over the report; empty when the report is showing. */
    private val _screens = MutableStateFlow<List<Screen>>(emptyList())
    val screens: StateFlow<List<Screen>> = _screens.asStateFlow()

    private val _draft = MutableStateFlow(TransactionDraft(LocalDate.now()))
    val draft: StateFlow<TransactionDraft> = _draft.asStateFlow()

    /** The name of the template being edited in the form. */
    private val _templateName = MutableStateFlow("")
    val templateName: StateFlow<String> = _templateName.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _templates = MutableStateFlow(Store.readTemplates(prefs))
    val templates: StateFlow<List<Template>> = _templates.asStateFlow()

    private val _savedViews = MutableStateFlow(Store.readViews(prefs))
    val savedViews: StateFlow<List<SavedView>> = _savedViews.asStateFlow()

    /** Goes up when the query is changed from outside the search box (a saved view), so the box shows it. */
    private val _queryRevision = MutableStateFlow(0)
    val queryRevision: StateFlow<Int> = _queryRevision.asStateFlow()

    /** A short message to show once, eg after adding a transaction, with Undo when it can be undone. */
    private val _messages = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _messages.asStateFlow()

    /** Show a message without Undo. (Kept as a property so the many places that just show text stay simple.) */
    private val _message = object {
        var value: String?
            get() = _messages.value?.text
            set(v) {
                _messages.value = v?.let { Message(it) }
            }
    }

    /**
     * The changes made in the app that can be undone, and those undone that can be redone, for the journal
     * they were made to ([historySource]). Cleared when another journal is opened. Each step checks the file
     * is exactly as the change left it, so a change made elsewhere since is never overwritten.
     */
    private val history = UndoHistory(
        if (prefs.getBoolean(KEY_LIGHTWEIGHT, false) && LightPart.SHORT_UNDO.name !in prefs.getStringSet(KEY_LIGHT_OFF, null).orEmpty())
            LIGHT_UNDO_STEPS else UNDO_STEPS
    )
    private var historySource: Source? = null
    private val undoMutex = kotlinx.coroutines.sync.Mutex()

    /** What Undo and Redo would do now, for the menu: the changes' descriptions, and how many of each. */
    data class UndoUi(val undo: String? = null, val redo: String? = null, val undoCount: Int = 0, val redoCount: Int = 0)

    private val _undoUi = MutableStateFlow(UndoUi())
    val undoUi: StateFlow<UndoUi> = _undoUi.asStateFlow()

    private fun historyChanged() {
        _undoUi.value = UndoUi(history.nextUndo?.description, history.nextRedo?.description, history.undoCount, history.redoCount)
    }

    /** Tree rows the person has collapsed, keyed by report section and account name. */
    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    private var source: Source = readSource()

    /** The last journal text read, so toggling auto postings re-parses without reading the file again. */
    @Volatile
    private var lastRead: Pair<String, String>? = null
    private var loadJob: Job? = null
    private var lastLoadedAt = 0L

    /**
     * Accounts the person has hidden; they and their subaccounts are left out of every report. Hiding can
     * be switched off for some of them, or for all, for now, without taking them off the list.
     */
    private val _hidden = MutableStateFlow(
        HiddenAccounts(
            listed = prefs.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet(),
            paused = prefs.getStringSet(KEY_HIDING_PAUSED, null)?.toSet() ?: emptySet(),
            on = prefs.getBoolean(KEY_HIDING_ON, true),
        )
    )
    val hidden: StateFlow<HiddenAccounts> = _hidden.asStateFlow()

    /** Leave out pinned accounts whose balance is 0. Remembered between launches. */
    private val _hideZeroPins = MutableStateFlow(prefs.getBoolean(KEY_HIDE_ZERO_PINS, false))
    val hideZeroPins: StateFlow<Boolean> = _hideZeroPins.asStateFlow()

    fun setHideZeroPins(on: Boolean) {
        _hideZeroPins.value = on
        prefs.edit().putBoolean(KEY_HIDE_ZERO_PINS, on).apply()
    }

    /**
     * Keep hidden accounts in totals (set in Hidden accounts): hiding then only takes the accounts' rows out
     * of reports, and every amount and total stays as if nothing were hidden. Off, they're left out entirely.
     */
    private val _keepHiddenInTotals = MutableStateFlow(prefs.getBoolean(KEY_KEEP_HIDDEN_IN_TOTALS, false))
    val keepHiddenInTotals: StateFlow<Boolean> = _keepHiddenInTotals.asStateFlow()

    fun setKeepHiddenInTotals(on: Boolean) {
        _keepHiddenInTotals.value = on
        prefs.edit().putBoolean(KEY_KEEP_HIDDEN_IN_TOTALS, on).apply()
    }

    /** What hiding does to reports: accounts whose postings are left out, and accounts whose rows are taken out. */
    private data class Hiding(val excluded: Set<String>, val rowsOnly: Set<String>)

    private val hiding = combine(_hidden, _keepHiddenInTotals) { h: HiddenAccounts, keep: Boolean ->
        val active = h.active
        if (keep) Hiding(emptySet(), active) else Hiding(active, emptySet())
    }

    /** Accounts pinned to the top of reports, in the order they were pinned. */
    private val _pinned = MutableStateFlow(
        prefs.getString(KEY_PINNED, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    )
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    /** How the account tree looks (Options › Appearance). */
    private val _treeStyle = MutableStateFlow(
        TreeStyle(
            colors = prefs.getBoolean(KEY_TREE_COLORS, false),
            bars = prefs.getBoolean(KEY_TREE_BARS, false),
            contrast = prefs.getFloat(KEY_TREE_CONTRAST, 1f),
            shading = prefs.getFloat(KEY_TREE_SHADING, BranchColors.DEFAULT_SHADING),
        )
    )
    val treeStyle: StateFlow<TreeStyle> = _treeStyle.asStateFlow()

    /** Each top-level account's branch colour, as a hue (see [BranchColors]). New accounts get one on loading. */
    private val _branchHues = MutableStateFlow(readHues())
    val branchHues: StateFlow<Map<String, Float>> = _branchHues.asStateFlow()

    /** Branch colours the person chose themselves; the contrast setting doesn't move them. */
    private var customHues: Set<String> =
        prefs.getString(KEY_CUSTOM_HUES, null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** Where on the colour wheel the random colours' range starts; picked at random once, and on New random colours. */
    private var hueStart: Float = prefs.getFloat(KEY_HUE_START, -1f).takeIf { it >= 0f }
        ?: (kotlin.random.Random.nextFloat() * 360f).also { prefs.edit().putFloat(KEY_HUE_START, it).apply() }

    private fun hueRange(contrast: Float = _treeStyle.value.contrast) = BranchColors.rangeFor(contrast, hueStart)

    val report: StateFlow<ReportUi?> = combine(_load, _options, hiding, _pinned, _hideZeroPins) { load, opts, hiding, pinned, hideZero ->
        (load as? LoadState.Loaded)?.let { buildReport(it.journal, opts, hiding, pinned, hideZero) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The register for the most recently opened register screen. */
    val register: StateFlow<RegisterUi?> = combine(_load, _options, _screens, hiding) { load, opts, screens, hiding ->
        val loaded = load as? LoadState.Loaded
        val target = screens.lastOrNull { it is Screen.Register }?.let { (it as Screen.Register).target }
        if (loaded == null || target == null) null else buildRegister(loaded.journal, opts, target, hiding.excluded)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _suggestions = MutableStateFlow<EntrySuggestions?>(null)
    val suggestions: StateFlow<EntrySuggestions?> = _suggestions.asStateFlow()

    init {
        // Rebuilt in the background whenever the journal is read again.
        viewModelScope.launch {
            _load.collect { load ->
                val journal = (load as? LoadState.Loaded)?.journal ?: return@collect
                _suggestions.value = withContext(Dispatchers.Default) { EntrySuggestions(journal) }
            }
        }
    }

    init {
        // Give new top-level accounts a colour, well apart from the others, as journals are read.
        viewModelScope.launch {
            _load.collect { load ->
                val journal = (load as? LoadState.Loaded)?.journal ?: return@collect
                val now = _branchHues.value
                val assigned = BranchColors.assign(now, BranchColors.topLevels(journal), range = hueRange())
                if (assigned != now) setHues(assigned)
            }
        }
        load(source)
    }

    // ------------------------------------------------------------------ actions

    fun open(uri: Uri) {
        history.clear()
        historySource = null
        historyChanged()
        val cr = getApplication<Application>().contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Ask to keep write access too, for adding transactions; some files can only be read.
        val persisted = runCatching { cr.takePersistableUriPermission(uri, read or write) }.isSuccess ||
                // e.g. opened via "Open with": readable now, but not after the app restarts
                runCatching { cr.takePersistableUriPermission(uri, read) }.isSuccess
        source = Source.File(uri, persisted)
        val editor = prefs.edit()
        if (persisted) editor.putString(KEY_URI, uri.toString()) else editor.remove(KEY_URI)
        editor.apply()
        _screens.update { s -> s.filterIsInstance<Screen.Add>() }
        load(source)
    }

    fun reload() = load(source)

    /**
     * Re-read the file when returning to the app, so edits made elsewhere (or synced) show up. Not in
     * Lightweight mode, where ↻ does it.
     */
    fun onResume() {
        if (!light(LightPart.MANUAL_REFRESH) && source is Source.File && _load.value !is LoadState.Loading &&
            SystemClock.elapsedRealtime() - lastLoadedAt > 2_000
        ) load(source, quiet = true)
        backUpIfDue()
    }

    fun updateOptions(change: (Options) -> Options) {
        val before = _options.value
        _options.update(change)
        saveOptions(_options.value)
        if (autoOf(before) != autoOf(_options.value)) reparse()
    }

    /** Whether auto postings are applied: the option, or --auto in the filter. They're added while reading. */
    private fun autoOf(o: Options): Boolean = o.auto || ReportCommand.parse(o.query).auto

    private fun reparse() {
        val (name, text) = lastRead ?: return
        loadJob?.cancel()
        val src = source
        val auto = autoOf(_options.value)
        loadJob = viewModelScope.launch {
            _load.value = withContext(Dispatchers.Default) { parse(name, text, src, auto) }
        }
    }

    fun setQuery(q: String) {
        val before = _options.value
        if (before.query == q) return
        _options.update { it.copy(query = q) }
        // Kept between launches with the other options, so a typed --budget report comes back.
        saveOptions(_options.value)
        if (autoOf(before) != autoOf(_options.value)) reparse()
    }

    // ------------------------------------------------------------------ pinned accounts and appearance

    /** Pin [account] to the top of reports, or unpin it if it's pinned. */
    fun togglePin(account: String) {
        val name = account.removePrefix("$UNBUDGETED:")
        val now = _pinned.value
        val pinning = name !in now
        setPinned(if (pinning) now + name else now - name)
        _message.value = if (pinning) "Pinned $name to the top" else "Unpinned $name"
    }

    private fun setPinned(accounts: List<String>) {
        _pinned.value = accounts
        prefs.edit().putString(KEY_PINNED, accounts.joinToString("\n")).apply()
    }

    fun setBranchHue(topLevel: String, hue: Float) {
        setCustomHues(customHues + topLevel)
        setHues(_branchHues.value + (topLevel to hue))
    }

    /** New random colours for [names], well apart from each other, replacing any chosen ones. */
    fun shuffleBranchHues(names: List<String>) {
        hueStart = kotlin.random.Random.nextFloat() * 360f
        prefs.edit().putFloat(KEY_HUE_START, hueStart).apply()
        setCustomHues(customHues - names.toSet())
        setHues(BranchColors.shuffle(_branchHues.value, names, range = hueRange()))
    }

    private fun setCustomHues(names: Set<String>) {
        customHues = names
        prefs.edit().putString(KEY_CUSTOM_HUES, names.joinToString("\n")).apply()
    }

    private fun setHues(hues: Map<String, Float>) {
        _branchHues.value = hues
        val o = org.json.JSONObject()
        hues.forEach { (k, v) -> o.put(k, v.toDouble()) }
        prefs.edit().putString(KEY_HUES, o.toString()).apply()
    }

    private fun readHues(): Map<String, Float> = try {
        val o = org.json.JSONObject(prefs.getString(KEY_HUES, null) ?: "{}")
        o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Whether the "N accounts hidden" bar shows above the report (set in Hidden accounts). */
    private val _showHiddenBar = MutableStateFlow(prefs.getBoolean(KEY_HIDDEN_BAR, true))
    val showHiddenBar: StateFlow<Boolean> = _showHiddenBar.asStateFlow()

    fun setShowHiddenBar(on: Boolean) {
        _showHiddenBar.value = on
        prefs.edit().putBoolean(KEY_HIDDEN_BAR, on).apply()
    }

    fun setTreeStyle(style: TreeStyle) {
        val before = _treeStyle.value
        if (style.contrast != before.contrast) {
            // Spread or gather the random colours to the new range, keeping their order and spacing;
            // colours the person chose stay as they are.
            val from = hueRange(before.contrast)
            val to = hueRange(style.contrast)
            setHues(_branchHues.value.mapValues { (name, h) -> if (name in customHues) h else BranchColors.remap(h, from, to) })
        }
        _treeStyle.value = style
        prefs.edit().putBoolean(KEY_TREE_COLORS, style.colors).putBoolean(KEY_TREE_BARS, style.bars)
            .putFloat(KEY_TREE_CONTRAST, style.contrast).putFloat(KEY_TREE_SHADING, style.shading).apply()
    }

    // ------------------------------------------------------------------ hidden accounts

    /** Hide [account]; if it's on the list switched off, switch it back on (and hiding with it). */
    fun hideAccount(account: String) {
        val h = _hidden.value
        setHidden(h.copy(listed = h.listed + account, paused = h.paused - account, on = true))
        _message.value = "Hidden $account. Show it again with ⋮ › Hidden accounts…"
    }

    /** Take [accounts] off the hidden list, keeping the others hidden. */
    fun unhide(accounts: Collection<String>) {
        val h = _hidden.value
        val shown = accounts.filter { it in h.listed }
        if (shown.isEmpty()) return
        setHidden(h.copy(listed = h.listed - shown.toSet(), paused = h.paused - shown.toSet()))
        _message.value = if (shown.size == 1) "Showing ${shown.single()} again" else "Showing ${shown.size} accounts again"
    }

    fun unhideAll() {
        val n = _hidden.value.listed.size
        setHidden(HiddenAccounts())
        _message.value = if (n == 1) "Showing the hidden account again" else "Showing $n hidden accounts again"
    }

    /** Switch hiding on or off for all the listed accounts, for now: they stay on the list. */
    fun setHidingOn(on: Boolean) = setHidden(_hidden.value.copy(on = on))

    /** Switch hiding on or off for one listed account, keeping it on the list. */
    fun setAccountHidingOn(account: String, on: Boolean) {
        val h = _hidden.value
        if (account !in h.listed) return
        setHidden(h.copy(paused = if (on) h.paused - account else h.paused + account, on = h.on || on))
    }

    private fun setHidden(h: HiddenAccounts) {
        // An account switched off and then taken off the list isn't kept as switched off.
        val clean = h.copy(paused = h.paused intersect h.listed)
        _hidden.value = clean
        prefs.edit()
            .putStringSet(KEY_HIDDEN, HashSet(clean.listed))
            .putStringSet(KEY_HIDING_PAUSED, HashSet(clean.paused))
            .putBoolean(KEY_HIDING_ON, clean.on)
            .apply()
    }

    fun toggleCollapsed(key: String) = _collapsed.update { if (key in it) it - key else it + key }

    /** Collapse every account in [keys] (all those with subaccounts in the report), or expand them all again. */
    fun collapseAll(keys: Set<String>) = _collapsed.update { it + keys }
    fun expandAll(keys: Set<String>) = _collapsed.update { it - keys }

    fun openRegister(target: RegisterTarget) = push(Screen.Register(target))

    /** Show only positive or negative amounts (or all) in the register on top. */
    fun setRegisterSign(sign: AmountSign) {
        _screens.update { list: List<Screen> -> withRegisterTarget(list) { it.copy(amountSign = sign) } }
    }

    /** Change the advanced filters of the register on top. */
    fun setRegisterFilter(filter: RegisterFilter) {
        _screens.update { list: List<Screen> -> withRegisterTarget(list) { it.copy(filter = filter) } }
    }

    private fun withRegisterTarget(list: List<Screen>, change: (RegisterTarget) -> RegisterTarget): List<Screen> {
        val i = list.indexOfLast { it is Screen.Register }
        if (i < 0) return list
        val register = list[i] as Screen.Register
        val changed = ArrayList<Screen>(list)
        changed[i] = Screen.Register(change(register.target))
        return changed
    }

    fun push(screen: Screen) = _screens.update { it + screen }

    fun back() {
        val closingForm = _screens.value.lastOrNull().let { it is Screen.Add || it is Screen.QuickAdd }
        _screens.update { it.dropLast(1) }
        if (closingForm) flushPending()
    }

    fun consumeMessage() {
        _messages.value = null
    }

    /** Show [text] after a change that [record] can undo. */
    private fun changed(src: Source, record: UndoRecord?, text: String) {
        if (record != null) {
            if (historySource != src) history.clear()
            historySource = src
            history.recorded(record)
            historyChanged()
        }
        _messages.value = Message(text, canUndo = record != null)
    }

    /** Put the journal back as it was before the last change made in the app. */
    fun undo() = step(redo = false)

    /** Make the last undone change again. */
    fun redo() = step(redo = true)

    /** Undo the newest change, or redo the newest undone one, after checking the file is as it left it. */
    private fun step(redo: Boolean) {
        val src = historySource ?: return
        val record = (if (redo) history.nextRedo else history.nextUndo) ?: return
        _messages.value = null
        viewModelScope.launch {
            if (!undoMutex.tryLock()) return@launch // one at a time
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when (src) {
                            Source.None -> throw EntryException(NO_JOURNAL)
                            is Source.File -> {
                                val store = UriStore(getApplication<Application>().contentResolver, src.uri)
                                if (redo) UndoRecord.redo(store, record) { backups.saveRecovery(src.uri, it) }
                                else UndoRecord.undo(store, record) { backups.saveRecovery(src.uri, it) }
                                backupRun = null
                                (if (redo) record.after else record.before).toString(Charsets.UTF_8)
                            }
                        }
                    }
                }
                result.onSuccess { text ->
                    if (redo) history.redone(record) else history.undone(record)
                    historyChanged()
                    // After an undo, the message offers Redo; after a redo, Undo.
                    _messages.value = if (redo) Message("Redone: ${record.description}", canUndo = true)
                    else Message("Undone: ${record.description}", canRedo = true)
                    applyWritten(src, text)
                }.onFailure { e -> _message.value = failureMessage(e, if (redo) "redo" else "undo") }
            } finally {
                undoMutex.unlock()
            }
        }
    }

    /** The lines of the journal text last read, to show a transaction as written. */
    fun sourceLines(): List<String>? = lastRead?.second?.removePrefix("\uFEFF")?.lines()

    // ------------------------------------------------------------------ saved views

    /**
     * Show a saved report. The report shown before it is remembered, so tapping the saved report's chip
     * again ([toggleView]) goes back to it. Going from one saved report to another keeps the report from
     * before the first one.
     */
    fun applyView(view: SavedView) {
        val current = _options.value
        if (current == view.options) return
        if (optionsBeforeView == null || _savedViews.value.none { it.options == current }) setOptionsBeforeView(current)
        updateOptions { view.options }
        _queryRevision.update { it + 1 }
    }

    /** Show [view], or if it's already showing, go back to the report from before it ([restoreReport]). */
    fun toggleView(view: SavedView) {
        if (_options.value == view.options) restoreReport() else applyView(view)
    }

    /**
     * Go back to the report shown before a saved report was chosen: its period, options and filter. If
     * that isn't known (the saved report was already showing when this was added), the default report.
     */
    fun restoreReport() {
        val before = optionsBeforeView ?: Options()
        setOptionsBeforeView(null)
        updateOptions { before }
        _queryRevision.update { it + 1 }
    }

    /** The report shown before a saved one was chosen. Kept between launches. */
    private var optionsBeforeView: Options? = Store.readOptionsBeforeView(prefs)

    private fun setOptionsBeforeView(o: Options?) {
        optionsBeforeView = o
        Store.writeOptionsBeforeView(prefs, o)
    }

    fun saveView(name: String) =
        setViews(_savedViews.value + SavedView(UUID.randomUUID().toString(), name.trim(), _options.value))

    /** Replace a saved view's options with the current ones. */
    fun updateView(id: String) =
        setViews(_savedViews.value.map { if (it.id == id) it.copy(options = _options.value) else it })

    fun renameView(id: String, name: String) =
        setViews(_savedViews.value.map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun deleteView(id: String) = setViews(_savedViews.value.filterNot { it.id == id })

    private fun setViews(views: List<SavedView>) {
        _savedViews.value = views
        Store.writeViews(prefs, views)
    }

    // ------------------------------------------------------------------ adding transactions

    /** Open the add form, empty or filled from a template, dated today. */
    fun startAdd(templateId: String? = null) {
        if (source is Source.None) {
            _message.value = NO_JOURNAL
            return
        }
        val template = templateId?.let { id -> _templates.value.firstOrNull { it.id == id } }
        _draft.value = (template?.draft ?: blankDraft()).copy(date = LocalDate.now())
        openForm(Screen.Add())
    }

    /** Open the add form with a copy of [t], dated today. */
    fun startAddFrom(t: Transaction) {
        val styles = (_load.value as? LoadState.Loaded)?.journal?.styles ?: return
        _draft.value = Entry.fromTransaction(t, styles, LocalDate.now())
        openForm(Screen.Add())
    }

    /** Where [t] is in the journal text it was read from, or null if that isn't known. */
    fun locate(t: Transaction): TransactionLocation? =
        if (t.file != null || t.forecast) null else lastRead?.second?.let { Entry.locate(it, t) }

    /** Open the form to change [t] in the journal. */
    fun startEdit(t: Transaction) {
        val styles = (_load.value as? LoadState.Loaded)?.journal?.styles ?: return
        val at = locate(t) ?: return
        _draft.value = Entry.fromTransaction(t, styles, t.date, keepComments = true)
        openForm(Screen.Add(editing = at))
    }

    /** Remove [t] from the journal, then close its screen. */
    fun deleteTransaction(t: Transaction) {
        val at = locate(t) ?: return
        val src = source
        val what = "deleted ${t.description.ifEmpty { "the transaction" }} on ${t.date}"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { rewrite(src, what) { Entry.delete(it, at) } } }
            result.onSuccess { w ->
                _screens.update { s -> s.filterNot { it is Screen.TransactionDetail && it.index == t.index } }
                changed(src, w.undo, "Deleted ${t.description.ifEmpty { "the transaction" }} on ${t.date}")
                applyWritten(src, w.text)
            }.onFailure { e -> _message.value = failureMessage(e, "delete the transaction") }
        }
    }

    /** Mark [t] cleared, pending or unmarked, changing only the mark on its first line. */
    fun setStatus(t: Transaction, status: Status) {
        if (t.status == status) return
        val at = locate(t) ?: run {
            _message.value = "This transaction's place in the file isn't known, so it can't be changed."
            return
        }
        val src = source
        val label = when (status) {
            Status.CLEARED -> "cleared"
            Status.PENDING -> "pending"
            Status.UNMARKED -> "unmarked"
        }
        val name = t.description.ifEmpty { "the transaction" }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { rewrite(src, "marked $name $label") { Entry.setStatus(it, at, status) } }
            }
            result.onSuccess { w ->
                changed(src, w.undo, "Marked $name $label")
                applyWritten(src, w.text)
            }.onFailure { e -> _message.value = failureMessage(e, "change the status") }
        }
    }

    /** Open the form to edit a template, or to create one if [id] is null. */
    fun editTemplate(id: String?) {
        val template = id?.let { i -> _templates.value.firstOrNull { it.id == i } }
        _draft.value = template?.draft ?: blankDraft()
        _templateName.value = template?.name ?: ""
        openForm(Screen.Add(editTemplateId = template?.id ?: UUID.randomUUID().toString()))
    }

    private fun openForm(screen: Screen) {
        _saveState.value = SaveState.Idle
        _addedInSession.value = 0
        backupRun = null
        _screens.update { s -> s.filterNot { it is Screen.Add || it is Screen.QuickAdd } + screen }
    }

    /**
     * Start quick add: a transaction one prompt at a time, dated today unless changed, and with the
     * template [templateId] already chosen if set.
     */
    fun startQuickAdd(templateId: String? = null) {
        if (source is Source.None) {
            _message.value = NO_JOURNAL
            return
        }
        _draft.value = blankDraft()
        openForm(Screen.QuickAdd(templateId?.takeIf { id -> _templates.value.any { it.id == id } }))
    }

    /**
     * Add a transaction the default way: quick add when that's the default (Settings), else the form;
     * either way starting from the template [templateId] if set. The + button, the widget, the shortcut
     * and the quick settings tile all come here.
     */
    fun startDefaultAdd(templateId: String? = null) =
        if (_quickAddDefault.value) startQuickAdd(templateId) else startAdd(templateId)

    /** Make quick add the default way of adding (Settings). Remembered between launches. */
    private val _quickAddDefault = MutableStateFlow(prefs.getBoolean(KEY_QUICK_ADD_DEFAULT, false))
    val quickAddDefault: StateFlow<Boolean> = _quickAddDefault.asStateFlow()

    fun setQuickAddDefault(on: Boolean) {
        _quickAddDefault.value = on
        prefs.edit().putBoolean(KEY_QUICK_ADD_DEFAULT, on).apply()
    }

    /** Add [draft] from quick add, then close it, or with [another] start the next one on the same date. */
    fun saveQuick(draft: TransactionDraft, another: Boolean) {
        _draft.value = draft
        saveTransaction(another)
    }

    /** Leave quick add for the full form, with what's been entered so far. */
    fun quickToForm(draft: TransactionDraft) {
        val commodity = suggestions.value?.defaultCommodity ?: ""
        // The form always has at least two accounts to fill in.
        val postings = draft.postings + List((2 - draft.postings.size).coerceAtLeast(0)) { DraftPosting(commodity = commodity) }
        _draft.value = draft.copy(postings = postings)
        val added = _addedInSession.value
        openForm(Screen.Add())
        _addedInSession.value = added
    }

    /** Show the quick add button above + on the home screen (Settings). Remembered between launches. */
    private val _quickAdd = MutableStateFlow(prefs.getBoolean(KEY_QUICK_ADD, false))
    val quickAdd: StateFlow<Boolean> = _quickAdd.asStateFlow()

    fun setQuickAdd(on: Boolean) {
        _quickAdd.value = on
        prefs.edit().putBoolean(KEY_QUICK_ADD, on).apply()
    }

    private fun blankDraft(): TransactionDraft {
        val commodity = suggestions.value?.defaultCommodity ?: ""
        return TransactionDraft(LocalDate.now(), postings = List(2) { DraftPosting(commodity = commodity) })
    }

    fun updateDraft(change: (TransactionDraft) -> TransactionDraft) {
        _draft.update(change)
        if (_saveState.value is SaveState.Failed) _saveState.value = SaveState.Idle
    }

    fun setTemplateName(name: String) {
        _templateName.value = name
    }

    /** Fill the form from a template, keeping the date. */
    fun applyTemplate(template: Template) = updateDraft { template.draft.copy(date = it.date) }


    /**
     * Add the form's transaction to the journal (or save the changes to the one being edited), then close
     * the form; with [another], keep it open for the next one, on the same date.
     */
    fun saveTransaction(another: Boolean = false) {
        if (_saveState.value == SaveState.Saving) return
        val loaded = _load.value as? LoadState.Loaded ?: run {
            _saveState.value = SaveState.Failed(
                when (_load.value) {
                    LoadState.Loading -> "The journal is still opening. Try again in a moment."
                    LoadState.NoJournal -> NO_JOURNAL
                    else -> "The journal couldn't be read, so the transaction can't be added. Fix the journal first."
                }
            )
            return
        }
        val src = source
        val draft = _draft.value
        val styles = loaded.journal.styles
        val editing = (_screens.value.lastOrNull { it is Screen.Add } as? Screen.Add)?.editing
        val next = another && editing == null
        val layout = _amountLayout.value
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            val timings = Timings(if (editing == null) "Adding" else "Saving changes")
            val what = draft.description.ifEmpty { "the transaction" }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (editing == null) appendTransaction(src, draft, styles, layout, timings, "added $what")
                    else rewrite(src, "changes to $what", timings) {
                        Entry.replace(it, editing, draft, styles, layout = layout, verify = !light(LightPart.SKIP_READ_BACK))
                    }
                }
            }
            result.onSuccess { w ->
                val text = w.text
                _saveState.value = SaveState.Idle
                if (next) {
                    // Ready for the next one: same date, empty everything else. The journal isn't read
                    // again until the form closes, so nothing competes with typing the next one; the
                    // suggestions just learn the new payee and accounts.
                    _draft.value = blankDraft().copy(date = draft.date)
                    _formRevision.update { it + 1 }
                    _addedInSession.update { it + 1 }
                    _suggestions.update { it?.including(draft) }
                    pendingText = src to text
                } else {
                    _screens.update { s -> s.filterNot { it is Screen.Add || it is Screen.QuickAdd } }
                    applyWritten(src, text)
                }
                Log.i(TAG, timings.toString())
                changed(
                    src, w.undo,
                    when {
                        editing != null -> "Saved changes to $what"
                        else -> "Added $what on ${draft.date}"
                    } + if (showTimings) " (${timings.totalMs} ms)" else "",
                )
                lastSaveTimings = timings.toString()
            }.onFailure { e ->
                _saveState.value = SaveState.Failed(failureMessage(e, "save the transaction"))
            }
        }
    }

    /**
     * How the form writes amounts: spaced or aligned after account names (aligned unless changed), and
     * whether whole amounts get .00. Remembered between launches.
     */
    private val _amountLayout = MutableStateFlow(
        AmountLayout(
            aligned = prefs.getBoolean(KEY_ALIGN_AMOUNTS, AmountLayout.APP_DEFAULT.aligned),
            spaces = prefs.getInt(KEY_AMOUNT_SPACES, AmountLayout.DEFAULT_SPACES),
            column = prefs.getInt(KEY_AMOUNT_COLUMN, AmountLayout.DEFAULT_COLUMN),
            padDecimals = prefs.getBoolean(KEY_PAD_DECIMALS, true),
        )
    )
    val amountLayout: StateFlow<AmountLayout> = _amountLayout.asStateFlow()

    fun setAmountLayout(layout: AmountLayout) {
        val l = layout.copy(
            spaces = layout.spaces.coerceIn(AmountLayout.MIN_SPACES, AmountLayout.MAX_SPACES),
            column = layout.column.coerceIn(AmountLayout.MIN_COLUMN, AmountLayout.MAX_COLUMN),
        )
        _amountLayout.value = l
        prefs.edit()
            .putBoolean(KEY_ALIGN_AMOUNTS, l.aligned)
            .putInt(KEY_AMOUNT_SPACES, l.spaces)
            .putInt(KEY_AMOUNT_COLUMN, l.column)
            .putBoolean(KEY_PAD_DECIMALS, l.padDecimals)
            .apply()
    }

    /** Intelligent mode (Settings): hledger-iadd-like suggestions in quick add and the form. Remembered. */
    private val _intelligent = MutableStateFlow(prefs.getBoolean(KEY_INTELLIGENT, false))
    val intelligent: StateFlow<Boolean> = _intelligent.asStateFlow()

    fun setIntelligent(on: Boolean) {
        _intelligent.value = on
        prefs.edit().putBoolean(KEY_INTELLIGENT, on).apply()
    }

    /**
     * Lightweight mode (Settings): leaves out work that slows older phones down, chiefly when adding and
     * editing: the read-back check of a written transaction (the file is still written safely and compared),
     * Intelligent mode's loose matching and duplicate check, the form's live preview and scrolling, the
     * reminder check when the app opens, and a long undo history. Remembered.
     */
    private val _lightweight = MutableStateFlow(prefs.getBoolean(KEY_LIGHTWEIGHT, false))
    val lightweight: StateFlow<Boolean> = _lightweight.asStateFlow()

    fun setLightweight(on: Boolean) {
        _lightweight.value = on
        prefs.edit().putBoolean(KEY_LIGHTWEIGHT, on).apply()
        lightChanged()
    }

    /** Lightweight mode's parts, each of which can be turned off on its own (Settings, under Lightweight mode). */
    enum class LightPart(val label: String, val detail: String) {
        MANUAL_REFRESH("Refresh by hand", "The journal isn't read again by itself, after a change or on coming back to the app: the refresh button does it."),
        SKIP_READ_BACK("Skip the read-back check", "After writing a transaction, don't go through the journal again to check it reads back as entered (the file is still written safely and compared)."),
        INTELLIGENT("Lighter Intelligent mode", "Intelligent mode's loose matching and duplicate check are off (it still follows similar transactions)."),
        FORM("Simpler form", "No live preview of the transaction (a button shows it), and fields aren't scrolled to the top."),
        OPENING_CHECK("No reminder check on opening", "Reminders are checked in the background only."),
        SHORT_UNDO("Shorter undo history", "Undo keeps the last 5 changes rather than 20."),
    }

    /** The parts turned off; the others are in effect while Lightweight mode is on. Remembered. */
    private val _lightOff = MutableStateFlow(
        prefs.getStringSet(KEY_LIGHT_OFF, null).orEmpty().mapNotNull { n -> LightPart.entries.firstOrNull { it.name == n } }.toSet()
    )
    val lightOff: StateFlow<Set<LightPart>> = _lightOff.asStateFlow()

    /** Whether [part] is in effect: Lightweight mode on, and the part not turned off. */
    private fun light(part: LightPart): Boolean = _lightweight.value && part !in _lightOff.value

    fun setLightPart(part: LightPart, on: Boolean) {
        val off = if (on) _lightOff.value - part else _lightOff.value + part
        _lightOff.value = off
        prefs.edit().putStringSet(KEY_LIGHT_OFF, off.map { it.name }.toSet()).apply()
        lightChanged()
    }

    /** After Lightweight mode or one of its parts changed: changes waiting are shown, and Undo's limit set. */
    private fun lightChanged() {
        if (!light(LightPart.MANUAL_REFRESH) && _stale.value) reload()
        history.maxSteps = if (light(LightPart.SHORT_UNDO)) LIGHT_UNDO_STEPS else UNDO_STEPS
        historyChanged()
    }

    /** Intelligent mode's costly parts, each on unless turned off (for slower phones). Remembered. */
    private val _intelligentFuzzy = MutableStateFlow(prefs.getBoolean(KEY_INTELLIGENT_FUZZY, true))
    val intelligentFuzzy: StateFlow<Boolean> = _intelligentFuzzy.asStateFlow()
    private val _intelligentDuplicates = MutableStateFlow(prefs.getBoolean(KEY_INTELLIGENT_DUPLICATES, true))
    val intelligentDuplicates: StateFlow<Boolean> = _intelligentDuplicates.asStateFlow()

    fun setIntelligentFuzzy(on: Boolean) {
        _intelligentFuzzy.value = on
        prefs.edit().putBoolean(KEY_INTELLIGENT_FUZZY, on).apply()
    }

    fun setIntelligentDuplicates(on: Boolean) {
        _intelligentDuplicates.value = on
        prefs.edit().putBoolean(KEY_INTELLIGENT_DUPLICATES, on).apply()
    }

    /** The main screen's chips folded away, to see only the accounts. Remembered. */
    private val _headerCollapsed = MutableStateFlow(prefs.getBoolean(KEY_HEADER_COLLAPSED, false))
    val headerCollapsed: StateFlow<Boolean> = _headerCollapsed.asStateFlow()

    fun setHeaderCollapsed(on: Boolean) {
        _headerCollapsed.value = on
        prefs.edit().putBoolean(KEY_HEADER_COLLAPSED, on).apply()
    }

    /** The transaction to follow for [description] (Intelligent mode), from the journal as read. */
    fun similarTo(description: String): Transaction? =
        (_load.value as? LoadState.Loaded)?.journal?.let { Intelligent.similar(it, description) }

    /** Quick add: Enter takes the only suggestion left at the payee and account prompts. Remembered. */
    private val _quickAddEnterTakesOnly = MutableStateFlow(prefs.getBoolean(KEY_QUICK_ADD_SOLE, false))
    val quickAddEnterTakesOnly: StateFlow<Boolean> = _quickAddEnterTakesOnly.asStateFlow()

    fun setQuickAddEnterTakesOnly(on: Boolean) {
        _quickAddEnterTakesOnly.value = on
        prefs.edit().putBoolean(KEY_QUICK_ADD_SOLE, on).apply()
    }

    /** Quick add puts each prompt's default in the box as text to edit, like the form, not as a hint. */
    private val _quickAddFill = MutableStateFlow(prefs.getBoolean(KEY_QUICK_ADD_FILL, false))
    val quickAddFill: StateFlow<Boolean> = _quickAddFill.asStateFlow()

    fun setQuickAddFill(on: Boolean) {
        _quickAddFill.value = on
        prefs.edit().putBoolean(KEY_QUICK_ADD_FILL, on).apply()
    }

    /** Optional fields the person has hidden in the form. Remembered between launches. */
    private val _hiddenFields = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN_FIELDS, emptySet()).orEmpty()
            .mapNotNull { n -> FormField.entries.firstOrNull { it.name == n } }.toSet()
    )
    val hiddenFields: StateFlow<Set<FormField>> = _hiddenFields.asStateFlow()

    fun setHiddenFields(fields: Set<FormField>) {
        _hiddenFields.value = fields
        prefs.edit().putStringSet(KEY_HIDDEN_FIELDS, fields.map { it.name }.toSet()).apply()
    }

    /** Suggest only payees declared with payee directives in the form. Remembered between launches. */
    private val _declaredPayeesOnly = MutableStateFlow(prefs.getBoolean(KEY_DECLARED_PAYEES_ONLY, false))
    val declaredPayeesOnly: StateFlow<Boolean> = _declaredPayeesOnly.asStateFlow()

    fun setDeclaredPayeesOnly(on: Boolean) {
        _declaredPayeesOnly.value = on
        prefs.edit().putBoolean(KEY_DECLARED_PAYEES_ONLY, on).apply()
    }

    /**
     * Where the Payees screen was (sort, filter, place in the list, and the list worked out), so coming back
     * from a payee finds it as it was. The sort is also remembered between launches.
     */
    private val _payeesPlace = MutableStateFlow(
        PayeesPlace(sort = PayeeSort.entries.firstOrNull { it.name == prefs.getString(KEY_PAYEES_SORT, null) } ?: PayeeSort.RECENT)
    )
    val payeesPlace: StateFlow<PayeesPlace> = _payeesPlace.asStateFlow()

    fun setPayeesPlace(place: PayeesPlace) {
        if (place.sort != _payeesPlace.value.sort) prefs.edit().putString(KEY_PAYEES_SORT, place.sort.name).apply()
        _payeesPlace.value = place
    }

    /**
     * Which payees the Payees screen lists: declared ones (the default, and the least to add up), all,
     * or the ones not declared. Remembered between launches.
     */
    private val _payeesView = MutableStateFlow(
        PayeesView.entries.firstOrNull { it.name == prefs.getString(KEY_PAYEES_VIEW, null) } ?: PayeesView.DECLARED
    )
    val payeesView: StateFlow<PayeesView> = _payeesView.asStateFlow()

    fun setPayeesView(view: PayeesView) {
        _payeesView.value = view
        prefs.edit().putString(KEY_PAYEES_VIEW, view.name).apply()
    }

    /** The journal text written while adding several in a row, shown when the form closes. */
    @Volatile
    private var pendingText: Pair<Source, String>? = null

    private fun flushPending() {
        pendingText?.let { (src, text) -> applyWritten(src, text) }
    }

    /** Show how long saving took in the message after each save (Settings), to find what's slow. */
    private val _showTimings = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TIMINGS, false))
    val showTimingsFlow: StateFlow<Boolean> = _showTimings.asStateFlow()
    private val showTimings get() = _showTimings.value

    fun setShowTimings(on: Boolean) {
        _showTimings.value = on
        prefs.edit().putBoolean(KEY_SHOW_TIMINGS, on).apply()
    }

    /** Each step of the last save, eg "Adding: read 40 ms, check 55 ms, ...", for Settings. */
    @Volatile
    var lastSaveTimings: String? = null
        private set

    /** Goes up each time the form is cleared for the next transaction, so it starts fresh. */
    private val _formRevision = MutableStateFlow(0)
    val formRevision: StateFlow<Int> = _formRevision.asStateFlow()

    /** How many transactions were added since the form was opened. */
    private val _addedInSession = MutableStateFlow(0)
    val addedInSession: StateFlow<Int> = _addedInSession.asStateFlow()

    /**
     * Show the journal as it is after a change, from the text just written instead of reading the file
     * again. Parsing happens in the background; the report updates when it's done.
     */
    private fun applyWritten(src: Source, text: String) {
        pendingText = null
        if (src != source) return
        val name = (lastRead?.first) ?: return load(src, quiet = true)
        lastRead = name to text
        // Lightweight mode: the journal isn't read again by itself (↻ does it). The text is kept, so changing
        // a transaction still finds it as it is in the file, and the screens say what's waiting.
        if (light(LightPart.MANUAL_REFRESH)) {
            _stale.value = true
            return
        }
        loadJob?.cancel()
        val auto = autoOf(_options.value)
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { parse(name, text, src, auto) }
            lastLoadedAt = SystemClock.elapsedRealtime()
            _load.value = result
        }
    }

    private fun failureMessage(e: Throwable, action: String) =
        if (e is EntryException) e.message.orEmpty() else "Couldn't $action: ${e.message ?: e}"

    /** The journal text after a change, and how to undo it. */
    private class Written(val text: String, val undo: UndoRecord)

    /**
     * Replace the whole journal with [change] applied to its text, then check the file holds exactly that.
     * Only for plain UTF-8 files, which come back byte for byte apart from the change.
     */
    private fun rewrite(
        src: Source,
        what: String,
        timings: Timings = Timings("Rewriting"),
        change: (String) -> String,
    ): Written {
        when (src) {
            Source.None -> throw EntryException(NO_JOURNAL)

            is Source.File -> {
                val store = UriStore(getApplication<Application>().contentResolver, src.uri)
                val before = readBytes(store)
                val existing = before.toString(Charsets.UTF_8)
                if (!existing.toByteArray(Charsets.UTF_8).contentEquals(before)) {
                    throw EntryException("This file isn't plain UTF-8 text, so Ledgedroid won't rewrite it. Nothing was changed.")
                }
                timings.step("read")
                val changed = change(existing)
                timings.step("check")
                backUpBeforeChange(src, before, timings)
                val after = changed.toByteArray(Charsets.UTF_8)
                SafeWrite.replace(store, before, after) { backups.saveRecovery(src.uri, it) }
                timings.step("write")
                backupRun = BackupRun(src.uri, after)
                return Written(changed, UndoRecord(what, before, after))
            }
        }
    }

    /**
     * Save a copy of the journal as it is before a change, if that's turned on. A failed backup stops the
     * change, so a change is never made without the backup the person asked for.
     */
    private fun backUpBeforeChange(src: Source.File, before: ByteArray, timings: Timings) {
        if (!backups.settings().beforeChange) return
        // When adding transactions one after another, back up once at the start of the run rather than
        // before each one: the journal then differs from that backup only by what was added in the app.
        val run = backupRun
        if (run != null && run.uri == src.uri && run.afterLastSave.contentEquals(before)) return
        val r = backups.backUp(src.uri, before)
        timings.step("backup")
        when (r) {
            is BackupResult.Failed -> throw EntryException(
                "Couldn't back up the journal before changing it (${r.message}), so nothing was changed. " +
                        "Check the backup folder in Settings, or turn off backups before changes."
            )

            else -> Unit
        }
    }

    /**
     * The journal file's contents right after the last change saved from the form, while it's open. If the
     * file still holds exactly this at the next save (nothing else changed it), no new backup is needed.
     */
    private class BackupRun(val uri: Uri, val afterLastSave: ByteArray)

    @Volatile
    private var backupRun: BackupRun? = null

    /** Save the form as a template: the one being edited, or a new one named [name]. */
    fun saveTemplate(name: String) {
        val editing = (_screens.value.lastOrNull { it is Screen.Add } as? Screen.Add)?.editTemplateId
        val template = Template(editing ?: UUID.randomUUID().toString(), name.trim(), _draft.value)
        val existing = _templates.value
        setTemplates(if (existing.any { it.id == template.id }) existing.map { if (it.id == template.id) template else it } else existing + template)
        if (editing != null) _screens.update { s -> s.filterNot { it is Screen.Add } }
        _message.value = "Saved the template \"${template.name}\""
    }

    fun deleteTemplate(id: String) = setTemplates(_templates.value.filterNot { it.id == id })

    fun moveTemplate(id: String, by: Int) {
        val list = _templates.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        setTemplates(list)
    }

    private fun setTemplates(templates: List<Template>) {
        _templates.value = templates
        Store.writeTemplates(prefs, templates)
        AddWidget.updateAll(getApplication())
    }

    // ------------------------------------------------------------------ included files

    /** The folder include directives are read from (Settings › Included files), or null. */
    private val _includeFolder = MutableStateFlow(IncludeFolder.chosen(app)?.toString())
    val includeFolder: StateFlow<String?> = _includeFolder.asStateFlow()

    /** Read included files from [tree] (or stop with null), then read the journal again. */
    fun setIncludeFolder(tree: Uri?) {
        IncludeFolder.choose(getApplication(), tree)
        _includeFolder.value = tree?.toString()
        load(source)
    }

    private fun includeResolver(src: Source): IncludeResolver? =
        (src as? Source.File)?.let { IncludeFolder.resolver(getApplication(), it.uri) }

    // ------------------------------------------------------------------ changes across the journal

    /** A change to preview: the lines it changes, and what else to do once it's made. */
    class PendingEdit(val plan: EditPlan, val message: String, val after: () -> Unit = {})

    private val _pendingEdit = MutableStateFlow<PendingEdit?>(null)
    val pendingEdit: StateFlow<PendingEdit?> = _pendingEdit.asStateFlow()

    private fun preview(make: (String, Journal) -> EditPlan, message: String, after: () -> Unit = {}) {
        val text = lastRead?.second ?: return
        val journal = (_load.value as? LoadState.Loaded)?.journal ?: return
        _pendingEdit.value = runCatching { PendingEdit(make(text, journal), message, after) }
            .getOrElse { e -> _message.value = e.message; null }
    }

    /** Rename [from] (and its subaccounts) to [to] across the journal, shown first; pins, hiding and types follow. */
    fun previewRename(from: String, to: String) {
        val target = to.trim()
        if (target.isEmpty() || target == from) return
        preview({ text, j -> JournalEdits.renameAccount(text, j, from, target) }, "Renamed $from to $target") {
            fun moved(a: String) = if (a == from || a.startsWith("$from:")) target + a.removePrefix(from) else a
            setPinned(_pinned.value.map(::moved).distinct())
            val h = _hidden.value
            setHidden(h.copy(listed = h.listed.map(::moved).toSet(), paused = h.paused.map(::moved).toSet()))
            val types = _accountTypes.value.mapKeys { (k, _) -> moved(k) }
            if (types != _accountTypes.value) {
                _accountTypes.value = types
                Store.writeAccountTypes(prefs, types)
            }
        }
    }

    /** Move [payee]'s postings to [from] to [to], shown first. */
    fun previewRecategorise(payee: String, from: String, to: String) {
        val target = to.trim()
        if (target.isEmpty() || target == from) return
        preview({ text, j -> JournalEdits.recategorise(text, j, payee, from, target) }, "Moved $payee's $from postings to $target")
    }

    /** Declare [accounts] and [payees] (from Journal health), shown first. */
    fun previewDeclare(accounts: List<String>, payees: List<String>) =
        preview({ text, _ -> JournalEdits.declare(text, accounts, payees) },
            listOfNotNull(accounts.size.takeIf { it > 0 }?.let { "Declared $it account${if (it == 1) "" else "s"}" },
                payees.size.takeIf { it > 0 }?.let { "declared $it payee${if (it == 1) "" else "s"}" }).joinToString(" and "))

    fun cancelEdit() {
        _pendingEdit.value = null
    }

    /** Make the previewed change, with Undo. */
    fun applyEdit() {
        val pending = _pendingEdit.value ?: return
        _pendingEdit.value = null
        if (pending.plan.isEmpty) return
        val src = source
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { rewrite(src, pending.message.lowercase()) { pending.plan.apply(it) } } }
            result.onSuccess { w ->
                pending.after()
                val n = pending.plan.changes.size
                changed(src, w.undo, "${pending.message} (${if (n == 1) "1 line" else "$n lines"})")
                applyWritten(src, w.text)
            }.onFailure { e -> _message.value = failureMessage(e, "make the change") }
        }
    }

    // ------------------------------------------------------------------ all transactions

    /** The All transactions view: its filter, order and page size, the pages loaded, and where it was scrolled. */
    data class AllTransactionsUi(
        val filter: String = "",
        val order: TransactionOrder = TransactionOrder.NEWEST,
        val pageSize: Int = 50,
        val items: List<Transaction> = emptyList(),
        /** Where the next page starts, or null when everything matching is loaded. */
        val next: Int? = null,
        /** How many transactions the journal has, to show without a filter. */
        val total: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
        /** The journal the items come from: when it changes, they're loaded again. */
        val journal: Journal? = null,
        val scroll: Int = 0,
    )

    private val _allTx = MutableStateFlow(AllTransactionsUi(pageSize = savedPageSize()))

    /** The page size chosen last time, if it's still one of the choices. */
    private fun savedPageSize(): Int {
        val n: Int = prefs.getInt(KEY_TX_PAGE_SIZE, 50)
        return if (n in TX_PAGE_SIZES) n else 50
    }
    val allTx: StateFlow<AllTransactionsUi> = _allTx.asStateFlow()
    private var allTxJob: kotlinx.coroutines.Job? = null
    // The ordered list and filter the loaded pages come from, so Load more carries on with them.
    private var allTxList: List<Transaction> = emptyList()
    private var allTxMatch: ((Transaction) -> Boolean)? = null

    /** Load the first page when All transactions opens, unless it's loaded from this journal already. */
    fun ensureAllTx() {
        val j = (_load.value as? LoadState.Loaded)?.journal ?: return
        val ui = _allTx.value
        if (ui.journal !== j || (ui.items.isEmpty() && ui.error == null)) refreshAllTx(keep = ui.items.size)
    }

    /** Filter with [text] (once Enter is pressed). */
    fun setAllTxFilter(text: String) {
        _allTx.update { it.copy(filter = text, scroll = 0) }
        refreshAllTx()
    }

    fun setAllTxOrder(order: TransactionOrder) {
        _allTx.update { it.copy(order = order, scroll = 0) }
        refreshAllTx()
    }

    fun setAllTxPageSize(n: Int) {
        _allTx.update { it.copy(pageSize = n) }
        prefs.edit().putInt(KEY_TX_PAGE_SIZE, n).apply()
        refreshAllTx(keep = _allTx.value.items.size)
    }

    fun saveAllTxScroll(index: Int) = _allTx.update { it.copy(scroll = index) }

    /**
     * Load the first page again (after the filter, order or journal changed), or with [keep] as many as
     * were loaded, so going back to the list after a change keeps its place. With [wait], after a pause in
     * typing.
     */
    fun refreshAllTx(keep: Int = 0, wait: Boolean = false) {
        allTxJob?.cancel()
        allTxJob = viewModelScope.launch {
            if (wait) kotlinx.coroutines.delay(250)
            val j = (_load.value as? LoadState.Loaded)?.journal ?: return@launch
            val ui = _allTx.value
            _allTx.update { it.copy(loading = true) }
            val result = withContext(Dispatchers.Default) {
                val (match, error) = TransactionSearch.matcher(ui.filter, j.styles)
                if (error != null) return@withContext null to error
                val list = TransactionPages.ordered(j, ui.order)
                allTxList = list
                allTxMatch = match
                TransactionPages.page(list, match, 0, maxOf(ui.pageSize, keep)) to null
            }
            val (page, error) = result
            _allTx.update {
                it.copy(
                    items = page?.transactions.orEmpty(), next = page?.next, error = error, loading = false, journal = j,
                    total = j.transactions.count { t -> !t.forecast },
                    scroll = if (keep > 0) it.scroll else 0,
                )
            }
        }
    }

    /** The next page, carrying on from where the last stopped. */
    fun loadMoreTx() {
        val ui = _allTx.value
        val from = ui.next ?: return
        if (ui.loading) return
        allTxJob = viewModelScope.launch {
            _allTx.update { it.copy(loading = true) }
            val page = withContext(Dispatchers.Default) { TransactionPages.page(allTxList, allTxMatch, from, ui.pageSize, fromMatches = true) }
            _allTx.update { it.copy(items = it.items + page.transactions, next = page.next, loading = false) }
        }
    }

    // ------------------------------------------------------------------ reminders

    /** When [id]'s transaction recurs, for reminders; null stops them. */
    fun setTemplateSchedule(id: String, schedule: Schedule?) {
        setTemplates(_templates.value.map { if (it.id == id) it.copy(schedule = schedule) else it })
        if (schedule != null && !_alertSettings.value.reminders) setAlertSettings(_alertSettings.value.copy(reminders = true))
    }

    private val _alertSettings = MutableStateFlow(Alerts.settings(app))
    val alertSettings: StateFlow<AlertSettings> = _alertSettings.asStateFlow()

    fun setAlertSettings(s: AlertSettings) {
        _alertSettings.value = s
        Alerts.setSettings(getApplication(), s)
        checkAlerts()
    }

    /** Tell about anything due now (also done in the background), with the journal as read if there is one. */
    fun checkAlerts() {
        val loaded = (_load.value as? LoadState.Loaded)?.journal
        viewModelScope.launch(Dispatchers.IO) { runCatching { Alerts.check(getApplication(), loaded = loaded) } }
    }

    init {
        // Each time the app opens (as well as in the background): once the journal is read, using it rather
        // than reading and parsing it a second time. Lightweight mode leaves it to the background check.
        viewModelScope.launch {
            val journal = _load.first { it is LoadState.Loaded || it is LoadState.Failed || it is LoadState.NoJournal }
            if (!light(LightPart.OPENING_CHECK) && journal is LoadState.Loaded) {
                withContext(Dispatchers.IO) { runCatching { Alerts.check(getApplication(), loaded = journal.journal) } }
            }
        }
    }

    // ------------------------------------------------------------------ sharing into quick add

    private val _sharePatterns = MutableStateFlow(Store.readSharePatterns(prefs))
    val sharePatterns: StateFlow<List<SharePattern>> = _sharePatterns.asStateFlow()

    fun setSharePatterns(patterns: List<SharePattern>) {
        _sharePatterns.value = patterns
        Store.writeSharePatterns(prefs, patterns)
    }

    /** Read shared text, as quick add would. */
    fun readShared(text: String, patterns: List<SharePattern> = _sharePatterns.value): SharedEntry =
        ShareParse.parse(text, patterns, LocalDate.now(), (_load.value as? LoadState.Loaded)?.journal?.styles?.commodities().orEmpty())

    /** Start quick add from shared text: what was picked out becomes its defaults. */
    fun startShared(text: String) {
        if (source is Source.None) {
            _message.value = NO_JOURNAL
            return
        }
        val shared = readShared(text)
        _draft.value = blankDraft()
        openForm(Screen.QuickAdd(shared.templateId?.takeIf { id -> _templates.value.any { it.id == id } }, shared))
    }

    /**
     * Add the transaction to the end of the journal file, after checking it reads back as entered.
     * Only the new lines are written, so the rest of the file stays byte for byte as it was. Returns
     * the journal text as written.
     */
    private fun appendTransaction(
        src: Source,
        draft: TransactionDraft,
        styles: Styles,
        layout: AmountLayout,
        timings: Timings,
        what: String
    ): Written {
        when (src) {
            Source.None -> throw EntryException(NO_JOURNAL)

            is Source.File -> {
                val store = UriStore(getApplication<Application>().contentResolver, src.uri)
                val before = readBytes(store)
                val existing = before.toString(Charsets.UTF_8)
                timings.step("read")
                val added = Entry.addition(existing, draft, styles, layout = layout, verify = !light(LightPart.SKIP_READ_BACK))
                timings.step("check")
                backUpBeforeChange(src, before, timings)
                val addedBytes = added.toByteArray(Charsets.UTF_8)
                SafeWrite.append(store, before, addedBytes) { backups.saveRecovery(src.uri, it) }
                timings.step("write")
                val after = before + addedBytes
                backupRun = BackupRun(src.uri, after)
                return Written(existing + added, UndoRecord(what, before, after))
            }
        }
    }

    private fun readBytes(store: UriStore): ByteArray = try {
        store.read()
    } catch (e: SecurityException) {
        throw EntryException("Ledgedroid can no longer read this file. Open it again from the menu.")
    } catch (e: Exception) {
        throw EntryException("The journal file couldn't be opened.")
    }

    // ------------------------------------------------------------------ backups

    private val _backupUi = MutableStateFlow(BackupUi())
    val backupUi: StateFlow<BackupUi> = _backupUi.asStateFlow()

    /** Read the backup settings and state for the settings screen. */
    fun refreshBackups() {
        val src = source
        viewModelScope.launch {
            _backupUi.value = withContext(Dispatchers.IO) { backupUiFor(src) }
        }
    }

    private fun backupUiFor(src: Source): BackupUi {
        val journal = (src as? Source.File)?.takeIf { it.persisted }?.uri
        val folder = journal?.let { backups.folder(it) }
        return BackupUi(
            settings = backups.settings(),
            journalName = journal?.let { backups.journalName(it) },
            folderName = folder?.let { backups.folderName(it) ?: "Chosen folder" },
            lastBackupAt = journal?.let { backups.lastBackupAt(it) },
            lastError = journal?.let { backups.lastError(it) },
            backups = journal?.let { runCatching { backups.list(it) }.getOrDefault(emptyList()) }.orEmpty(),
            noJournal = src is Source.None,
            busy = false,
        )
    }

    /** The journal file, eg to start the folder picker next to it. */
    fun journalUri(): Uri? = (source as? Source.File)?.uri

    fun updateBackupSettings(change: (BackupSettings) -> BackupSettings) {
        val s = change(_backupUi.value.settings)
        backups.saveSettings(s)
        _backupUi.update { it.copy(settings = s) }
    }

    fun setBackupFolder(tree: Uri) {
        val journal = (source as? Source.File)?.uri ?: return
        viewModelScope.launch {
            val warning = withContext(Dispatchers.IO) {
                runCatching { backups.setFolder(journal, tree) }
                    .getOrElse { "Ledgedroid couldn't keep access to this folder: ${it.message ?: it}" }
            }
            if (warning != null) _message.value = warning
            refreshBackups()
        }
    }

    fun backUpNow() {
        val journal = (source as? Source.File)?.uri ?: return
        _backupUi.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { backups.backUp(journal, force = true) }
            _message.value = when (r) {
                is BackupResult.Made -> "Backed up as ${r.name}"
                BackupResult.Unchanged -> "The journal hasn't changed since the last backup"
                BackupResult.NoFolder -> "Choose a backup folder first"
                is BackupResult.Failed -> "Backup failed: ${r.message}"
            }
            refreshBackups()
        }
    }

    // ------------------------------------------------------------------ searching transactions

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    /** All transactions matching the search, newest first; null while there's no journal. */
    val searchResults: StateFlow<TransactionSearch.Result?> = combine(_load, _searchText) { load, text ->
        val journal = (load as? LoadState.Loaded)?.journal ?: return@combine null
        if (text.isBlank()) TransactionSearch.Result(emptyList(), 0, null)
        else TransactionSearch.search(journal, text)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Catch up on a scheduled backup that's due, eg if Android delayed the background job. */
    private fun backUpIfDue() {
        if (source !is Source.File) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { backups.runScheduledIfDue() } }
    }

    // ------------------------------------------------------------------ loading

    private fun load(src: Source, quiet: Boolean = false) {
        loadJob?.cancel()
        if (!quiet) _load.value = LoadState.Loading
        val auto = autoOf(_options.value)
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readAndParse(src, auto) }
            lastLoadedAt = SystemClock.elapsedRealtime()
            _load.value = result
        }
    }

    private fun readAndParse(src: Source, auto: Boolean): LoadState {
        if (src !is Source.File) {
            lastRead = null
            return LoadState.NoJournal
        }
        val name: String
        val text: String
        try {
            text = getApplication<Application>().contentResolver.openInputStream(src.uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return LoadState.Failed("Journal", "The file couldn't be opened.", 0, "")
            name = displayName(src.uri)
        } catch (e: Exception) {
            return LoadState.Failed(
                "Journal",
                "Ledgedroid can no longer read this file. It may have been moved or deleted. Open it again from the menu.",
                0, "",
            )
        }
        lastRead = name to text
        return parse(name, text, src, auto)
    }

    private fun parse(name: String, text: String, src: Source, auto: Boolean): LoadState {
        // Read afresh: anything waiting to be shown is shown now.
        _stale.value = false
        return try {
            LoadState.Loaded(JournalParser(auto = auto).parse(text, includeResolver(src)).copy(assignedTypes = _accountTypes.value), name, src)
        } catch (e: JournalException) {
            LoadState.Failed(name, e.message ?: "Couldn't read this journal.", e.line, e.excerpt)
        } catch (e: OutOfMemoryError) {
            LoadState.Failed(name, "This journal is too large to open on this device.", 0, "")
        }
    }

    private fun displayName(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        return try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Journal"
    }

    // ------------------------------------------------------------------ reports

    private fun buildReport(
        journal: Journal,
        stored: Options,
        hiding: Hiding,
        pinned: List<String>,
        hideZeroPins: Boolean,
    ): ReportUi {
        val ui = buildReport(journal, stored, hiding.excluded, hiding.rowsOnly)
        val hidden = hiding.excluded + hiding.rowsOnly
        val pins = pinned.filterNot { Pins.isHidden(it, hidden) }
        if (pins.isEmpty()) return ui
        val today = LocalDate.now()
        val query = parseQuery(ui.command.queryTerms).first.excludingAccounts(hiding.excluded)
        val opts = ui.options
        val ropts = opts.reportOptions(today)
        val j = ui.journal
        val periods = ui.multi?.periods ?: ui.multiCompound?.periods
        val budget = ui.budget
        val lines = pins.mapNotNull { a ->
            when {
                budget != null -> Pins.budget(budget, a)?.let { PinnedLine(a, budget = it) }
                periods != null -> Pins.multi(j, query, ropts, opts.interval, opts.kind, periods, a)
                    ?.let { (s, r) -> PinnedLine(a, s, row = r) }

                else -> Pins.single(j, query, ropts, opts.kind, a)?.let { (s, amt) -> PinnedLine(a, s, amount = amt) }
            }
        }
        if (!hideZeroPins) return ui.copy(pinned = lines)
        val (zero, shown) = lines.partition { it.isZero }
        return ui.copy(pinned = shown, pinnedAtZero = zero.size)
    }

    /**
     * The report for [stored] options, leaving out the postings of [excluded] accounts, and only the rows of
     * [rowsHidden] ones (their amounts still count in parents and totals).
     */
    private fun buildReport(journal: Journal, stored: Options, excluded: Set<String>, rowsHidden: Set<String>): ReportUi {
        val today = LocalDate.now()
        val (opts, command) = stored.resolve(today)
        val (filter, queryError) = parseQuery(command.queryTerms)
        val query = filter.excludingAccounts(excluded)
        val ropts = opts.reportOptions(today)
        val (j, forecastNote) = withForecast(journal, command, filter, ropts.span, today)
        val error = command.error ?: queryError ?: forecastNote
        // --drop shortens names in list mode, like hledger.
        val drop = command.drop?.takeIf { it > 0 && opts.mode == AccountListMode.FLAT }
        // -V / -X: market values from the journal's P prices, on each column's last day (or the report's end).
        val valuer = command.value?.let { Valuation(j.prices, it) }
        val valueNote = if (valuer != null && !valuer.hasPrices) "${command.value?.describe()}: this journal has no P prices, so amounts are shown as they are" else null
        val at = Valuation.reportDate(j, ropts.span, today)
        val base = ReportUi(j, ropts.span, ropts.accumulation, null, null, error ?: valueNote, options = opts, command = command)
        if (opts.multiPeriod && opts.kind != ReportKind.BUDGET) {
            val multi = if (opts.kind == ReportKind.BALANCE) MultiPeriodReports.balance(j, query, ropts, opts.interval)
                .let { valuer?.multiPeriod(it) ?: it } else null
            val compound = if (opts.kind == ReportKind.BALANCE) null
            else MultiPeriodReports.compound(j, query, ropts, opts.interval, opts.kind).let { valuer?.multiCompound(it) ?: it }
            return base.copy(
                multi = (drop?.let { multi?.dropNames(it) } ?: multi)?.withoutHiddenRows(rowsHidden),
                multiCompound = (drop?.let { compound?.dropNames(it) } ?: compound)?.withoutHiddenRows(rowsHidden),
            )
        }
        return when (opts.kind) {
            ReportKind.BALANCE -> Reports.balance(j, query, ropts).let { valuer?.balance(it, at) ?: it }.let { r ->
                base.copy(single = (drop?.let { r.dropNames(it) } ?: r).withoutHiddenRows(rowsHidden))
            }

            ReportKind.BUDGET -> Budgets.report(j, query, opts.budgetOptions(today, command.budgetPattern)).let { valuer?.budget(it) ?: it }.let { r ->
                base.copy(budget = (drop?.let { r.dropNames(it) } ?: r).withoutHiddenRows(rowsHidden))
            }

            else -> Reports.compound(j, query, ropts, opts.kind).let { valuer?.compound(it, at) ?: it }.let { r ->
                base.copy(compound = (drop?.let { r.dropNames(it) } ?: r).withoutHiddenRows(rowsHidden))
            }
        }
    }

    /**
     * The journal with --forecast transactions added, if the filter asks for them, and a note if it
     * can't (eg there are no periodic rules). The forecast runs to the report's end, or its date: term's.
     */
    private fun withForecast(
        journal: Journal,
        command: ReportCommand,
        filter: Query,
        span: DateSpan,
        today: LocalDate,
    ): Pair<Journal, String?> {
        val expr = command.forecast ?: return journal to null
        if (journal.periodicRules.isEmpty()) {
            return journal to "--forecast: this journal has no periodic rules (lines starting with ~) to forecast from"
        }
        val dates = filter.dateSpan
        val reportSpan = DateSpan(span.start ?: dates?.start, span.end ?: dates?.end)
        return try {
            Forecast.extend(journal, Forecast.span(journal, expr, reportSpan, today)) to null
        } catch (e: IllegalArgumentException) {
            journal to "--forecast: ${e.message}"
        }
    }

    private fun buildRegister(journal: Journal, stored: Options, target: RegisterTarget, hidden: Set<String>): RegisterUi {
        val today = LocalDate.now()
        val (opts, command) = stored.resolve(today)
        val filter = parseQuery(command.queryTerms).first
        val ropts = opts.reportOptions(today)
            .let { o -> target.span?.let { o.copy(span = it) } ?: o }
            // Only charges, refunds etc, or only what the advanced filters keep: their total in the period,
            // not a balance carried in from before.
            .let { o ->
                if (target.amountSign != AmountSign.ALL || target.filter.isActive) o.copy(accumulation = Accumulation.CHANGE) else o
            }
        val j = withForecast(journal, command, filter, opts.reportOptions(today).span, today).first
        val section = target.section
        val sign = section?.sign ?: 1
        val report = Reports.register(
            j, filter.excludingAccounts(hidden), ropts, target.account, target.includeSubaccounts,
            accountFilter = section?.let { s -> { a: String -> j.accountType(a) in s.types } },
            sign = sign,
            amountSign = target.amountSign,
            filter = target.filter,
        ).let { r -> command.value?.let { Valuation(j.prices, it).register(r, Valuation.reportDate(j, ropts.span, today)) } ?: r }
        val choices = SignChoices.forAccount(j.accountType(target.account), sign * (if (ropts.invert) -1 else 1))
        return RegisterUi(target, j, ropts.span, ropts.accumulation, report, choices)
    }

    private fun parseQuery(terms: List<String>): Pair<Query, String?> = try {
        Query.parseTerms(terms, LocalDate.now()) to null
    } catch (e: IllegalArgumentException) {
        Query.ALL to e.message
    }

    // ------------------------------------------------------------------ persistence

    private fun readSource(): Source =
        prefs.getString(KEY_URI, null)?.let { Source.File(Uri.parse(it), persisted = true) } ?: Source.None

    private fun readOptions(): Options = migrateKind(readStoredOptions(), prefs.getString("budgetPattern", null) ?: "")

    private fun readStoredOptions(): Options = Options(
        kind = enumOr(prefs.getString("kind", null), ReportKind.BALANCE),
        preset = enumOr(prefs.getString("preset", null), PeriodPreset.ALL),
        customStart = prefs.getString("customStart", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        customEnd = prefs.getString("customEnd", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        mode = enumOr(prefs.getString("mode", null), AccountListMode.TREE),
        depth = prefs.getInt("depth", 0).takeIf { it > 0 },
        showEmpty = prefs.getBoolean("showEmpty", false),
        sortByAmount = prefs.getBoolean("sortByAmount", false),
        invert = prefs.getBoolean("invert", false),
        endBalance = prefs.getBoolean("endBalance", false),
        atCost = prefs.getBoolean("atCost", false),
        auto = prefs.getBoolean("auto", false),
        interval = enumOr(prefs.getString("interval", null), ReportInterval.MONTHLY),
        periodTable = prefs.getBoolean("periodTable", prefs.getBoolean("budgetTable", false)),
        multiPeriod = prefs.getBoolean("multiPeriod", false),
        query = prefs.getString("query", null) ?: "",
    )

    private fun saveOptions(o: Options) {
        prefs.edit()
            .putString("kind", o.kind.name)
            .putString("preset", o.preset.name)
            .putString("customStart", o.customStart?.toString())
            .putString("customEnd", o.customEnd?.toString())
            .putString("mode", o.mode.name)
            .putInt("depth", o.depth ?: 0)
            .putBoolean("showEmpty", o.showEmpty)
            .putBoolean("sortByAmount", o.sortByAmount)
            .putBoolean("invert", o.invert)
            .putBoolean("endBalance", o.endBalance)
            .putBoolean("atCost", o.atCost)
            .putBoolean("auto", o.auto)
            .putString("interval", o.interval.name)
            .remove("budgetPattern") // now --budget=TEXT in the filter
            .putBoolean("periodTable", o.periodTable)
            .putBoolean("multiPeriod", o.multiPeriod)
            .putString("query", o.query)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    // ------------------------------------------------------------------ landing screen

    /** What the app opens on (Settings › Open on). */
    enum class Landing(val label: String) {
        ACCOUNTS("Accounts"),
        ALL_TRANSACTIONS("All transactions"),
        ADD("Add transaction"),
        SAVED_REPORTS("Saved reports"),
    }

    private val _landing = MutableStateFlow(
        Landing.entries.firstOrNull { it.name == prefs.getString(KEY_LANDING, null) } ?: Landing.ACCOUNTS
    )
    val landing: StateFlow<Landing> = _landing.asStateFlow()

    fun setLanding(l: Landing) {
        _landing.value = l
        prefs.edit().putString(KEY_LANDING, l.name).apply()
    }

    init {
        // On opening (not when the screen turns): the chosen screen, over Accounts, so Back goes there. Last in
        // the class, so everything it uses is ready. A widget, shortcut or shared text opens what it asks for
        // on top.
        when (_landing.value) {
            Landing.ACCOUNTS -> Unit
            Landing.ALL_TRANSACTIONS -> if (source is Source.File) _screens.value = listOf(Screen.AllTransactions)
            Landing.SAVED_REPORTS -> _screens.value = listOf(Screen.SavedViews)
            Landing.ADD -> if (source is Source.File) startDefaultAdd()
        }
    }

    companion object {
        private const val KEY_URI = "journalUri"
        private const val KEY_QUICK_ADD = "quickAdd"
        private const val KEY_QUICK_ADD_FILL = "quickAddFill"
        private const val KEY_QUICK_ADD_SOLE = "quickAddEnterTakesOnly"
        private const val KEY_INTELLIGENT = "intelligentMode"
        private const val KEY_LIGHTWEIGHT = "lightweightMode"
        private const val KEY_LIGHT_OFF = "lightweightPartsOff"
        private const val KEY_LANDING = "landingScreen"
        private const val UNDO_STEPS = 20
        private const val LIGHT_UNDO_STEPS = 5
        private const val KEY_TX_PAGE_SIZE = "transactionsPageSize"

        /** How many transactions All transactions loads at a time, to choose from. */
        val TX_PAGE_SIZES = listOf(25, 50, 100, 250, 500)
        private const val KEY_INTELLIGENT_FUZZY = "intelligentFuzzy"
        private const val KEY_INTELLIGENT_DUPLICATES = "intelligentDuplicates"
        private const val KEY_HEADER_COLLAPSED = "headerCollapsed"
        private const val KEY_PAD_DECIMALS = "padDecimals"
        private const val KEY_QUICK_ADD_DEFAULT = "quickAddDefault"
        private const val KEY_ALIGN_AMOUNTS = "alignAmounts"
        private const val KEY_AMOUNT_SPACES = "amountSpaces"
        private const val KEY_AMOUNT_COLUMN = "amountColumn"
        private const val KEY_HIDDEN_FIELDS = "hiddenFormFields"
        private const val KEY_DECLARED_PAYEES_ONLY = "declaredPayeesOnly"
        private const val KEY_PAYEES_VIEW = "payeesView"
        private const val KEY_PAYEES_SORT = "payeesSort"
        private const val KEY_SHOW_TIMINGS = "showTimings"
        private const val KEY_HIDDEN = "hiddenAccounts"
        private const val KEY_HIDING_PAUSED = "hidingPaused"
        private const val KEY_HIDING_ON = "hidingOn"
        private const val KEY_HIDE_ZERO_PINS = "hideZeroPins"
        private const val KEY_PINNED = "pinnedAccounts"
        private const val KEY_TREE_COLORS = "treeColors"
        private const val KEY_TREE_BARS = "treeBars"
        private const val KEY_HUES = "branchHues"
        private const val KEY_HIDDEN_BAR = "showHiddenBar"
        private const val KEY_KEEP_HIDDEN_IN_TOTALS = "keepHiddenInTotals"
        private const val KEY_TREE_CONTRAST = "treeContrast"
        private const val KEY_TREE_SHADING = "treeShading"
        private const val KEY_CUSTOM_HUES = "customHues"
        private const val KEY_HUE_START = "hueStart"
        private const val TAG = "Ledgedroid"
        private const val NO_JOURNAL = "Open your journal first, from the menu (⋮ › Open journal file…)."

    }
}
