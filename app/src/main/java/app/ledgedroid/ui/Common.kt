package app.ledgedroid.ui

import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Material's "filter list" icon (three lines, shortest at the bottom), drawn here because it's only in the
 * large extended icon set.
 */
val FilterListIcon: ImageVector by lazy {
    ImageVector.Builder("FilterList", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

/** Material's "done all" double tick, for Add and start another (the core icon set doesn't have it). */
val DoneAllIcon: ImageVector by lazy {
    ImageVector.Builder("DoneAll", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7zM22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41zM0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

/** A prompt, ">_", for quick add's button. */
val PromptIcon: ImageVector by lazy {
    ImageVector.Builder("Prompt", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M4.41,5.59L3,7l5,5 -5,5 1.41,1.41L10.83,12zM12,17h9v2h-9z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

internal val WEEKDAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
internal val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
internal val SHORT_DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy")

/** "27 Oct", or "27 Oct 25" for other years. */
internal fun shortDate(d: LocalDate, today: LocalDate = LocalDate.now()): String =
    d.format(if (d.year == today.year) SHORT_DAY else SHORT_DAY_YEAR)

/** The top bar used by every screen above the report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(backIcon, contentDescription = backLabel) }
        },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalLedgerColors.current.mutedInk,
                        maxLines = 1
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Ask for a name, eg for a template or saved view. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    label: String = "Name",
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Suggestions shown under the text field being edited. */
@Composable
fun SuggestionList(
    items: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A short muted note shown after an item, eg "not declared", or null for none. */
    note: ((String) -> String?)? = null,
) {
    if (items.isEmpty()) return
    val lc = LocalLedgerColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
    ) {
        Column {
            items.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = lc.hairline)
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    note?.invoke(s)?.let { n ->
                        Text(
                            n,
                            style = MaterialTheme.typography.labelSmall,
                            color = lc.mutedInk,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A box for filtering. What's typed is only applied when Enter (the keyboard's search key) or ✓ is
 * pressed, and until then it says so; ✕ clears the filter at once. [applied] is the filter in effect: when
 * it changes from elsewhere (cleared, a saved report), the box shows it.
 */
@Composable
fun FilterField(
    applied: String,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    /** A problem with the filter in effect, or a note about it, shown under the box when nothing's pending. */
    message: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    textStyle: TextStyle? = null,
    searchIcon: Boolean = true,
    /** More buttons after ✓ / ✕, eg help. */
    extraTrailing: (@Composable () -> Unit)? = null,
    /** Called when ✕ is pressed (after clearing), eg to close the box too. */
    onClear: (() -> Unit)? = null,
    /** Typed text that isn't applied yet, eg for suggestions under the box. */
    onTyping: ((String) -> Unit)? = null,
) {
    var text: String by rememberSaveable { mutableStateOf(applied) }
    LaunchedEffect(applied) { text = applied }
    val keyboard = LocalSoftwareKeyboardController.current
    val pending = text != applied
    val apply = {
        onApply(text)
        keyboard?.hide()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onTyping?.invoke(it) },
        modifier = modifier,
        singleLine = true,
        label = label?.let { l -> { Text(l) } },
        placeholder = placeholder?.let { p -> { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingIcon = if (searchIcon) ({ Icon(Icons.Default.Search, null) }) else null,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pending) {
                    IconButton(onClick = apply) { Icon(Icons.Default.Check, contentDescription = "Filter") }
                } else if (text.isNotEmpty() || onClear != null) {
                    IconButton(onClick = { text = ""; onApply(""); onClear?.invoke() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                    }
                }
                extraTrailing?.invoke()
            }
        },
        textStyle = textStyle ?: LocalTextStyle.current,
        isError = isError && !pending,
        supportingText = when {
            pending -> ({ Text("Press Enter to filter") })
            message != null -> ({ Text(message) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Search,
            // Filters are typed exactly: don't autocorrect acct: or --budget into something else.
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        keyboardActions = KeyboardActions(onSearch = { apply() }, onDone = { apply() }),
    )
}
