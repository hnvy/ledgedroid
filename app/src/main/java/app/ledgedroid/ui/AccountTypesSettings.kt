package app.ledgedroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.AccountType
import app.ledgedroid.core.BranchColors
import app.ledgedroid.core.Journal
import app.ledgedroid.core.TypeInfo
import app.ledgedroid.core.TypeSource
import app.ledgedroid.core.parentAccount

/** What each type means, shown when choosing one. */
private fun typeDetail(type: AccountType): String = when (type) {
    AccountType.ASSET -> "Balance sheet. What you own: bank accounts, savings, investments"
    AccountType.CASH -> "Balance sheet, as an asset. Cash and bank accounts you spend from"
    AccountType.LIABILITY -> "Balance sheet. What you owe: credit cards, loans"
    AccountType.EQUITY -> "Balance sheet. Opening balances and the like"
    AccountType.REVENUE -> "Income statement. Salary, interest, gifts received"
    AccountType.EXPENSE -> "Income statement. Spending: food, rent, fuel"
}

/** "Expense, set here", "Liability, from a type: tag on owed", "No type", for an account's row. */
internal fun describeType(account: String, info: TypeInfo): String {
    val type = info.type?.let { AccountType.label(it) } ?: return "No type: not in the balance sheet or income statement"
    val where = when (info.source) {
        TypeSource.SET_IN_APP -> if (info.from == account) "set here" else "set here on ${info.from}"
        TypeSource.DECLARED -> if (info.from == account) "from a type: tag in the journal" else "from a type: tag on ${info.from}"
        TypeSource.FROM_NAME -> "guessed from the name"
        TypeSource.NONE -> ""
    }
    return if (where.isEmpty()) type else "$type, $where"
}

/**
 * Settings › Account types: the journal's top-level accounts (and any account given a type here) with
 * their type and where it comes from. Tap one to choose its type; another account can be picked from
 * the journal's accounts.
 */
@Composable
fun AccountTypesSection(journal: Journal?, onAccountType: (String, AccountType?) -> Unit) {
    val lc = LocalLedgerColors.current
    var choosingFor by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    Text(
        "Reports need to know what each account is: the balance sheet shows assets, liabilities and equity, " +
                "the income statement revenues and expenses, and Payees what was spent. Ledgedroid guesses " +
                "from names like assets and expenses, and reads type: tags on account directives. Choose the " +
                "type here for accounts named otherwise, eg expns. Subaccounts get the same type unless they " +
                "have their own. The journal isn't changed.",
        style = MaterialTheme.typography.bodySmall,
        color = lc.mutedInk,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
    if (journal == null) {
        Text(
            "Open a journal to see its accounts here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        return
    }
    val accounts = remember(journal) {
        val tops = BranchColors.topLevels(journal)
        tops + journal.assignedTypes.keys.filter { it !in tops }.sorted()
    }
    accounts.forEach { account ->
        val info = journal.typeInfo(account)
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { choosingFor = account }
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(account, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                describeType(account, info),
                style = MaterialTheme.typography.bodySmall,
                color = if (info.type == null) MaterialTheme.colorScheme.error else lc.mutedInk,
            )
        }
    }
    TextButton(onClick = { picking = true }, modifier = Modifier.padding(start = 12.dp)) {
        Text("Choose the type of another account…")
    }

    if (picking) {
        AccountPickerDialog(
            journal = journal,
            onPick = { picking = false; choosingFor = it },
            onDismiss = { picking = false },
        )
    }
    choosingFor?.let { account ->
        AccountTypeDialog(
            account = account,
            journal = journal,
            onPick = { type -> onAccountType(account, type); choosingFor = null },
            onDismiss = { choosingFor = null },
        )
    }
}

/** Choose an account's type, or Automatic: from an account above it, a type: tag or its name. */
@Composable
internal fun AccountTypeDialog(
    account: String,
    journal: Journal,
    onPick: (AccountType?) -> Unit,
    onDismiss: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    val setHere = journal.assignedTypes[account]
    val automatic = remember(journal, account) { journal.automaticTypeInfo(account) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type of $account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TypeOption(
                    label = "Automatic",
                    detail = describeType(account, automatic).replaceFirstChar { it.uppercase() },
                    selected = setHere == null,
                    onClick = { onPick(null) },
                )
                AccountType.entries.forEach { type ->
                    TypeOption(
                        label = AccountType.label(type),
                        detail = typeDetail(type),
                        selected = setHere == type,
                        onClick = { onPick(type) },
                    )
                }
                Text(
                    "Applies to its subaccounts too, unless they have a type of their own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TypeOption(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
        }
    }
}

/** Every account in the journal (and every account above one), to choose one by typing part of its name. */
@Composable
private fun AccountPickerDialog(journal: Journal, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val all = remember(journal) {
        val names = LinkedHashSet<String>()
        for (a in journal.declaredAccounts + journal.accounts) {
            var p: String? = a
            while (p != null) {
                names.add(p)
                p = parentAccount(p)
            }
        }
        names.sorted()
    }
    val shown = remember(all, text) {
        val words = text.lowercase().split(' ', ':').filter { it.isNotEmpty() }
        all.filter { a -> a.lowercase().let { l -> words.all { l.contains(it) } } }.take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The list is narrowed when Enter (or ✓) is pressed, not while typing.
                FilterField(
                    applied = text,
                    onApply = { text = it },
                    placeholder = "Part of its name, eg expns",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(
                        "No account matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalLedgerColors.current.mutedInk,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it }) { account ->
                        val type = journal.accountType(account)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(account) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(account, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                type?.let { AccountType.label(it) } ?: "No type",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (type == null) MaterialTheme.colorScheme.error else LocalLedgerColors.current.mutedInk,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
