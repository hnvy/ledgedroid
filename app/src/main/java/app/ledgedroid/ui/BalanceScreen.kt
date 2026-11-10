package app.ledgedroid.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Add

import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.AccountListMode
import app.ledgedroid.core.Accumulation
import app.ledgedroid.core.BranchColors
import app.ledgedroid.core.BalanceRow
import app.ledgedroid.core.CompoundSection
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.JournalIssue
import app.ledgedroid.core.MixedAmount
import app.ledgedroid.core.PeriodPreset
import app.ledgedroid.core.ReportCommand
import app.ledgedroid.core.ReportInterval
import app.ledgedroid.core.ReportKind
import app.ledgedroid.core.UNBUDGETED
import app.ledgedroid.core.parentAccount
import app.ledgedroid.core.Styles
import app.ledgedroid.core.describeSpan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceScreen(
    load: LoadState,
    report: ReportUi?,
    options: Options,
    collapsed: Set<String>,
    onOptions: ((Options) -> Options) -> Unit,
    onQuery: (String) -> Unit,
    onToggleCollapsed: (String) -> Unit,
    onOpenFile: () -> Unit,
    onReload: () -> Unit,
    onOpenRegister: (RegisterTarget) -> Unit,
    savedViews: List<SavedView>,
    queryRevision: Int,
    onApplyView: (SavedView) -> Unit,
    onSaveView: (String) -> Unit,
    onAdd: () -> Unit,
    /** Rename an account across the journal (from its long-press menu). */
    onRename: (String) -> Unit,
    /** Lightweight mode: changes made in the app aren't shown yet (↻ reads the journal). */
    stale: Boolean = false,
    /** What Undo and Redo would do, for the menu. */
    undoUi: LedgerViewModel.UndoUi = LedgerViewModel.UndoUi(),
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    /** The chips above the report folded away, to see only the accounts. */
    headerCollapsed: Boolean = false,
    onHeaderCollapsed: (Boolean) -> Unit = {},
    onQuickAdd: () -> Unit,
    /** Show the smaller button above + (Settings › Quick add button). */
    quickAddButton: Boolean,
    /** + opens quick add, and the smaller button the full form (Settings › Quick add by default). */
    quickAddDefault: Boolean,
    onOpen: (Screen) -> Unit,
    hidden: HiddenAccounts,
    onHide: (String) -> Unit,
    onUnhide: (Set<String>) -> Unit,
    onUnhideAll: () -> Unit,
    onHidingOn: (Boolean) -> Unit,
    onHidingOnFor: (String, Boolean) -> Unit,
    hideZeroPins: Boolean,
    onHideZeroPins: (Boolean) -> Unit,
    pinned: List<String>,
    onTogglePin: (String) -> Unit,
    treeStyle: TreeStyle,
    branchHues: Map<String, Float>,
    onBranchHue: (String, Float) -> Unit,
    showHiddenBar: Boolean,
    onShowHiddenBar: (Boolean) -> Unit,
    keepHiddenInTotals: Boolean,
    onKeepHiddenInTotals: (Boolean) -> Unit,
    onCollapseAll: (Set<String>) -> Unit,
    onExpandAll: (Set<String>) -> Unit,
) {
    /** The top-level account whose colour is being chosen (from a long press). */
    var editingColor by remember { mutableStateOf<String?>(null) }
    // The accounts with subaccounts in this report, for Collapse all / Expand all (tree only).
    val parents = remember(report) { report?.let { parentKeys(it) }.orEmpty() }
    val allCollapsed = parents.isNotEmpty() && collapsed.containsAll(parents)
    val hiddenCount = hidden.listed.size
    /** The list of hidden accounts, to pick which to show again. */
    var choosingUnhide by remember { mutableStateOf(false) }
    // The filter in effect is options.query; what's typed in the box is only applied with Enter.
    val queryText = options.query
    var savingView by remember { mutableStateOf(false) }
    var showFilterHelp by remember { mutableStateOf(false) }
    /** The account the person long-pressed, to pin or hide it. */
    var actionsFor by remember { mutableStateOf<String?>(null) }
    // The options in effect, with any hledger options typed into the filter, for the controls.
    val resolved = remember(options) { options.resolve() }
    val effective = resolved.options
    val command = resolved.command

    // A saved view changed the query: show it in the search box.

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }


    val title = when (load) {
        is LoadState.Loaded -> load.name
        is LoadState.Failed -> load.name
        LoadState.Loading -> "Opening…"
        LoadState.NoJournal -> "Ledgedroid"
    }
    val noJournal = load is LoadState.NoJournal
    val subtitle = report?.let {
        val line = when {
            it.budget != null -> budgetSubtitle(it.options, it.span)
            it.multi != null || it.multiCompound != null -> multiPeriodSubtitle(it.options, it.span, it.accumulation)
            else -> periodLine(it.span, it.accumulation)
        }
        // There's no button for the balance sheet, so say which report it is.
        val title = it.compound?.title ?: it.multiCompound?.title
        if (title != null) "$title · $line" else line
    } ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalLedgerColors.current.mutedInk,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    // Filtering, searching and reloading need a journal.
                    if (!noJournal) {
                        // Fold the chips away to see only the accounts (warnings and an open filter stay).
                        IconButton(onClick = { onHeaderCollapsed(!headerCollapsed) }) {
                            Icon(
                                if (headerCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (headerCollapsed) "Show the report's options" else "Hide the report's options",
                            )
                        }
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(FilterListIcon, contentDescription = "Filter the report")
                        }
                        IconButton(onClick = { onOpen(Screen.Search) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search transactions")
                        }
                        IconButton(onClick = onReload) {
                            // Lightweight mode: tinted while changes are waiting to be shown.
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = if (stale) "Reload journal (changes waiting)" else "Reload journal",
                                tint = if (stale) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Undo and redo, step by step, naming the change (and how many more there are).
                            DropdownMenuItem(
                                text = {
                                    Text(undoUi.undo?.let { "Undo: $it" + if (undoUi.undoCount > 1) " (${undoUi.undoCount - 1} more)" else "" } ?: "Undo",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                enabled = undoUi.undo != null,
                                onClick = { menuOpen = false; onUndo() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(undoUi.redo?.let { "Redo: $it" + if (undoUi.redoCount > 1) " (${undoUi.redoCount - 1} more)" else "" } ?: "Redo",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                enabled = undoUi.redo != null,
                                onClick = { menuOpen = false; onRedo() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Open journal file…") },
                                onClick = { menuOpen = false; onOpenFile() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Payees") },
                                onClick = { menuOpen = false; onOpen(Screen.Payees) },
                            )
                            DropdownMenuItem(
                                text = { Text("Templates") },
                                onClick = { menuOpen = false; onOpen(Screen.Templates) },
                            )
                            DropdownMenuItem(
                                text = { Text("Saved reports") },
                                onClick = { menuOpen = false; onOpen(Screen.SavedViews) },
                            )
                            DropdownMenuItem(
                                text = { Text("All transactions") },
                                onClick = { menuOpen = false; onOpen(Screen.AllTransactions) },
                            )
                            DropdownMenuItem(
                                text = { Text("Journal health") },
                                onClick = { menuOpen = false; onOpen(Screen.Health) },
                            )
                            DropdownMenuItem(
                                text = { Text("Save this report…") },
                                onClick = { menuOpen = false; savingView = true },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Hidden accounts…") },
                                enabled = hiddenCount > 0,
                                onClick = { menuOpen = false; choosingUnhide = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (hiddenCount) {
                                            0 -> "Unhide all accounts"
                                            1 -> "Unhide all accounts (1 hidden)"
                                            else -> "Unhide all accounts ($hiddenCount hidden)"
                                        }
                                    )
                                },
                                enabled = hiddenCount > 0,
                                onClick = { menuOpen = false; onUnhideAll() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { menuOpen = false; onOpen(Screen.Settings) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (load is LoadState.Loaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The other way of adding (Settings), smaller and lighter, above +: quick add, or the full
                    // form when quick add is the default.
                    if (quickAddButton) {
                        SmallFloatingActionButton(
                            onClick = if (quickAddDefault) onAdd else onQuickAdd,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            if (quickAddDefault) Icon(Icons.Default.Edit, contentDescription = "Add with the full form")
                            else Icon(PromptIcon, contentDescription = "Quick add")
                        }
                    }
                    FloatingActionButton(onClick = if (quickAddDefault) onQuickAdd else onAdd) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (quickAddDefault) "Add transaction (quick add)" else "Add transaction"
                        )
                    }
                }
            }
        },

    ) { padding ->
        if (noJournal) {
            WelcomePanel(Modifier.padding(padding), onOpenFile)
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!headerCollapsed && savedViews.isNotEmpty()) SavedViewChips(savedViews, options, onApplyView) { onOpen(Screen.SavedViews) }
            if (!headerCollapsed) ControlChips(
                effective, command, onOptions, onShowOptions = { showOptions = true },
                collapse = if (parents.isEmpty()) null else allCollapsed,
                onCollapse = { if (allCollapsed) onExpandAll(parents) else onCollapseAll(parents) },
            )

            if (searchOpen || queryText.isNotEmpty()) {
                SearchField(
                    text = queryText,
                    error = report?.queryError,
                    applied = command.describe(),
                    onChange = onQuery,
                    onClose = { searchOpen = false },
                    onHelp = { showFilterHelp = true },
                    focusOnShow = searchOpen,
                )
            }
            if (!headerCollapsed && hiddenCount > 0 && showHiddenBar) HiddenBar(hidden, onChoose = { choosingUnhide = true }, onHidingOn = onHidingOn)

            when (load) {
                LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                is LoadState.Failed -> ErrorPanel(load, onReload, onOpenFile)
                LoadState.NoJournal -> Unit // shown instead of all of this, above
                is LoadState.Loaded -> {
                    val warnings = load.journal.warnings
                    if (warnings.isNotEmpty()) WarningBar(warnings) { showWarnings = true }
                    // Lightweight mode: a change made here isn't shown until ↻.
                    if (stale) StaleBar(onReload)
                    // The balance sheet and income statement leave out accounts without a type; say so.
                    val untyped = remember(load.journal) {
                        BranchColors.topLevels(load.journal).filter { load.journal.accountType(it) == null }
                    }
                    if (untyped.isNotEmpty() && (report?.compound != null || report?.multiCompound != null)) {
                        UntypedBar(untyped) { onOpen(Screen.Settings) }
                    }
                    if (report == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        val journal = report.journal
                        val look = remember(treeStyle, pinned, branchHues) {
                            TreeLook(treeStyle, pinned.toSet(), branchHues)
                        }
                        CompositionLocalProvider(
                            LocalTreeLook provides look,
                            LocalPinnedControls provides PinnedControls(hideZeroPins, onHideZeroPins),
                        ) {
                            ReportList(
                                report, report.options, collapsed, onToggleCollapsed, onOpenRegister,
                                onShowAllDates = { onOptions { it.copy(preset = PeriodPreset.ALL) } },
                                onClearFilter = { onQuery("") },
                                onLongPress = { actionsFor = it },
                            )
                        }
                    }
                    if (showWarnings) WarningsDialog(warnings) { showWarnings = false }
                }
            }
        }
    }

    if (showOptions) {
        val autoRules = (load as? LoadState.Loaded)?.journal?.autoRules?.size ?: 0
        OptionsSheet(effective, command, autoRules, onOptions) { showOptions = false }
    }
    if (showFilterHelp) FilterHelpDialog { showFilterHelp = false }
    // Closes by itself once nothing is hidden, eg after unhiding the last ones.
    if (choosingUnhide && hidden.listed.isNotEmpty()) {
        HiddenAccountsDialog(
            hidden = hidden,
            showBar = showHiddenBar,
            onShowBar = onShowHiddenBar,
            keepInTotals = keepHiddenInTotals,
            onKeepInTotals = onKeepHiddenInTotals,
            onHidingOn = onHidingOn,
            onHidingOnFor = onHidingOnFor,
            onUnhide = onUnhide,
            onUnhideAll = { choosingUnhide = false; onUnhideAll() },
            onDismiss = { choosingUnhide = false },
        )
    }
    actionsFor?.let { account ->
        AccountActionsSheet(
            account = account,
            pinned = account.removePrefix("$UNBUDGETED:") in pinned,
            onPin = { actionsFor = null; onTogglePin(account) },
            onHide = { actionsFor = null; onHide(account) },
            onDismiss = { actionsFor = null },
            hideKeepsTotals = keepHiddenInTotals,
            branchColor = if (!treeStyle.colors) null else {
                val top = BranchColors.topLevel(account)
                branchHues[top]?.let { hueColor(it, LocalLedgerColors.current.dark, treeStyle.contrast) }
            },
            onColor = { editingColor = BranchColors.topLevel(account); actionsFor = null },
            onRename = if (account == UNBUDGETED || account.startsWith("$UNBUDGETED:")) null else ({ actionsFor = null; onRename(account) }),
        )
    }
    editingColor?.let { name ->
        HuePickerDialog(
            name = name,
            initial = branchHues[name] ?: BranchColors.pickHue(branchHues.values),
            others = branchHues.filterKeys { it != name }.values,
            contrast = treeStyle.contrast,
            shading = treeStyle.shading,
            onDismiss = { editingColor = null },
            onPick = { h -> editingColor = null; onBranchHue(name, h) },
        )
    }
    if (savingView) {
        NameDialog("Save this report", "", "Save", onDismiss = { savingView = false }) {
            savingView = false
            onSaveView(it)
        }
    }
}

/**
 * One-tap saved reports, above the report picker. The current one is highlighted; tapping it again goes
 * back to the report shown before it.
 */
@Composable
private fun SavedViewChips(
    views: List<SavedView>,
    options: Options,
    onApply: (SavedView) -> Unit,
    onManage: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            tint = LocalLedgerColors.current.mutedInk,
            modifier = Modifier.size(18.dp)
        )
        views.forEach { v ->
            // Tapping the one showing goes back to the report from before it.
            val selected = v.options == options
            FilterChip(
                selected = selected,
                onClick = { onApply(v) },
                label = { Text(v.name, maxLines = 1) },
                trailingIcon = if (selected) {
                    { Icon(Icons.Default.Close, contentDescription = "Back to the report before", Modifier.size(16.dp)) }
                } else null,
            )
        }
        TextButton(onClick = onManage) { Text("Edit") }
    }
}

// ---------------------------------------------------------------------- controls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlChips(
    options: Options,
    command: ReportCommand,
    onOptions: ((Options) -> Options) -> Unit,
    onShowOptions: () -> Unit,
    /** Null when there's nothing to collapse; else whether everything is collapsed now. */
    collapse: Boolean?,
    onCollapse: () -> Unit,
) {
    var periodMenu by remember { mutableStateOf(false) }
    var depthMenu by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            val label = if (options.preset == PeriodPreset.CUSTOM) describeSpan(
                DateSpan(
                    options.customStart,
                    options.customEnd
                )
            )
            else options.preset.label
            FilterChip(
                selected = options.preset != PeriodPreset.ALL,
                onClick = { periodMenu = true },
                enabled = command.span == null, // set by -b, -e or -p in the filter
                label = { Text(label) },
                leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                PeriodPreset.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(if (p == PeriodPreset.CUSTOM) "Custom dates…" else p.label) },
                        leadingIcon = { if (p == options.preset) Icon(Icons.Default.Check, null) },
                        onClick = {
                            periodMenu = false
                            if (p == PeriodPreset.CUSTOM) customDialog = true
                            else onOptions { it.copy(preset = p) }
                        },
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = options.depth != null,
                onClick = { depthMenu = true },
                enabled = command.depth == null,
                label = { Text(levelsLabel(options.depth)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = depthMenu, onDismissRequest = { depthMenu = false }) {
                listOf(null, 1, 2, 3, 4).forEach { d ->
                    DropdownMenuItem(
                        text = { Text(levelsLabel(d)) },
                        leadingIcon = { if (d == options.depth) Icon(Icons.Default.Check, null) },
                        onClick = { depthMenu = false; onOptions { it.copy(depth = d) } },
                    )
                }
            }
        }
        if (options.kind != ReportKind.BUDGET) {
            FilterChip(
                selected = options.multiPeriod,
                onClick = { onOptions { it.copy(multiPeriod = !it.multiPeriod) } },
                enabled = command.interval == null, // -M etc. in the filter turn columns on
                label = { Text(if (options.multiPeriod) "By ${options.interval.unit}" else "By period") },
            )
        }
        if (options.kind == ReportKind.BUDGET || options.multiPeriod) {
            var intervalMenu by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = true,
                    onClick = { intervalMenu = true },
                    enabled = command.interval == null,
                    label = { Text(options.interval.label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            null,
                            Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                )
                DropdownMenu(expanded = intervalMenu, onDismissRequest = { intervalMenu = false }) {
                    ReportInterval.entries.forEach { iv ->
                        DropdownMenuItem(
                            text = { Text(iv.label) },
                            leadingIcon = { if (iv == options.interval) Icon(Icons.Default.Check, null) },
                            onClick = { intervalMenu = false; onOptions { it.copy(interval = iv) } },
                        )
                    }
                }
            }
            // One period at a time (swipe or use the arrows), or every period side by side.
            FilterChip(
                selected = options.periodTable,
                onClick = { onOptions { it.copy(periodTable = !it.periodTable) } },
                label = { Text("All periods") },
            )
        }
        val changed = listOf(
            options.mode == AccountListMode.FLAT, options.showEmpty,
            options.sortByAmount && options.kind != ReportKind.BUDGET,
            options.invert, options.atCost, options.auto,
            options.endBalance && (options.kind == ReportKind.BALANCE || options.kind == ReportKind.BUDGET),
        ).count { it }
        if (collapse != null && options.mode == AccountListMode.TREE) {
            FilterChip(
                selected = false,
                onClick = onCollapse,
                label = { Text(if (collapse) "Expand all" else "Collapse all") },
                // The chevron shows the state, like each account's: pointing down while anything is open,
                // right when everything is collapsed. The label says what tapping does.
                leadingIcon = {
                    Icon(
                        if (collapse) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (collapse) "All collapsed" else "Expanded",
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
        FilterChip(
            selected = changed > 0,
            onClick = onShowOptions,
            label = { Text(if (changed > 0) "Options ($changed)" else "Options") },
            leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }

    if (customDialog) {
        CustomRangeDialog(
            initialStart = options.customStart,
            initialEndExclusive = options.customEnd,
            onDismiss = { customDialog = false },
            onApply = { s, e ->
                customDialog = false
                onOptions { it.copy(preset = PeriodPreset.CUSTOM, customStart = s, customEnd = e) }
            },
        )
    }
}

private fun levelsLabel(depth: Int?) = when (depth) {
    null -> "All levels"
    1 -> "1 level"
    else -> "$depth levels"
}

/**
 * The report's filter. Typing changes nothing until Enter (or ✓); ✕ clears it and closes the box. Under it:
 * the problem with the filter, or what its options do ("Options: budget, monthly").
 */
@Composable
private fun SearchField(
    text: String,
    error: String?,
    applied: List<String>,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
    onHelp: () -> Unit,
    focusOnShow: Boolean
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusOnShow) { if (focusOnShow) focus.requestFocus() }
    FilterField(
        applied = text,
        onApply = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
        placeholder = "Filter: food, desc:rent, amt:>50, or options: --budget -M -H",
        message = error ?: applied.takeIf { it.isNotEmpty() }?.let { "Options: " + it.joinToString(", ") },
        isError = error != null,
        extraTrailing = { IconButton(onClick = onHelp) { Icon(Icons.Default.Info, contentDescription = "What can I type here?") } },
        onClear = onClose,
    )
}

/** What can be typed into the filter: query terms and hledger options. */
@Composable
private fun FilterHelpDialog(onDismiss: () -> Unit) {
    val terms = listOf(
        "food" to "accounts whose name contains food (acct:food)",
        "desc:  payee:  cur:  tag:" to "description, payee, currency, tag (tag:trip=lisbon)",
        "date:2026-10  amt:>50" to "dates (date:2026q3, date:2026-01..2026-04) and amounts",
        "status:*  depth:2  not:" to "cleared, levels, and not: before any term",
    )
    val opts = listOf(
        "--budget[=TEXT]" to "budget report (only rules whose description contains TEXT)",
        "--balancesheet, bs" to "balance sheet; is or --incomestatement for income",
        "-W -M -Q -Y" to "a column per week, month, quarter or year",
        "-H" to "balances at the end of each period",
        "--auto" to "apply auto posting rules (=)",
        "--forecast[=PERIOD]" to "add transactions from periodic rules (~), up to the report's end or 6 months ahead",
        "-p PERIOD, -b DATE, -e DATE" to "dates, eg -p 2026, -p \"monthly in 2026\", -b 2026-01",
        "--depth N, -N" to "at most N account levels",
        "--drop N" to "leave out the first N parts of account names (list mode)",
        "--tree, --flat" to "tree or list",
        "-U -P -C" to "only unmarked, pending or cleared",
        "-E  -S  -B  --invert" to "zero balances, sort by amount, at cost, flip signs",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Filter and options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Type query terms and hledger options together, eg --auto --budget expenses -M -H. " +
                            "Options here override the chips above the report and Options, which are greyed out while they do.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HelpSection("Query", terms)
                HelpSection("Options", opts)
                Text(
                    "Long-press an account to pin it to the top or hide it. ⋮ › Hidden accounts… shows " +
                            "chosen ones again. Colours and amount bars are in Options › Appearance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLedgerColors.current.mutedInk,
                )
            }
        },
    )
}

@Composable
private fun HelpSection(title: String, items: List<Pair<String, String>>) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    items.forEach { (code, what) ->
        Column {
            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(what, style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
        }
    }
}

/**
 * Shown while accounts are on the hidden list, so the totals aren't a surprise: how many are hidden, with
 * Choose… for the list and Show for now, which switches hiding off without taking them off the list. While
 * it's off, it says they're showing, with Hide again.
 */
@Composable
private fun HiddenBar(hidden: HiddenAccounts, onChoose: () -> Unit, onHidingOn: (Boolean) -> Unit) {
    val lc = LocalLedgerColors.current
    val active = hidden.active.size
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onChoose).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                !hidden.on -> if (hidden.listed.size == 1) "1 hidden account showing" else "${hidden.listed.size} hidden accounts showing"
                active == 1 -> "1 account hidden"
                else -> "$active accounts hidden"
            },
            style = MaterialTheme.typography.labelLarge,
            color = lc.mutedInk,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChoose) { Text("Choose…") }
        if (hidden.on) TextButton(onClick = { onHidingOn(false) }) { Text("Show for now") }
        else TextButton(onClick = { onHidingOn(true) }) { Text("Hide again") }
    }
}

