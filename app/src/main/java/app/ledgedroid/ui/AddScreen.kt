package app.ledgedroid.ui

import app.ledgedroid.core.Payees
import app.ledgedroid.core.Transaction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import app.ledgedroid.core.AmountLayout
import app.ledgedroid.core.DraftCheck
import app.ledgedroid.core.DraftPosting
import app.ledgedroid.core.Entry
import app.ledgedroid.core.EntrySuggestions
import app.ledgedroid.core.Status
import app.ledgedroid.core.Styles
import app.ledgedroid.core.Template
import app.ledgedroid.core.TransactionDraft
import app.ledgedroid.core.templatesMatching
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The form's optional fields, which can be hidden from the form's ⋮ menu. A hidden field still shows
 * when it has something in it (eg from a template, or a transaction being edited), so nothing is
 * written that can't be seen.
 */
enum class FormField(val label: String, val description: String) {
    TEMPLATE("Template", "The box at the top for filling the form from a template"),
    STATUS("Status", "Unmarked, pending ! or cleared *"),
    NOTE("Note", "Written after the payee as payee | note"),
    CURRENCY("Currency", "Hidden, every amount gets the journal's usual currency"),
    COMMENT("Comment", "A comment or tags on the transaction"),
}

/**
 * The add-transaction form. With [templateMode] it edits a template instead: there is a name, no
 * date, and the amounts don't have to balance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    draft: TransactionDraft,
    styles: Styles,
    suggestions: EntrySuggestions?,
    templates: List<Template>,
    saveState: SaveState,
    journalName: String,
    templateMode: Boolean,
    templateName: String,
    editing: Boolean,
    addedInSession: Int,
    hiddenFields: Set<FormField>,
    amountLayout: AmountLayout,
    declaredPayeesOnly: Boolean,
    onHiddenFields: (Set<FormField>) -> Unit,
    onAmountLayout: (AmountLayout) -> Unit,
    onDeclaredPayeesOnly: (Boolean) -> Unit,
    onChange: ((TransactionDraft) -> TransactionDraft) -> Unit,
    onTemplateName: (String) -> Unit,
    onApplyTemplate: (Template) -> Unit,
    /** Add (or save changes); with true, add and start the next one. */
    onSave: (Boolean) -> Unit,
    /** Intelligent mode: iadd's matching, and picking a payee fills in its last transaction's accounts. */
    intelligent: Boolean = false,
    similarTo: (String) -> Transaction? = { null },
    onSaveTemplate: (String) -> Unit,
    onClose: () -> Unit,
) {
    val check = remember(draft, styles) { Entry.check(draft, styles) }
    var focused by remember { mutableStateOf<String?>(null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var nameTemplate by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var choosingFields by remember { mutableStateOf(false) }
    // Intelligent mode: the transaction the accounts were filled in from, to say so under the payee.
    var filledFrom: Transaction? by remember { mutableStateOf<Transaction?>(null) }
    var choosingLayout by remember { mutableStateOf(false) }
    val initial = remember { draft }
    // A hidden field shows while it has something in it, and then stays for as long as the form is open,
    // so it doesn't vanish while its text is being deleted.
    val defaultCommodity = suggestions?.defaultCommodity.orEmpty()
    val withValues = buildSet {
        if (draft.status != Status.UNMARKED) add(FormField.STATUS)
        if (draft.note.isNotBlank()) add(FormField.NOTE)
        if (draft.comment.isNotBlank()) add(FormField.COMMENT)
        if (suggestions != null && draft.postings.any { it.commodity.trim() != defaultCommodity }) add(FormField.CURRENCY)
    }
    var revealed by remember { mutableStateOf(emptySet<FormField>()) }
    LaunchedEffect(withValues) { revealed = revealed + withValues }
    fun shows(f: FormField) = f !in hiddenFields || f in withValues || f in revealed
    // After adding one of several, start typing the next payee straight away.
    val payeeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (addedInSession > 0) runCatching { payeeFocus.requestFocus() } }
    val dirty = if (editing) draft != initial
    else draft.payee.isNotBlank() || draft.note.isNotBlank() || draft.postings.any { it.account.isNotBlank() || it.amount.isNotBlank() }
    val close = { if (dirty && !templateMode) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)

    // A field with suggestions is scrolled to the top when it gets focus, so its suggestions open into the
    // space below it instead of under the keyboard. The fields say where they are through LocalFieldTracker.
    val scroll = rememberScrollState()
    val tracker = remember { FieldTracker() }
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        val key = focused ?: return@LaunchedEffect
        if (!tracker.knows(key)) return@LaunchedEffect
        delay(250) // the keyboard coming up changes the space first
        val y = tracker.top(key) ?: return@LaunchedEffect
        scroll.animateScrollTo((y - with(density) { 8.dp.toPx() }).roundToInt().coerceAtLeast(0))
    }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val save = { another: Boolean ->
        if (templateMode) onSaveTemplate(templateName)
        else if (check.ok) onSave(another) else showErrors = true
    }

    Scaffold(
        topBar = {
          Column {
            LedgerTopBar(
                title = when {
                    templateMode -> "Template"
                    editing -> "Edit transaction"
                    addedInSession > 0 -> "Add transaction (${addedInSession + 1})"
                    else -> "Add transaction"
                },
                subtitle = if (templateMode) "Used to fill in the form" else journalName,
                onBack = close,
                backIcon = Icons.Default.Close,
                backLabel = "Close",
                actions = {
                    // Templates are picked by typing in the Template box at the top of the form, so a long
                    // list of them doesn't make a menu unusable; saving one is in this menu, with the
                    // form's settings.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!templateMode && !editing) {
                                DropdownMenuItem(
                                    text = { Text("Save this as a template…") },
                                    enabled = dirty,
                                    onClick = { menuOpen = false; nameTemplate = true },
                                )
                                HorizontalDivider()
                            }
                            if (!templateMode) {
                                CheckMenuItem("Align amounts", amountLayout.aligned) {
                                    onAmountLayout(amountLayout.copy(aligned = it))
                                }
                                CheckMenuItem("Write .00 on whole amounts", amountLayout.padDecimals) {
                                    onAmountLayout(amountLayout.copy(padDecimals = it))
                                }
                                DropdownMenuItem(
                                    text = { Text("Amount spacing…") },
                                    onClick = { menuOpen = false; choosingLayout = true },
                                )
                            }
                            CheckMenuItem("Suggest declared payees only", declaredPayeesOnly, onDeclaredPayeesOnly)
                            DropdownMenuItem(
                                text = { Text("Fields shown…") },
                                onClick = { menuOpen = false; choosingFields = true },
                            )
                        }
                    }
                },
            )
            // Whether it balances, or what's wrong, at the top where it's seen while typing.
            StatusStrip(check, saveState, templateMode, showErrors)
          }
        },
        floatingActionButton = {
            SaveButtons(
                templateMode = templateMode,
                editing = editing,
                saving = saveState == SaveState.Saving,
                enabled = if (templateMode) templateName.isNotBlank() else true,
                onSave = save,
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalFieldTracker provides tracker) {
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(scroll)
                .onGloballyPositioned { tracker.content = it }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (templateMode) {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = onTemplateName,
                    label = { Text("Template name") },
                    placeholder = { Text("Coffee, Fuel, Rent…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next
                    ),
                )
            } else {
                if (!editing && templates.isNotEmpty() && shows(FormField.TEMPLATE)) {
                    TemplateField(templates, focused, onFocus = { focused = it }, onApply = onApplyTemplate)
                }
                DateRow(draft.date) { d -> onChange { it.copy(date = d) } }
            }
            if (shows(FormField.STATUS)) StatusRow(draft.status) { s -> onChange { it.copy(status = s) } }

            AutoField(
                focusRequester = payeeFocus,
                key = "payee",
                value = draft.payee,
                label = "Payee",
                focused = focused,
                onFocus = { focused = it },
                suggestions = { suggestions?.payeesMatching(draft.payee, declaredOnly = declaredPayeesOnly, intelligent = intelligent).orEmpty() },
                isExact = { suggestions?.isKnownPayee(it) == true },
                onValueChange = { v -> filledFrom = null; onChange { it.copy(payee = v) } },
                onPick = { v ->
                    // Intelligent: with no accounts entered yet, start from the last transaction with this payee.
                    val like = if (intelligent && draft.postings.all { it.isBlank }) similarTo(v) else null
                    if (like != null) {
                        val from = Entry.fromTransaction(like, styles, draft.date)
                        onChange { it.copy(payee = v, postings = from.postings) }
                    } else onChange { it.copy(payee = v) }
                    filledFrom = like
                },
                capitalization = KeyboardCapitalization.Words,
                supporting = filledNote(filledFrom) ?: payeeNote(suggestions, draft.payee, declaredPayeesOnly, focused == "payee"),
                suggestionNote = suggestions?.takeIf { !declaredPayeesOnly && it.hasDeclaredPayees }?.let { s ->
                    { p: String -> if (s.isDeclaredPayee(p)) null else "not declared" }
                },
            )
            if (shows(FormField.NOTE)) OutlinedTextField(
                value = draft.note,
                onValueChange = { v -> onChange { it.copy(note = v) } },
                label = { Text("Note (optional)") },
                supportingText = { Text("Written after the payee as payee | note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                ),
            )

            Text("Accounts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            draft.postings.forEachIndexed { i, p ->
                PostingRow(
                    index = i,
                    posting = p,
                    check = check,
                    showErrors = showErrors && !templateMode,
                    styles = styles,
                    suggestions = suggestions,
                    showCurrency = shows(FormField.CURRENCY),
                    intelligent = intelligent,
                    focused = focused,
                    onFocus = { focused = it },
                    canRemove = draft.postings.size > 2,
                    onChange = { np ->
                        onChange { d ->
                            d.copy(
                                postings = d.postings.toMutableList().also { it[i] = np })
                        }
                    },
                    onRemove = { onChange { d -> d.copy(postings = d.postings.filterIndexed { k, _ -> k != i }) } },
                )
            }
            OutlinedButton(
                onClick = {
                    onChange { d ->
                        val commodity = d.postings.lastOrNull { it.commodity.isNotBlank() }?.commodity
                            ?: suggestions?.defaultCommodity.orEmpty()
                        d.copy(postings = d.postings + DraftPosting(commodity = commodity))
                    }
                },
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add another account")
            }

            if (shows(FormField.COMMENT)) OutlinedTextField(
                value = draft.comment,
                onValueChange = { v -> onChange { it.copy(comment = v) } },
                label = { Text("Comment (optional)") },
                supportingText = { Text("Tags go here too, like trip:lisbon") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!templateMode && check.amountErrors.isEmpty()) Preview(draft, styles, amountLayout)
            // Room below: clear of the round buttons, and while typing enough to scroll the last fields to
            // the top with their suggestions under them.
            Spacer(Modifier.height(if (imeVisible) (LocalConfiguration.current.screenHeightDp * 0.5f).dp else 160.dp))
        }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (editing) "Discard your changes?" else "Discard this transaction?") },
            text = { Text(if (editing) "The transaction in the journal stays as it was." else "It hasn't been added to the journal.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (choosingFields) {
        FieldsDialog(
            hidden = hiddenFields,
            onHidden = onHiddenFields,
            onDismiss = { choosingFields = false },
        )
    }
    if (choosingLayout) {
        AmountLayoutDialog(
            layout = amountLayout,
            draft = draft,
            styles = styles,
            defaultCommodity = defaultCommodity,
            onLayout = onAmountLayout,
            onDismiss = { choosingLayout = false },
        )
    }
    if (nameTemplate) {
        NameDialog(
            title = "Save as template",
            initial = draft.payee,
            confirmLabel = "Save",
            onDismiss = { nameTemplate = false },
            onConfirm = { nameTemplate = false; onSaveTemplate(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(date: LocalDate, onDate: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { picking = true },
            label = { Text(date.format(WEEKDAY_DATE), style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = "Choose date") },
        )
        FilterChip(selected = date == today, onClick = { onDate(today) }, label = { Text("Today") })
        FilterChip(
            selected = date == today.minusDays(1),
            onClick = { onDate(today.minusDays(1)) },
            label = { Text("Yesterday") })
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.utcMillis())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(it.utcDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusRow(status: Status, onStatus: (Status) -> Unit) {
    val options = listOf(Status.UNMARKED to "Unmarked", Status.PENDING to "Pending !", Status.CLEARED to "Cleared *")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (s, label) ->
            SegmentedButton(
                selected = status == s,
                onClick = { onStatus(s) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun PostingRow(
    index: Int,
    posting: DraftPosting,
    check: DraftCheck,
    showErrors: Boolean,
    styles: Styles,
    suggestions: EntrySuggestions?,
    showCurrency: Boolean,
    intelligent: Boolean,
    focused: String?,
    onFocus: (String?) -> Unit,
    canRemove: Boolean,
    onChange: (DraftPosting) -> Unit,
    onRemove: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    val accountError = check.accountErrors[index]?.takeIf { showErrors && !posting.isBlank }
    val amountError = check.amountErrors[index]
    val isNew = posting.account.isNotBlank() && suggestions != null && !suggestions.isKnownAccount(posting.account)
    val inferred = check.inferredIndex == index && !posting.isBlank

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                AutoField(
                    key = "account$index",
                    value = posting.account,
                    label = "Account",
                    focused = focused,
                    onFocus = onFocus,
                    suggestions = { suggestions?.accountsMatching(posting.account, intelligent = intelligent).orEmpty() },
                    isExact = { suggestions?.isKnownAccount(it) == true },
                    onValueChange = { onChange(posting.copy(account = it)) },
                    onPick = { onChange(posting.copy(account = it)) },
                    error = accountError,
                    supporting = if (isNew && accountError == null) "New account" else null,
                    modifier = Modifier.weight(1f),
                    moveFocusOnPick = true,
                )
                IconButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove this account",
                        tint = if (canRemove) lc.mutedInk else lc.guide
                    )
                }
            }
            Row(
                Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = posting.amount,
                    onValueChange = { onChange(posting.copy(amount = it)) },
                    label = { Text("Amount") },
                    placeholder = {
                        Text(if (inferred) styles.format(check.inferred) else "Leave empty to calculate", maxLines = 1)
                    },
                    isError = amountError != null,
                    supportingText = when {
                        amountError != null -> ({ Text(amountError) })
                        inferred -> ({ Text("Calculated: ${styles.format(check.inferred)}, written into the journal") })
                        Entry.isExpression(posting.amount) -> Entry.parseAmount(posting.amount)?.let { v ->
                            { Text("= ${Entry.formatExact(posting.commodity.trim(), v, styles)}") }
                        }

                        else -> null
                    },
                    singleLine = true,
                    textStyle = NumberStyle,
                    trailingIcon = {
                        TextButton(onClick = { onChange(posting.copy(amount = flipSign(posting.amount))) }) {
                            Text("±", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    // The phone keyboard has + * ( ) as well as digits, for sums like 12.50+3.20; the decimal
                    // keyboard has no + on many phones.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                if (showCurrency) AutoField(
                    key = "commodity$index",
                    value = posting.commodity,
                    label = "Currency",
                    focused = focused,
                    onFocus = onFocus,
                    suggestions = { suggestions?.commoditiesMatching(posting.commodity).orEmpty() },
                    isExact = { suggestions?.isKnownCommodity(it) == true },
                    onValueChange = { onChange(posting.copy(commodity = it)) },
                    onPick = { onChange(posting.copy(commodity = it)) },
                    modifier = Modifier.width(104.dp),
                    capitalization = KeyboardCapitalization.Characters,
                )
            }
        }
    }
}

/** "4.50" ↔ "-4.50". */
private fun flipSign(amount: String): String {
    val t = amount.trim()
    return when {
        t.startsWith("-") || t.startsWith("\u2212") -> t.substring(1)
        t.startsWith("+") -> "-" + t.substring(1)
        else -> "-$t"
    }
}

/**
 * A small box to fill the form from a template by typing part of its name (or payee): matching templates
 * show below it; tap one, or press Done for the first.
 */
@Composable
private fun TemplateField(
    templates: List<Template>,
    focused: String?,
    onFocus: (String?) -> Unit,
    onApply: (Template) -> Unit,
) {
    val key = "template"
    var text by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val matches = remember(templates, text) { templatesMatching(templates, text) }
    fun apply(t: Template) {
        onApply(t)
        text = ""
        focusManager.clearFocus()
    }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Template: type a name, eg coffee", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(20.dp)) },
            trailingIcon = if (text.isNotEmpty()) {
                { IconButton(onClick = { text = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }
            } else null,
            supportingText = if (text.isNotBlank() && matches.isEmpty()) {
                { Text("No template matches \"${text.trim()}\"") }
            } else null,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { matches.firstOrNull()?.let { apply(it) } }),
            modifier = Modifier.fillMaxWidth()
                .trackedAs(key)
                .onFocusChanged { s -> if (s.isFocused) onFocus(key) else if (focused == key) onFocus(null) },
        )
        if (focused == key) {
            SuggestionList(matches.map { it.name }, onPick = { name ->
                matches.firstOrNull { it.name == name }?.let { apply(it) }
            })
        }
    }
}

/** A text field that shows suggestions below itself while it has focus. */
@Composable
private fun AutoField(
    key: String,
    value: String,
    label: String,
    focused: String?,
    onFocus: (String?) -> Unit,
    suggestions: () -> List<String>,
    onValueChange: (String) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    moveFocusOnPick: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Whether the text is already a complete value, eg a known account, so Next keeps it as typed. */
    isExact: (String) -> Boolean = { false },
    suggestionNote: ((String) -> String?)? = null,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next),
            // Next (or Enter) with exactly one suggestion takes it, then moves on as usual. Text that is
            // already a complete value is kept, so "assets:cash" isn't replaced by "assets:cash:wallet".
            keyboardActions = KeyboardActions(onNext = {
                if (value.isNotBlank() && !isExact(value)) suggestions().singleOrNull()?.let(onPick)
                defaultKeyboardAction(ImeAction.Next)
            }),
            modifier = Modifier.fillMaxWidth()
                .trackedAs(key)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { s -> if (s.isFocused) onFocus(key) else if (focused == key) onFocus(null) },
        )
        if (focused == key) {
            SuggestionList(suggestions(), onPick = {
                onPick(it)
                if (moveFocusOnPick) focusManager.moveFocus(FocusDirection.Next)
            }, note = suggestionNote)
        }
    }
}

@Composable
private fun Preview(draft: TransactionDraft, styles: Styles, layout: AmountLayout) {
    if (draft.used.isEmpty()) return
    Column {
        Text(
            "Will be added as",
            style = MaterialTheme.typography.labelLarge,
            color = LocalLedgerColors.current.mutedInk
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                Entry.render(draft, styles, layout = layout),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

/**
 * Where the fields with suggestions are in the form, so the focused one can be scrolled to the top.
 * [content] is the form's scrolling content; each field reports itself with [trackedAs].
 */
internal class FieldTracker {
    var content: LayoutCoordinates? = null
    private val tops = HashMap<String, Float>()

    fun place(key: String, field: LayoutCoordinates) {
        val c = content ?: return
        if (c.isAttached && field.isAttached) tops[key] = c.localPositionOf(field, Offset.Zero).y
    }

    fun knows(key: String) = key in tops
    fun top(key: String): Float? = tops[key]
}

internal val LocalFieldTracker = compositionLocalOf<FieldTracker?> { null }

/** Report this field's place to the form, under [key]. */
@Composable
private fun Modifier.trackedAs(key: String): Modifier {
    val tracker = LocalFieldTracker.current ?: return this
    return this.onGloballyPositioned { tracker.place(key, it) }
}

/**
 * Under the title: whether the transaction balances, or what's wrong. Problems are shown quietly while
 * typing, and in red once adding was tried, or saving failed.
 */
@Composable
private fun StatusStrip(check: DraftCheck, saveState: SaveState, templateMode: Boolean, showErrors: Boolean) {
    val lc = LocalLedgerColors.current
    val failed = saveState as? SaveState.Failed
    val (text, ok) = when {
        failed != null -> failed.message to false
        templateMode -> "Templates don't need to balance. Amounts can be changed each time." to true
        check.ok -> (if (check.inferredIndex != null) "Balanced, with one amount calculated" else "Balanced") to true
        else -> (check.firstProblem ?: "") to false
    }
    val loud = !ok && (failed != null || showErrors)
    Surface(color = if (loud) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ok) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = when {
                    ok -> lc.rule
                    loud -> MaterialTheme.colorScheme.onErrorContainer
                    else -> lc.mutedInk
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (loud) MaterialTheme.colorScheme.onErrorContainer else lc.mutedInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The round buttons, above the keyboard: ✓ adds the transaction (or saves the changes, or the template),
 * and when adding, a smaller lighter ✓✓ above it adds it and starts the next one.
 */
@Composable
private fun SaveButtons(
    templateMode: Boolean,
    editing: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onSave: (Boolean) -> Unit,
) {
    Column(
        Modifier.imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!templateMode && !editing) {
            SmallFloatingActionButton(
                onClick = { if (!saving) onSave(true) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(DoneAllIcon, contentDescription = "Add, and start another")
            }
        }
        FloatingActionButton(
            onClick = { if (!saving && enabled) onSave(false) },
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Check,
                    contentDescription = when {
                        templateMode -> "Save template"
                        editing -> "Save changes"
                        else -> "Add to journal"
                    },
                )
            }
        }
    }
}

/** Intelligent mode: what to say under the payee when its accounts were filled in from [t], or null. */
private fun filledNote(t: Transaction?): String? =
    t?.let { "Filled in from your ${Payees.of(it.description)} of ${it.date}: change anything" }

/** What to say under the payee about payee declarations, or null. */
private fun payeeNote(suggestions: EntrySuggestions?, payee: String, declaredOnly: Boolean, focused: Boolean): String? {
    if (suggestions == null) return null
    return when {
        declaredOnly && !suggestions.hasDeclaredPayees && focused ->
            "No payees are declared in this journal, so none are suggested (⋮ menu)"
        suggestions.hasDeclaredPayees && payee.isNotBlank() && !suggestions.isDeclaredPayee(payee) -> "Not a declared payee"
        else -> null
    }
}

/** A menu item with a checkbox, that stays open so the change can be seen. */
@Composable
private fun CheckMenuItem(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChecked(!checked) },
    )
}

/** Choose which optional fields the form shows. */
@Composable
private fun FieldsDialog(hidden: Set<FormField>, onHidden: (Set<FormField>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fields shown") },
        text = {
            Column {
                Text(
                    "Untick the fields you don't use. A hidden field still shows when it has something in it, " +
                            "eg from a template.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalLedgerColors.current.mutedInk,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FormField.entries.forEach { f ->
                    val shown = f !in hidden
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = shown,
                                role = Role.Checkbox,
                                onValueChange = { on -> onHidden(if (on) hidden - f else hidden + f) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = shown, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(f.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                f.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalLedgerColors.current.mutedInk,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (hidden.isNotEmpty()) {
            { TextButton(onClick = { onHidden(emptySet()) }) { Text("Show all") } }
        } else null,
    )
}

/**
 * How amounts are spaced after account names: a number of spaces, or lined up on their decimal marks
 * at a column, with a preview of the transaction in the form (or an example).
 */
@Composable
private fun AmountLayoutDialog(
    layout: AmountLayout,
    draft: TransactionDraft,
    styles: Styles,
    defaultCommodity: String,
    onLayout: (AmountLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val example = remember(draft, defaultCommodity) {
        if (draft.used.size >= 2 && Entry.check(draft, styles).amountErrors.isEmpty()) draft
        else TransactionDraft(
            date = draft.date,
            payee = "Corner Cafe",
            postings = listOf(
                DraftPosting("expenses:food:coffee", "4.50", defaultCommodity),
                DraftPosting("assets:cash", "", defaultCommodity),
            ),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Amount spacing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = layout.aligned, role = Role.Switch, onValueChange = { onLayout(layout.copy(aligned = it)) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Align amounts", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (layout.aligned) "Decimal marks line up at a column, like hledger print"
                            else "A set number of spaces after each account",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalLedgerColors.current.mutedInk,
                        )
                    }
                    Switch(checked = layout.aligned, onCheckedChange = null)
                }
                if (layout.aligned) {
                    NumberStepper(
                        label = "Decimal marks at column",
                        value = layout.column,
                        range = AmountLayout.MIN_COLUMN..AmountLayout.MAX_COLUMN,
                        onValue = { onLayout(layout.copy(column = it)) },
                    )
                    Text(
                        "hledger print uses column ${AmountLayout.DEFAULT_COLUMN}. A long account name moves the " +
                                "column right for that transaction, keeping two spaces after it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalLedgerColors.current.mutedInk,
                    )
                } else {
                    NumberStepper(
                        label = "Spaces after the account",
                        value = layout.gap,
                        range = AmountLayout.MIN_SPACES..AmountLayout.MAX_SPACES,
                        onValue = { onLayout(layout.copy(spaces = it)) },
                    )
                    Text(
                        "At least two, so hledger can tell where the account name ends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalLedgerColors.current.mutedInk,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = layout.padDecimals, role = Role.Switch, onValueChange = { onLayout(layout.copy(padDecimals = it)) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Write .00 on whole amounts", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (layout.padDecimals) "\$4 is written \$4.00, the commodity's usual decimals"
                            else "Amounts are written as typed: \$4 stays \$4, \$4.50 stays \$4.50",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalLedgerColors.current.mutedInk,
                        )
                    }
                    Switch(checked = layout.padDecimals, onCheckedChange = null)
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        Entry.render(example, styles, layout = layout),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (layout != AmountLayout.APP_DEFAULT) {
            { TextButton(onClick = { onLayout(AmountLayout.APP_DEFAULT) }) { Text("Reset") } }
        } else null,
    )
}

@Composable
private fun NumberStepper(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { onValue((value - 1).coerceIn(range)) }, enabled = value > range.first) {
            Text("\u2212", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            value.toString(),
            style = NumberStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        TextButton(onClick = { onValue((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

internal fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
