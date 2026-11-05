package app.ledgedroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.RegisterFilter
import app.ledgedroid.core.Status
import java.time.LocalDate

/**
 * "Advanced filters" under an account's In / Out buttons: tap to open a panel of filters for this
 * account's list only, so nothing has to be typed into the report's filter. It says how many are on, and
 * opens by itself when coming back to a filtered list. Suggestions (other accounts, tags, currencies) come
 * from this account's own postings in the period.
 */
@Composable
fun AdvancedFilters(ui: RegisterUi, filter: RegisterFilter, onFilter: (RegisterFilter) -> Unit) {
    val lc = LocalLedgerColors.current
    var open by rememberSaveable { mutableStateOf(filter.isActive) }
    val count = filter.activeCount
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FilterListIcon, contentDescription = null, tint = lc.mutedInk, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (count == 0) "Advanced filters" else "Advanced filters · $count on",
            style = MaterialTheme.typography.labelLarge,
            color = if (count == 0) lc.mutedInk else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) TextButton(onClick = { onFilter(RegisterFilter.NONE) }) { Text("Clear") }
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = if (open) "Hide filters" else "Show filters",
            tint = lc.mutedInk,
            modifier = Modifier.padding(8.dp).rotate(if (open) 180f else 0f),
        )
    }
    if (open) FilterPanel(ui, filter, onFilter)
}

@Composable
private fun FilterPanel(ui: RegisterUi, filter: RegisterFilter, onFilter: (RegisterFilter) -> Unit) {
    val report = ui.report
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Each box is applied when Enter (or ✓) is pressed, not while typing.
        FilterField(
            applied = filter.text,
            onApply = { onFilter(filter.copy(text = it)) },
            label = "Description or comment",
            modifier = Modifier.fillMaxWidth(),
        )

        // The amount's size: the In / Out buttons above choose the sign.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterField(
                applied = filter.minAmount,
                onApply = { onFilter(filter.copy(minAmount = it)) },
                label = "Amount at least",
                searchIcon = false,
                textStyle = NumberStyle,
                keyboardType = KeyboardType.Decimal,
                isError = filter.amountError != null && filter.minAmount.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            FilterField(
                applied = filter.maxAmount,
                onApply = { onFilter(filter.copy(maxAmount = it)) },
                label = "At most",
                searchIcon = false,
                textStyle = NumberStyle,
                keyboardType = KeyboardType.Decimal,
                isError = filter.amountError != null && filter.maxAmount.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        Hint(filter.amountError ?: "The size of the amount, whether in or out", error = filter.amountError != null)

        Label("Status")
        ChipRow {
            listOf(Status.UNMARKED to "Unmarked", Status.PENDING to "Pending !", Status.CLEARED to "Cleared *").forEach { (s, label) ->
                FilterChip(
                    selected = s in filter.statuses,
                    onClick = { onFilter(filter.copy(statuses = filter.statuses.toggle(s))) },
                    label = { Text(label) },
                )
            }
        }

        SuggestingField(
            value = filter.otherAccount,
            label = "With account",
            placeholder = "eg food, or expenses:fuel",
            choices = report.otherAccounts,
            onValue = { onFilter(filter.copy(otherAccount = it)) },
        )
        SuggestingField(
            value = filter.tag,
            label = "Tag",
            placeholder = "eg trip, or trip=lisbon",
            choices = report.tagNames,
            onValue = { onFilter(filter.copy(tag = it)) },
        )

        // Only when there's a choice to make (or one was made, to undo it).
        if (report.commodities.size > 1 || filter.commodities.isNotEmpty()) {
            Label("Currency")
            ChipRow {
                (report.commodities + filter.commodities).distinct().sorted().forEach { c ->
                    FilterChip(
                        selected = c in filter.commodities,
                        onClick = { onFilter(filter.copy(commodities = filter.commodities.toggle(c))) },
                        label = { Text(c.ifEmpty { "No currency" }) },
                    )
                }
            }
        }

        Label("Dates")
        ChipRow {
            DateChip("From", filter.from, default = ui.span.start) { onFilter(filter.copy(from = it)) }
            DateChip("To", filter.to, default = ui.span.end?.minusDays(1)) { onFilter(filter.copy(to = it)) }
        }
        filter.dateError?.let { Hint(it, error = true) }
    }
}

private fun <T> Set<T>.toggle(x: T): Set<T> = if (x in this) this - x else this + x

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalLedgerColors.current.mutedInk,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else LocalLedgerColors.current.mutedInk,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A text field suggesting [choices] containing what's typed while it has focus; tap one to use it. */
@Composable
private fun SuggestingField(
    value: String,
    label: String,
    placeholder: String,
    choices: List<String>,
    onValue: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // What's typed (suggestions follow it); it's applied with Enter, or by picking a suggestion.
    var typed: String by remember(value) { mutableStateOf(value) }
    Column {
        FilterField(
            applied = value,
            onApply = onValue,
            label = label,
            placeholder = placeholder,
            searchIcon = false,
            onTyping = { typed = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        if (focused) {
            val t = typed.trim()
            val shown = choices.filter { it != t && (t.isEmpty() || it.contains(t, ignoreCase = true)) }.take(6)
            SuggestionList(shown, onPick = onValue)
        }
    }
}

/** "From: 3 Oct 2026" chip opening a date picker; ✕ clears it. [default] is where the picker starts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChip(label: String, date: LocalDate?, default: LocalDate?, onDate: (LocalDate?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { picking = true },
        label = { Text(if (date == null) "$label: any" else "$label: ${date.format(WEEKDAY_DATE)}") },
        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, Modifier.size(18.dp)) },
        trailingIcon = if (date != null) {
            { Icon(Icons.Default.Close, contentDescription = "Clear $label date", Modifier.size(18.dp).clickable { onDate(null) }) }
        } else null,
    )
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: default ?: LocalDate.now()).utcMillis())
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