/**
 * The hidden accounts. Each has a switch to hide it or show it for now, staying on the list, and × to take
 * it off the list; a switch at the top does the same for all of them at once. An account inside another
 * hidden account stays out of the reports while that one is hidden, so it says so.
 */
@Composable
private fun HiddenAccountsDialog(
    hidden: HiddenAccounts,
    showBar: Boolean,
    onShowBar: (Boolean) -> Unit,
    keepInTotals: Boolean,
    onKeepInTotals: (Boolean) -> Unit,
    onHidingOn: (Boolean) -> Unit,
    onHidingOnFor: (String, Boolean) -> Unit,
    onUnhide: (Set<String>) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts = remember(hidden.listed) { hidden.listed.sortedWith(String.CASE_INSENSITIVE_ORDER) }
    val lc = LocalLedgerColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden accounts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogSwitch(
                    "Hide these accounts",
                    if (hidden.on) "Switch off to show them for now. They stay on this list, to hide again."
                    else "Off: they're showing for now. Switch on to hide them again.",
                    hidden.on,
                    onHidingOn,
                )
                HorizontalDivider(color = lc.hairline)
                accounts.forEach { a ->
                    val on = hidden.on && a !in hidden.paused
                    // The nearest other account hidden now that this one is inside, which still hides it.
                    val hiddenParent = generateSequence(parentAccount(a)) { parentAccount(it) }.firstOrNull { it in hidden.active }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                a,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (on) MaterialTheme.colorScheme.onSurface else lc.mutedInk,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    !on && hiddenParent != null -> "Showing for now, but stays out while $hiddenParent is hidden"
                                    !on -> "Showing for now"
                                    else -> "Hidden"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = lc.mutedInk,
                            )
                        }
                        Switch(
                            checked = a !in hidden.paused,
                            onCheckedChange = { onHidingOnFor(a, it) },
                            enabled = hidden.on,
                        )
                        IconButton(onClick = { onUnhide(setOf(a)) }) {
                            Icon(Icons.Default.Close, contentDescription = "Take $a off the list")
                        }
                    }
                }
                HorizontalDivider(color = lc.hairline, modifier = Modifier.padding(top = 8.dp))
                // What hiding does to the reports.
                DialogSwitch(
                    "Keep hidden accounts in totals",
                    if (keepInTotals) "Only their rows are hidden. Parent accounts, totals and transactions still include them."
                    else "Off: their amounts are left out of every report and total, as if they weren't in the journal.",
                    keepInTotals,
                    onKeepInTotals,
                )
                // Whether to keep the reminder above the report; this list stays in ⋮ › Hidden accounts….
                DialogSwitch(
                    "Show the reminder bar",
                    "\"${hidden.active.size} hidden\" above the report, with Choose… and Show for now",
                    showBar,
                    onShowBar,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onUnhideAll) { Text("Unhide all") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(
    options: Options,
    command: ReportCommand,
    autoRules: Int,
    onOptions: ((Options) -> Options) -> Unit,
    onDismiss: () -> Unit
) {
    val budget = options.kind == ReportKind.BUDGET
    /** The detail for an option set by the filter, which the switch can't change. */
    fun fromFilter(flag: String) = "Set by $flag in the filter"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "Report options",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                listOf(AccountListMode.TREE to "Tree", AccountListMode.FLAT to "List").forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = options.mode == m,
                        onClick = { onOptions { it.copy(mode = m) } },
                        enabled = command.mode == null,
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    ) { Text(label) }
                }
            }
            SwitchRow(
                "Apply auto postings",
                when {
                    command.auto -> fromFilter("--auto")
                    autoRules == 0 -> "This journal has no auto posting rules (lines starting with =)"
                    autoRules == 1 -> "Use this journal's 1 auto posting rule, like --auto"
                    else -> "Use this journal's $autoRules auto posting rules, like --auto"
                },
                checked = options.auto,
                enabled = !command.auto && (autoRules > 0 || options.auto),
            ) { v -> onOptions { it.copy(auto = v) } }
            SwitchRow(
                if (budget) "Running balances" else "Balance at end of period",
                when {
                    command.historical && (options.kind == ReportKind.BALANCE || budget) -> fromFilter("-H")
                    options.kind == ReportKind.BALANCE -> "Include everything before the period too, like hledger -H"
                    budget -> "Each period includes everything before it, for actual amounts and goals, like -H"
                    options.kind == ReportKind.BALANCE_SHEET -> "Always on for the balance sheet"
                    else -> "The income statement always shows change in the period"
                },
                checked = options.accumulation == Accumulation.END_BALANCE,
                enabled = (options.kind == ReportKind.BALANCE || budget) && !command.historical,
            ) { v -> onOptions { it.copy(endBalance = v) } }
            SwitchRow(
                if (budget) "Show unbudgeted accounts" else "Show zero balances",
                when {
                    command.empty -> fromFilter("-E")
                    budget -> "List accounts without goals by name under <unbudgeted>, like -E"
                    else -> "Include accounts whose balance is zero, like -E"
                },
                options.showEmpty,
                enabled = !command.empty,
            ) { v -> onOptions { it.copy(showEmpty = v) } }
            SwitchRow(
                "Sort by amount",
                when {
                    budget -> "Not used by the budget report"
                    command.sortByAmount -> fromFilter("-S")
                    else -> "Largest first, instead of by account name, like -S"
                },
                options.sortByAmount && !budget,
                enabled = !budget && !command.sortByAmount,
            ) { v -> onOptions { it.copy(sortByAmount = v) } }
            SwitchRow(
                "Convert to cost",
                if (command.cost) fromFilter("-B") else "Show amounts with a recorded @ cost in that cost, like -B",
                options.atCost,
                enabled = !command.cost,
            ) { v ->
                onOptions { it.copy(atCost = v) }
            }
            SwitchRow(
                "Flip signs",
                if (command.invert) fromFilter("--invert") else "Negate every amount, like --invert",
                options.invert,
                enabled = !command.invert,
            ) { v ->
                onOptions { it.copy(invert = v) }
            }
            Text(
                "The budget report, balance sheet, forecasts and more are typed into the filter, " +
                        "eg --budget -M or --balancesheet. Tap ⓘ in the filter for the list.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalLedgerColors.current.mutedInk,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = LocalLedgerColors.current.mutedInk.copy(alpha = alpha)
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    initialStart: LocalDate?,
    initialEndExclusive: LocalDate?,
    onDismiss: () -> Unit,
    onApply: (LocalDate, LocalDate) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.toUtcMillis(),
        initialSelectedEndDateMillis = initialEndExclusive?.minusDays(1)?.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis!!.utcMillisToDate()
                    val last = state.selectedEndDateMillis?.utcMillisToDate() ?: start
                    onApply(start, last.plusDays(1))
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = {
                Text("Choose dates", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp))
            },
        )
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.utcMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

