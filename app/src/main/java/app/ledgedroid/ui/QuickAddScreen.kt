package app.ledgedroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.AmountLayout
import app.ledgedroid.core.Entry
import app.ledgedroid.core.EntrySuggestions
import app.ledgedroid.core.QuickAdd
import app.ledgedroid.core.QuickAddState
import app.ledgedroid.core.QuickHints
import app.ledgedroid.core.QuickCompletion
import app.ledgedroid.core.QuickPrompt
import app.ledgedroid.core.QuickResult
import app.ledgedroid.core.QuickStep
import app.ledgedroid.core.Styles
import app.ledgedroid.core.Template
import app.ledgedroid.core.TransactionDraft
import java.time.LocalDate

/**
 * Quick add: a transaction one prompt at a time, like hledger add. The answers so far are listed at the top,
 * and the prompt sits just above the keyboard with its suggestions right above it, so they're never hidden.
 *
 * Tab cycles through the suggestions as in vim (Shift+Tab goes back; past the last one comes back to what
 * was typed); the ⇥ and ⇤ keys beside the field do the same on a phone keyboard. Enter answers, taking the
 * prompt's default when nothing's typed. Back goes to the previous prompt.
 */
@Composable
fun QuickAddScreen(
    styles: Styles,
    suggestions: EntrySuggestions?,
    templates: List<Template>,
    journalName: String,
    declaredPayeesOnly: Boolean,
    amountLayout: AmountLayout,
    saveState: SaveState,
    addedInSession: Int,
    startDate: LocalDate,
    /** Start with this template already chosen (from the widget or the Templates screen). */
    startTemplate: Template? = null,
    /** What was picked out of shared text, as the first prompts' defaults; and the text, to show. */
    startHints: QuickHints = QuickHints(),
    /** Put each prompt's default in the box as text to edit, like the form, instead of as a grey hint. */
    fillDefaults: Boolean = false,
    sharedText: String? = null,
    onSave: (TransactionDraft, Boolean) -> Unit,
    onToForm: (TransactionDraft) -> Unit,
    onClose: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    val engine: QuickAdd = remember(styles, suggestions, templates, declaredPayeesOnly) {
        QuickAdd(styles, suggestions, templates, LocalDate.now(), declaredPayeesOnly)
    }
    var state: QuickAddState by remember {
        mutableStateOf(startTemplate?.let { engine.startWith(it, startDate, startHints) } ?: engine.start(startDate, startHints))
    }
    /** The text a prompt starts with: its default when filling them in, except at the last look. */
    fun startText(s: QuickAddState): String =
        if (fillDefaults && s.step != QuickStep.Confirm) engine.prompt(s).default.orEmpty() else ""
    var field: TextFieldValue by remember { startText(state).let { mutableStateOf(TextFieldValue(it, TextRange(it.length))) } }
    // What was typed before Tab, and which suggestion Tab is on (-1: none, the typed text).
    var stem: String by remember { mutableStateOf(startText(state)) }
    var cycle: Int by remember { mutableIntStateOf(-1) }
    var problem: String? by remember { mutableStateOf<String?>(null) }
    val completions: List<QuickCompletion> = remember(state, stem) { engine.completions(state, stem) }
    val prompt: QuickPrompt = engine.prompt(state)
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val saving = saveState == SaveState.Saving

    fun setText(t: String) {
        field = TextFieldValue(t, TextRange(t.length))
    }

    fun go(next: QuickAddState) {
        state = next
        val text = startText(next)
        stem = text
        cycle = -1
        problem = null
        setText(text)
    }

    /** Tab (or Shift+Tab): the next (or previous) suggestion, coming back round to what was typed. */
    fun tab(step: Int) {
        if (completions.isEmpty()) return
        val n = completions.size + 1                       // the suggestions, and what was typed
        cycle = ((cycle + 1 + step) % n + n) % n - 1
        setText(if (cycle < 0) stem else completions[cycle].value)
    }

    fun pick(i: Int) {
        cycle = i
        setText(completions[i].value)
    }

    fun answer() {
        if (saving) return
        when (val r = engine.submit(state, field.text, completions.getOrNull(cycle))) {
            is QuickResult.Next -> go(r.state)
            is QuickResult.Problem -> problem = r.message
            is QuickResult.Done -> onSave(r.draft, false)
        }
    }

    val back = {
        val previous = engine.back(state)
        if (previous != null) go(previous) else onClose()
    }
    BackHandler(onBack = back)

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Keep the latest answer in view.
    LaunchedEffect(state.log.size, state.step) {
        val last = state.log.size + (if (state.step == QuickStep.Confirm) 1 else 0)
        if (last > 0) list.animateScrollToItem(last - 1)
    }

    Scaffold(
        topBar = {
            LedgerTopBar(
                title = if (addedInSession > 0) "Quick add (${addedInSession + 1})" else "Quick add",
                subtitle = journalName,
                onBack = onClose,
                backIcon = Icons.Default.Close,
                backLabel = "Close",
                actions = {
                    TextButton(onClick = { onToForm(state.draft) }) { Text("Full form") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(),
        ) {
            // The answers so far, and at the end the transaction as it will be added.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (sharedText != null) item {
                    Text(
                        "Shared: " + sharedText.replace('\n', ' ').take(240),
                        style = MaterialTheme.typography.bodySmall,
                        color = lc.mutedInk,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                itemsIndexed(state.log) { _, line: Pair<String, String> ->
                    val (label, value) = line
                    Row {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = lc.mutedInk,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.step == QuickStep.Confirm) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text(
                                Entry.render(state.draft, styles, layout = amountLayout),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                            )
                        }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Suggestions just above the field, the one Tab is on highlighted, like vim's menu.
                    if (completions.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            completions.forEachIndexed { i, c ->
                                FilterChip(
                                    selected = i == cycle,
                                    onClick = { pick(i) },
                                    label = {
                                        Text(
                                            if (c.template != null) "${c.value} · template" else c.value,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    val shownProblem = problem ?: (saveState as? SaveState.Failed)?.message
                    if (shownProblem != null) {
                        Text(
                            shownProblem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = field,
                            onValueChange = { v ->
                                if (v.text != field.text) {
                                    stem = v.text
                                    cycle = -1
                                    problem = null
                                }
                                field = v
                            },
                            label = { Text(prompt.label) },
                            placeholder = prompt.default?.let { d -> { Text(d, fontFamily = FontFamily.Monospace) } },
                            singleLine = true,
                            enabled = !saving,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(
                                capitalization = if (state.step == QuickStep.Payee) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                                // Plain text keys even for amounts, which can have a currency: $4.50, 4.50 EUR.
                                keyboardType = KeyboardType.Text,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { answer() }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focus)
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                                        tab(if (e.isShiftPressed) -1 else 1); true
                                    } else false
                                },
                        )
                        // Tab keys for phone keyboards, which have none.
                        TextButton(onClick = { tab(-1) }, enabled = completions.isNotEmpty()) { Text("⇤") }
                        TextButton(onClick = { tab(1) }, enabled = completions.isNotEmpty()) { Text("⇥") }
                    }
                    Text(
                        prompt.hint + (prompt.default?.let { " · Enter for $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = lc.mutedInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = back, enabled = !saving) {
                            Text(if (state.previous == null) "Cancel" else "Back")
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.step == QuickStep.Confirm) {
                            FilledTonalButton(onClick = { onSave(state.draft, true) }, enabled = !saving) {
                                Icon(DoneAllIcon, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add, next")
                            }
                        }
                        Button(onClick = { answer() }, enabled = !saving) {
                            if (state.step == QuickStep.Confirm) {
                                Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add")
                            } else Text("Enter")
                        }
                    }
                }
            }
        }
    }
}
