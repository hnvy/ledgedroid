package app.ledgedroid

import app.ledgedroid.ui.Message
import app.ledgedroid.ui.AllTransactionsScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.ledgedroid.ui.AccountNameDialog
import app.ledgedroid.ui.EditPreviewDialog
import app.ledgedroid.ui.HealthScreen
import app.ledgedroid.ui.RecategoriseDialog
import app.ledgedroid.ui.SharePatternsScreen
import app.ledgedroid.core.QuickHints
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key

import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledgedroid.core.BranchColors
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.Styles
import app.ledgedroid.ui.AddScreen
import app.ledgedroid.ui.QuickAddScreen
import app.ledgedroid.ui.BalanceScreen
import app.ledgedroid.ui.LedgerTheme
import app.ledgedroid.ui.LedgerViewModel
import app.ledgedroid.ui.LoadState

import app.ledgedroid.ui.PayeeScreen
import app.ledgedroid.ui.PayeesScreen
import app.ledgedroid.ui.RegisterScreen
import app.ledgedroid.ui.RegisterTarget
import app.ledgedroid.ui.SavedViewsScreen
import app.ledgedroid.ui.Screen
import app.ledgedroid.ui.SearchScreen

import app.ledgedroid.ui.SettingsScreen
import app.ledgedroid.ui.TemplatesScreen
import app.ledgedroid.ui.TransactionScreen
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val vm: LedgerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            LedgerTheme { LedgerApp(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            // "Open with Ledgedroid" from a file manager or sync app.
            Intent.ACTION_VIEW -> intent.data?.let { vm.open(it) }
            // The widget, quick settings tile or launcher shortcut.
            AddEntry.ACTION_ADD -> vm.startDefaultAdd(intent.getStringExtra(AddEntry.EXTRA_TEMPLATE))
            // Text shared from another app (a bank SMS, a notification, a note): quick add, filled in.
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                    .filter { it.isNotBlank() }.distinct().joinToString("\n")
                if (text.isNotBlank()) vm.startShared(text)
            }
        }
    }
}