// ---------------------------------------------------------------------- report

data class VisibleRow<R>(val row: R, val hasChildren: Boolean, val collapsed: Boolean, val key: String)

/** Rows to show, skipping the descendants of collapsed tree rows. Keys are [prefix] + account name. */
fun <R> visibleRowsOf(
    rows: List<R>,
    tree: Boolean,
    collapsed: Set<String>,
    prefix: String,
    indent: (R) -> Int,
    name: (R) -> String,
): List<VisibleRow<R>> {
    val out = ArrayList<VisibleRow<R>>(rows.size)
    var hideDeeperThan: Int? = null
    for ((i, r) in rows.withIndex()) {
        val hide = hideDeeperThan
        if (hide != null) {
            if (indent(r) > hide) continue
            hideDeeperThan = null
        }
        val key = prefix + name(r)
        val hasChildren = tree && i + 1 < rows.size && indent(rows[i + 1]) > indent(r)
        val isCollapsed = hasChildren && key in collapsed
        out.add(VisibleRow(r, hasChildren, isCollapsed, key))
        if (isCollapsed) hideDeeperThan = indent(r)
    }
    return out
}

private fun visibleRows(rows: List<BalanceRow>, tree: Boolean, collapsed: Set<String>, prefix: String) =
    visibleRowsOf(rows, tree, collapsed, prefix, { it.indent }, { it.fullName })

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportList(
    report: ReportUi,
    options: Options,
    collapsed: Set<String>,
    onToggleCollapsed: (String) -> Unit,
    onOpenRegister: (RegisterTarget) -> Unit,
    onShowAllDates: () -> Unit,
    onClearFilter: () -> Unit,
    onLongPress: (String) -> Unit,
) {
    val styles = report.journal.styles
    val tree = options.mode == AccountListMode.TREE
    val listState = rememberLazyListState()
    // "Show all dates" can't widen dates typed into the filter (-b, -e, -p).
    val canWidenPeriod = options.preset != PeriodPreset.ALL && report.command.span == null

    fun target(row: BalanceRow, section: CompoundSection?) =
        RegisterTarget(row.fullName, includeSubaccounts = tree || row.atDepthLimit, section = section)

    val budget = report.budget
    if (budget != null) {
        BudgetContent(
            budget,
            report.journal,
            options,
            report.command.budgetPattern,
            collapsed,
            onToggleCollapsed,
            onOpenRegister,
            canWidenPeriod,
            onShowAllDates,
            onClearFilter,
            onLongPress,
            report.pinned,
            report.pinnedAtZero,
        )
        return
    }
    if (report.multi != null || report.multiCompound != null) {
        MultiPeriodContent(
            report, options, collapsed, onToggleCollapsed, onOpenRegister, canWidenPeriod, onShowAllDates,
            onClearFilter, onLongPress,
        )
        return
    }

    val single = report.single
    val compound = report.compound
    val isEmpty =
        (single != null && single.rows.isEmpty()) || (compound != null && compound.sections.all { it.report.rows.isEmpty() })
    // Amount bars: each section's rows are compared among themselves.
    val singleShares = rememberShares(single?.rows.orEmpty(), tree, "")
    val sectionShares = compound?.sections.orEmpty().map { rememberShares(it.report.rows, tree, "${it.title}/") }
    val shares = singleShares + sectionShares.fold(emptyMap<String, Float>()) { a, b -> a + b }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (isEmpty) {
            item(key = "empty") {
                EmptyState(
                    hasTransactions = report.journal.transactions.isNotEmpty(),
                    canWidenPeriod = canWidenPeriod,
                    hasFilter = options.query.isNotEmpty(),
                    onShowAllDates = onShowAllDates,
                    onClearFilter = onClearFilter,
                )
            }
        } else if (single != null) {
            pinnedSection(report.pinned, report.pinnedAtZero, styles, "", { it.amount }, onOpen = { p ->
                onOpenRegister(RegisterTarget(p.account, includeSubaccounts = true, section = p.section))
            }, onLongPress = onLongPress)
            items(visibleRows(single.rows, tree, collapsed, ""), key = { it.key }) { v ->
                AccountRow(v, styles, tree, { onToggleCollapsed(v.key) }, { onLongPress(v.row.fullName) }, shares[v.key]) {
                    onOpenRegister(target(v.row, null))
                }
            }
            item(key = "total") { TotalRow("Total", single.total, styles, doubleRule = true) }
        } else if (compound != null) {
            pinnedSection(report.pinned, report.pinnedAtZero, styles, "", { it.amount }, onOpen = { p ->
                onOpenRegister(RegisterTarget(p.account, includeSubaccounts = true, section = p.section))
            }, onLongPress = onLongPress)
            for (section in compound.sections) {
                stickyHeader(key = "h:${section.title}") { SectionHeader(section.title) }
                if (section.report.rows.isEmpty()) {
                    item(key = "e:${section.title}") {
                        Text(
                            "No ${section.title.lowercase()} for this period.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalLedgerColors.current.mutedInk,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                items(visibleRows(section.report.rows, tree, collapsed, "${section.title}/"), key = { it.key }) { v ->
                    AccountRow(v, styles, tree, { onToggleCollapsed(v.key) }, { onLongPress(v.row.fullName) }, shares[v.key]) {
                        onOpenRegister(target(v.row, section))
                    }
                }
                item(key = "t:${section.title}") {
                    TotalRow("Total ${section.title.lowercase()}", section.report.total, styles, doubleRule = false)
                }
            }
            item(key = "net") { TotalRow(compound.netLabel, compound.net, styles, doubleRule = true) }
        }
    }
}

/** One account: tap to see its transactions, long-press to hide it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AccountRow(
    v: VisibleRow<BalanceRow>,
    styles: Styles,
    tree: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    /** This account's share of the one above it, for the amount bar (Options › Appearance). */
    share: Float? = null,
    onClick: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    val row = v.row
    val step = 16.dp
    val topLevel = tree && row.indent == 0
    val pinned = LocalTreeLook.current.isPinned(row.fullName)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClickLabel = "Pin or hide", onLongClick = onLongClick, onClick = onClick)
            // Branch colour, pin highlight, indent guides (from each ancestor's chevron down through its
            // children), amount bar and the ledger ruling between rows.
            .treeRow(
                indent = row.indent, step = step, guideStart = 24.dp, branch = branchColor(row.fullName),
                pinned = pinned, share = share, barStart = 8.dp + step * row.indent + (if (tree) 32.dp else 8.dp),
                lc = lc,
            )
            .heightIn(min = 48.dp)
            .padding(start = 8.dp + step * row.indent, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tree) {
            Box(
                Modifier.size(32.dp).then(if (v.hasChildren) Modifier.clickable(onClick = onToggle) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (v.hasChildren) {
                    Icon(
                        if (v.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = (if (v.collapsed) "Expand " else "Collapse ") + row.displayName,
                        tint = lc.mutedInk,
                    )
                }
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        if (pinned) PinMark()
        Text(
            row.displayName,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (topLevel) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        AmountLines(row.amount, styles, strong = topLevel)
    }
}

@Composable
fun AmountLines(amount: MixedAmount, styles: Styles, strong: Boolean = false, modifier: Modifier = Modifier) {
    val lc = LocalLedgerColors.current
    val color = if (styles.looksNegative(amount)) lc.redInk else lc.ink
    Column(modifier, horizontalAlignment = Alignment.End) {
        styles.formatLines(amount).forEach {
            Text(it, style = if (strong) NumberStyleStrong else NumberStyle, color = color, maxLines = 1)
        }
    }
}

/** A total under a single blue rule; the final total also gets the accountant's double underline. */
@Composable
fun TotalRow(label: String, amount: MixedAmount, styles: Styles, doubleRule: Boolean) {
    val lc = LocalLedgerColors.current
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (doubleRule) 24.dp else 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(top = 10.dp, start = 8.dp, end = 12.dp),
        )
        AmountLines(
            amount, styles, strong = true,
            modifier = Modifier
                .widthIn(min = 88.dp)
                .drawBehind {
                    val w = 1.5.dp.toPx()
                    drawLine(lc.rule, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                    if (doubleRule) {
                        val y1 = size.height - 5.dp.toPx()
                        val y2 = size.height - 1.dp.toPx()
                        drawLine(lc.rule, Offset(0f, y1), Offset(size.width, y1), w)
                        drawLine(lc.rule, Offset(0f, y2), Offset(size.width, y2), w)
                    }
                }
                .padding(top = 9.dp, bottom = if (doubleRule) 10.dp else 2.dp),
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    val lc = LocalLedgerColors.current
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawLine(lc.rule, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun EmptyState(
    hasTransactions: Boolean,
    canWidenPeriod: Boolean,
    hasFilter: Boolean,
    onShowAllDates: () -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (hasTransactions) "No balances to show" else "This journal has no transactions yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasTransactions) "Nothing matches this period and filter." else "Add some in your editor, then tap Reload.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalLedgerColors.current.mutedInk,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canWidenPeriod) OutlinedButton(onClick = onShowAllDates) { Text("Show all dates") }
            if (hasFilter) OutlinedButton(onClick = onClearFilter) { Text("Clear filter") }
        }
    }
}

/** Shown until a journal is opened: what the app does, and a button to open one. */
@Composable
private fun WelcomePanel(modifier: Modifier, onOpenFile: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Open your journal", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ledgedroid shows balance reports, budgets and registers from an hledger journal file, and adds " +
                    "transactions to it.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Choose the journal file (eg main.journal or 2026.ledger). It's read again each time you come back " +
                    "to the app, so changes synced from your computer show up.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalLedgerColors.current.mutedInk,
        )
        Button(onClick = onOpenFile) { Text("Open journal file") }
    }
}

@Composable
private fun ErrorPanel(state: LoadState.Failed, onReload: () -> Unit, onOpenFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("This journal couldn't be read", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.line > 0) "Line ${state.line}: ${state.message}" else state.message,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.excerpt.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    state.excerpt,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    softWrap = false,
                )
            }
        }
        Text(
            "Fix it in your editor, then tap Reload.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalLedgerColors.current.mutedInk,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReload) { Text("Reload") }
            OutlinedButton(onClick = onOpenFile) { Text("Open another file") }
        }
    }
}

/** "expns isn't in this report: it has no account type. Set types…", opening Settings. */
@Composable
private fun UntypedBar(accounts: List<String>, onSetTypes: () -> Unit) {
    val lc = LocalLedgerColors.current
    val names = if (accounts.size <= 3) accounts.joinToString(", ") else accounts.take(3).joinToString(", ") + " and ${accounts.size - 3} more"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSetTypes)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, tint = lc.mutedInk, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            (if (accounts.size == 1) "$names isn't in this report: it has no account type." else "$names aren't in this report: they have no account type.") +
                    " Tap to choose types.",
            style = MaterialTheme.typography.bodySmall,
            color = lc.mutedInk,
        )
    }
}

/** Lightweight mode: the journal changed in the app and isn't read again by itself: Refresh reads it. */
@Composable
private fun StaleBar(onReload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onReload)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Changes not shown yet (Lightweight mode)",
            style = MaterialTheme.typography.bodySmall,
            color = LocalLedgerColors.current.mutedInk,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReload) { Text("Refresh") }
    }
}

@Composable
private fun WarningBar(warnings: List<JournalIssue>, onClick: () -> Unit) {
    val failed = warnings.count { it.isFailedAssertion }
    val text = when {
        failed == 1 -> "1 balance assertion failed"
        failed > 1 -> "$failed balance assertions failed"
        warnings.size == 1 -> "1 note about this journal"
        else -> "${warnings.size} notes about this journal"
    }
    val color = if (failed > 0) MaterialTheme.colorScheme.error else LocalLedgerColors.current.mutedInk
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f))
        Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WarningsDialog(warnings: List<JournalIssue>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("About this journal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                warnings.forEach { w ->
                    Text(
                        if (w.line > 0) "Line ${w.line}: ${w.message}" else w.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (w.isFailedAssertion) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------------- text helpers

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** The subtitle under the file name: which period, and whether amounts are changes or end balances. */
fun periodLine(span: DateSpan, accumulation: Accumulation): String = when {
    accumulation == Accumulation.END_BALANCE ->
        span.end?.let { "Balances on ${it.minusDays(1).format(DAY)}" } ?: "Latest balances"

    else -> describeSpan(span)
}

/** The subtitle for the budget report, eg "Monthly budget, running balances". */
fun budgetSubtitle(options: Options, span: DateSpan): String = buildString {
    append(options.interval.label).append(" budget")
    if (options.endBalance) append(", running balances")
    if (!span.isOpen) append(", ").append(describeSpan(span))
}

/** The subtitle for a report with a column per period, eg "Monthly changes, 2026". */
fun multiPeriodSubtitle(options: Options, span: DateSpan, accumulation: Accumulation): String = buildString {
    append(options.interval.label)
    append(if (accumulation == Accumulation.END_BALANCE) " balances" else " changes")
    if (!span.isOpen) append(", ").append(describeSpan(span))
}

/**
 * The keys of every row with subaccounts in [report], as used for collapsing (see [visibleRowsOf]): every
 * section and view of it, so Collapse all works whichever way it's shown. Empty in list mode.
 */
internal fun parentKeys(report: ReportUi): Set<String> {
    if (report.options.mode != AccountListMode.TREE) return emptySet()
    val out = HashSet<String>()
    fun <R> add(rows: List<R>, prefix: String, indent: (R) -> Int, name: (R) -> String) {
        for (i in 0 until rows.size - 1) if (indent(rows[i + 1]) > indent(rows[i])) out.add(prefix + name(rows[i]))
    }
    report.single?.let { add(it.rows, "", { r -> r.indent }, { r -> r.fullName }) }
    report.compound?.sections?.forEach { s -> add(s.report.rows, "${s.title}/", { r -> r.indent }, { r -> r.fullName }) }
    report.multi?.let { add(it.rows, "multi/", { r -> r.indent }, { r -> r.fullName }) }
    report.multiCompound?.sections?.forEach { s ->
        add(s.report.rows, "multi/${s.section.title}/", { r -> r.indent }, { r -> r.fullName })
    }
    report.budget?.let { add(it.rows, "budget/", { r -> r.indent }, { r -> r.fullName }) }
    return out
}

/** A switch with a title and an explanation, inside a dialog. */
@Composable
private fun DialogSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