@Composable
fun LedgerApp(vm: LedgerViewModel) {
    val load by vm.load.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val screens by vm.screens.collectAsStateWithLifecycle()
    val register by vm.register.collectAsStateWithLifecycle()
    val savedViews by vm.savedViews.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val queryRevision by vm.queryRevision.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val hideZeroPins by vm.hideZeroPins.collectAsStateWithLifecycle()
    val quickAdd by vm.quickAdd.collectAsStateWithLifecycle()
    val quickAddDefault by vm.quickAddDefault.collectAsStateWithLifecycle()
    val quickAddFill by vm.quickAddFill.collectAsStateWithLifecycle()
    val quickAddEnterTakesOnly by vm.quickAddEnterTakesOnly.collectAsStateWithLifecycle()
    val intelligent by vm.intelligent.collectAsStateWithLifecycle()
    val intelligentFuzzy by vm.intelligentFuzzy.collectAsStateWithLifecycle()
    val intelligentDuplicates by vm.intelligentDuplicates.collectAsStateWithLifecycle()
    val headerCollapsed by vm.headerCollapsed.collectAsStateWithLifecycle()
    val lightweight by vm.lightweight.collectAsStateWithLifecycle()
    val lightOff by vm.lightOff.collectAsStateWithLifecycle()
    // Lightweight mode's parts in effect: the mode on, and that part not turned off.
    val lightIntelligent = lightweight && LedgerViewModel.LightPart.INTELLIGENT !in lightOff
    val lightForm = lightweight && LedgerViewModel.LightPart.FORM !in lightOff
    val stale by vm.stale.collectAsStateWithLifecycle()
    val landing by vm.landing.collectAsStateWithLifecycle()
    val undoUi by vm.undoUi.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val treeStyle by vm.treeStyle.collectAsStateWithLifecycle()
    val branchHues by vm.branchHues.collectAsStateWithLifecycle()
    val showHiddenBar by vm.showHiddenBar.collectAsStateWithLifecycle()
    val keepHiddenInTotals by vm.keepHiddenInTotals.collectAsStateWithLifecycle()


    // Journals have many extensions (.journal, .hledger, .ledger, .txt) and MIME types, so accept any file.
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val chooseBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setBackupFolder(uri)
    }
    val chooseIncludeFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setIncludeFolder(uri)
    }
    // Turning on reminders asks for notifications first (Android 13 and later).
    val context = androidx.compose.ui.platform.LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(Alerts.allowed(context)) }
    var pendingAlerts by remember { mutableStateOf<AlertSettings?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        pendingAlerts?.let { vm.setAlertSettings(it) }
        pendingAlerts = null
    }
    val setAlerts = { s: AlertSettings ->
        if (s.any && !Alerts.allowed(context) && android.os.Build.VERSION.SDK_INT >= 33) {
            pendingAlerts = s
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else vm.setAlertSettings(s)
    }
    val alertSettings by vm.alertSettings.collectAsStateWithLifecycle()
    val includeFolder by vm.includeFolder.collectAsStateWithLifecycle()
    val sharePatterns by vm.sharePatterns.collectAsStateWithLifecycle()
    val pendingEdit by vm.pendingEdit.collectAsStateWithLifecycle()
    val entrySuggestions by vm.suggestions.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<String?>(null) }
    var recategorising by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // Messages show over every screen, so Undo is offered wherever the change was made.
    val snackbar = remember { SnackbarHostState() }
    // Each message is an event: taking it (consumeMessage) mustn't cancel its own snackbar, as keying an
    // effect on the message did, which could take a snackbar, and its Undo, away straight after showing it.
    // Only a newer message replaces the one showing.
    LaunchedEffect(Unit) {
        vm.message.filterNotNull().collectLatest { m: Message ->
            vm.consumeMessage()
            val result = snackbar.showSnackbar(
                m.text,
                actionLabel = if (m.canUndo) "Undo" else if (m.canRedo) "Redo" else null,
                withDismissAction = true,
                duration = if (m.canUndo || m.canRedo) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) { if (m.canUndo) vm.undo() else if (m.canRedo) vm.redo() }
        }
    }

    val loaded = load as? LoadState.Loaded
    val journal = loaded?.journal
    val styles = journal?.styles ?: Styles.EMPTY
    // The period shown in the report, used by the payee screens.
    val span = report?.span ?: options.span(LocalDate.now())

    Box(Modifier.fillMaxSize()) {
        // Keeps the balance screen's scroll position while other screens are open.
        val stateHolder = rememberSaveableStateHolder()
        when (val screen = screens.lastOrNull()) {
            null -> stateHolder.SaveableStateProvider("balance") {
                BalanceScreen(
                    load = load,
                    report = report,
                    options = options,
                    collapsed = collapsed,
                    onOptions = vm::updateOptions,
                    onQuery = vm::setQuery,
                    onToggleCollapsed = vm::toggleCollapsed,
                    onOpenFile = { openDocument.launch(arrayOf("*/*")) },
                    onReload = vm::reload,
                    onOpenRegister = vm::openRegister,
                    savedViews = savedViews,
                    queryRevision = queryRevision,
                    onApplyView = vm::toggleView,
                    onSaveView = vm::saveView,
                    onAdd = { vm.startAdd() },
                    onRename = { renaming = it },
                    undoUi = undoUi,
                    stale = stale,
                    onUndo = vm::undo,
                    onRedo = vm::redo,
                    headerCollapsed = headerCollapsed,
                    onHeaderCollapsed = vm::setHeaderCollapsed,
                    onQuickAdd = { vm.startQuickAdd() },
                    quickAddButton = quickAdd,
                    quickAddDefault = quickAddDefault,
                    onOpen = vm::push,
                    hidden = hidden,
                    onHide = vm::hideAccount,
                    onUnhide = vm::unhide,
                    onUnhideAll = vm::unhideAll,
                    onHidingOn = vm::setHidingOn,
                    onHidingOnFor = vm::setAccountHidingOn,
                    hideZeroPins = hideZeroPins,
                    onHideZeroPins = vm::setHideZeroPins,
                    pinned = pinned,
                    onTogglePin = vm::togglePin,
                    treeStyle = treeStyle,
                    branchHues = branchHues,
                    onBranchHue = vm::setBranchHue,
                    showHiddenBar = showHiddenBar,
                    onShowHiddenBar = vm::setShowHiddenBar,
                    keepHiddenInTotals = keepHiddenInTotals,
                    onKeepHiddenInTotals = vm::setKeepHiddenInTotals,
                    onCollapseAll = vm::collapseAll,
                    onExpandAll = vm::expandAll,
                )
            }

            is Screen.Register -> {
                BackHandler(onBack = vm::back)
                RegisterScreen(
                    // While a new In / Out choice or filter is worked out, the list for the previous one stays up.
                    register?.takeIf {
                        it.target.copy(amountSign = screen.target.amountSign, filter = screen.target.filter) == screen.target
                    },
                    screen.target,
                    onBack = vm::back,
                    onOpenTransaction = { vm.push(Screen.TransactionDetail(it)) },
                    onSign = vm::setRegisterSign,
                    onFilter = vm::setRegisterFilter,
                )
            }

            Screen.AllTransactions -> {
                BackHandler(onBack = vm::back)
                val allTx by vm.allTx.collectAsStateWithLifecycle()
                // Again once the journal is read: opening straight on this screen (Settings › Open on), it isn't yet.
                LaunchedEffect(journal) { vm.ensureAllTx() }
                AllTransactionsScreen(
                    journal = journal,
                    ui = allTx,
                    onFilter = vm::setAllTxFilter,
                    onOrder = vm::setAllTxOrder,
                    onPageSize = vm::setAllTxPageSize,
                    onLoadMore = vm::loadMoreTx,
                    onRefresh = { keep -> vm.refreshAllTx(keep) },
                    onScroll = vm::saveAllTxScroll,
                    onOpenTransaction = { vm.push(Screen.TransactionDetail(it)) },
                    onBack = vm::back,
                )
            }

            Screen.Health -> {
                BackHandler(onBack = vm::back)
                val j = journal
                if (j != null) HealthScreen(j, onDeclare = vm::previewDeclare, onOpenTransaction = { vm.push(Screen.TransactionDetail(it)) }, onBack = vm::back)
            }

            Screen.SharePatterns -> {
                BackHandler(onBack = vm::back)
                SharePatternsScreen(sharePatterns, templates, onChange = vm::setSharePatterns, read = { t, p -> vm.readShared(t, p) }, onBack = vm::back)
            }

            is Screen.TransactionDetail -> {
                BackHandler(onBack = vm::back)
                val sourceLines = remember(journal) { vm.sourceLines() }
                TransactionScreen(
                    // The report's journal also has any --forecast transactions, which registers can open.
                    journal = report?.journal ?: journal,
                    index = screen.index,
                    sourceLines = sourceLines,
                    onBack = vm::back,
                    onRepeat = vm::startAddFrom,
                    onOpenPayee = { vm.push(Screen.Payee(it)) },
                    onOpenAccount = {
                        vm.openRegister(
                            RegisterTarget(
                                it,
                                includeSubaccounts = false,
                                section = null,
                                span = DateSpan.ALL
                            )
                        )
                    },
                    onEdit = vm::startEdit,
                    onDelete = vm::deleteTransaction,
                    onStatus = vm::setStatus,
                )
            }

            Screen.Search -> {
                BackHandler(onBack = vm::back)
                val text by vm.searchText.collectAsStateWithLifecycle()
                val results by vm.searchResults.collectAsStateWithLifecycle()
                SearchScreen(
                    journal = journal,
                    text = text,
                    results = results,
                    onText = vm::setSearchText,
                    onBack = vm::back,
                    onOpenTransaction = { vm.push(Screen.TransactionDetail(it)) },
                )
            }

            Screen.Payees -> {
                BackHandler(onBack = vm::back)
                val payeesView by vm.payeesView.collectAsStateWithLifecycle()
                val payeesPlace by vm.payeesPlace.collectAsStateWithLifecycle()
                PayeesScreen(
                    journal, span,
                    view = payeesView,
                    onView = vm::setPayeesView,
                    onBack = vm::back,
                    onOpenPayee = { vm.push(Screen.Payee(it)) },
                    place = payeesPlace,
                    onPlace = vm::setPayeesPlace,
                )
            }

            is Screen.Payee -> {
                BackHandler(onBack = vm::back)
                PayeeScreen(
                    journal, screen.name, span,
                    onBack = vm::back,
                    onOpenTransaction = { vm.push(Screen.TransactionDetail(it)) },
                    onRepeat = vm::startAddFrom,
                    onRecategorise = { recategorising = screen.name },
                )
            }

            Screen.Templates -> {
                BackHandler(onBack = vm::back)
                TemplatesScreen(
                    templates, styles,
                    onBack = vm::back,
                    onUse = { vm.startDefaultAdd(it.id) },
                    onEdit = vm::editTemplate,
                    onMove = vm::moveTemplate,
                    onDelete = vm::deleteTemplate,
                    onSchedule = vm::setTemplateSchedule,
                )
            }

            Screen.Settings -> {
                BackHandler(onBack = vm::back)
                val backupUi by vm.backupUi.collectAsStateWithLifecycle()
                val showTimings by vm.showTimingsFlow.collectAsStateWithLifecycle()
                SettingsScreen(
                    treeStyle = treeStyle,
                    onTreeStyle = vm::setTreeStyle,
                    topLevels = remember(journal) { journal?.let { BranchColors.topLevels(it) }.orEmpty() },
                    branchHues = branchHues,
                    onBranchHue = vm::setBranchHue,
                    onShuffleHues = vm::shuffleBranchHues,
                    showTimings = showTimings,
                    lastSaveTimings = vm.lastSaveTimings,
                    onShowTimings = vm::setShowTimings,
                    journal = journal,
                    onAccountType = vm::setAccountType,
                    quickAdd = quickAdd,
                    onQuickAdd = vm::setQuickAdd,
                    quickAddDefault = quickAddDefault,
                    onQuickAddDefault = vm::setQuickAddDefault,
                    quickAddFill = quickAddFill,
                    onQuickAddFill = vm::setQuickAddFill,
                    quickAddEnterTakesOnly = quickAddEnterTakesOnly,
                    onQuickAddEnterTakesOnly = vm::setQuickAddEnterTakesOnly,
                    lightweight = lightweight,
                    onLightweight = vm::setLightweight,
                    lightOff = lightOff,
                    onLightPart = vm::setLightPart,
                    landing = landing,
                    onLanding = vm::setLanding,
                    intelligent = intelligent,
                    onIntelligent = vm::setIntelligent,
                    intelligentFuzzy = intelligentFuzzy,
                    onIntelligentFuzzy = vm::setIntelligentFuzzy,
                    intelligentDuplicates = intelligentDuplicates,
                    onIntelligentDuplicates = vm::setIntelligentDuplicates,
                    onSharePatterns = { vm.push(Screen.SharePatterns) },
                    sharePatternCount = sharePatterns.size,
                    alertSettings = alertSettings,
                    notificationsAllowed = notificationsAllowed,
                    scheduledTemplates = templates.count { it.schedule != null },
                    onAlertSettings = setAlerts,
                    includeFolder = includeFolder,
                    onChooseIncludeFolder = { chooseIncludeFolder.launch(vm.journalUri()) },
                    onForgetIncludeFolder = { vm.setIncludeFolder(null) },
                    ui = backupUi,
                    onBack = vm::back,
                    onRefresh = vm::refreshBackups,
                    onSettings = vm::updateBackupSettings,
                    // Start in the journal's folder where the file's app allows it.
                    onChooseFolder = { chooseBackupFolder.launch(vm.journalUri()) },
                    onBackUpNow = vm::backUpNow,
                )
            }

            Screen.SavedViews -> {
                BackHandler(onBack = vm::back)
                SavedViewsScreen(
                    savedViews, options,
                    onBack = vm::back,
                    onApply = { vm.applyView(it); vm.back() },
                    onSaveCurrent = vm::saveView,
                    onUpdate = vm::updateView,
                    onRename = vm::renameView,
                    onDelete = vm::deleteView,
                )
            }

            is Screen.Add -> {
                val draft by vm.draft.collectAsStateWithLifecycle()
                val suggestions by vm.suggestions.collectAsStateWithLifecycle()
                val saveState by vm.saveState.collectAsStateWithLifecycle()
                val templateName by vm.templateName.collectAsStateWithLifecycle()
                val addedInSession by vm.addedInSession.collectAsStateWithLifecycle()
                val formRevision by vm.formRevision.collectAsStateWithLifecycle()
                val hiddenFields by vm.hiddenFields.collectAsStateWithLifecycle()
                val amountLayout by vm.amountLayout.collectAsStateWithLifecycle()
                val declaredPayeesOnly by vm.declaredPayeesOnly.collectAsStateWithLifecycle()
                // A new form (eg editing another transaction, or the next of several) starts with fresh form
                // state: no leftover error marks, scroll position or focus.
                key(screen, formRevision) {
                    AddScreen(
                        draft = draft,
                        styles = styles,
                        suggestions = suggestions,
                        templates = templates,
                        saveState = saveState,
                        journalName = loaded?.name ?: "",
                        templateMode = screen.editTemplateId != null,
                        templateName = templateName,
                        editing = screen.editing != null,
                        addedInSession = addedInSession,
                        hiddenFields = hiddenFields,
                        amountLayout = amountLayout,
                        declaredPayeesOnly = declaredPayeesOnly,
                        onHiddenFields = vm::setHiddenFields,
                        onAmountLayout = vm::setAmountLayout,
                        onDeclaredPayeesOnly = vm::setDeclaredPayeesOnly,
                        onChange = vm::updateDraft,
                        onTemplateName = vm::setTemplateName,
                        onApplyTemplate = vm::applyTemplate,
                        onSave = { another -> vm.saveTransaction(another) },
                        intelligent = intelligent,
                        similarTo = vm::similarTo,
                        fuzzy = intelligentFuzzy && !lightIntelligent,
                        lightweight = lightForm,
                        onSaveTemplate = vm::saveTemplate,
                        onClose = vm::back,
                    )
                }
            }

            is Screen.QuickAdd -> {
                val draft by vm.draft.collectAsStateWithLifecycle()
                val suggestions by vm.suggestions.collectAsStateWithLifecycle()
                val saveState by vm.saveState.collectAsStateWithLifecycle()
                val addedInSession by vm.addedInSession.collectAsStateWithLifecycle()
                val formRevision by vm.formRevision.collectAsStateWithLifecycle()
                val amountLayout by vm.amountLayout.collectAsStateWithLifecycle()
                val declaredPayeesOnly by vm.declaredPayeesOnly.collectAsStateWithLifecycle()
                // After adding one, the next starts afresh on the same date.
                key(screen, formRevision) {
                    QuickAddScreen(
                        styles = styles,
                        suggestions = suggestions,
                        templates = templates,
                        journalName = loaded?.name ?: "",
                        declaredPayeesOnly = declaredPayeesOnly,
                        amountLayout = amountLayout,
                        saveState = saveState,
                        addedInSession = addedInSession,
                        startDate = draft.date,
                        // Only the first: after Add, next the next one starts plain.
                        startTemplate = screen.templateId?.takeIf { addedInSession == 0 }
                            ?.let { id -> templates.firstOrNull { it.id == id } },
                        startHints = screen.shared?.takeIf { addedInSession == 0 }?.hints() ?: QuickHints(),
                        sharedText = screen.shared?.text?.takeIf { addedInSession == 0 },
                        fillDefaults = quickAddFill,
                        intelligent = intelligent,
                        journal = journal,
                        // Lightweight mode turns Intelligent mode's costly parts off.
                        fuzzy = intelligentFuzzy && !lightIntelligent,
                        checkDuplicates = intelligentDuplicates && !lightIntelligent,
                        padDecimals = amountLayout.padDecimals,
                        enterTakesOnly = quickAddEnterTakesOnly,
                        onSave = vm::saveQuick,
                        onToForm = vm::quickToForm,
                        onClose = vm::back,
                    )
                }
            }
        }

        // Changes across the journal, over whichever screen started them: asked for, then shown line by line.
        renaming?.let { account ->
            AccountNameDialog(
                title = "Rename $account",
                initial = account,
                detail = "Renames it and its subaccounts everywhere in the journal: transactions, budget and auto posting rules, " +
                        "and account directives. Pins, hiding and account types follow. You'll see every change first.",
                confirm = "Show changes",
                suggestions = entrySuggestions,
                onConfirm = { to -> renaming = null; vm.previewRename(account, to) },
                onDismiss = { renaming = null },
            )
        }
        recategorising?.let { payee ->
            val j = journal
            if (j != null) RecategoriseDialog(j, payee, entrySuggestions,
                onConfirm = { from, to -> recategorising = null; vm.previewRecategorise(payee, from, to) },
                onDismiss = { recategorising = null })
        }
        pendingEdit?.let { EditPreviewDialog(it, onApply = vm::applyEdit, onCancel = vm::cancelEdit) }

        // Above the screens' own bottom bars (the add form's save bar), so Undo is always reachable.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (screens.lastOrNull().let { it is Screen.Add || it is Screen.QuickAdd }) 140.dp else 0.dp),
        )
    }
}
