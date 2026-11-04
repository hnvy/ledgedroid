package app.ledgedroid.ui

import app.ledgedroid.AlertSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.ledgedroid.Backups
import app.ledgedroid.core.BackupSchedule
import app.ledgedroid.core.BackupSettings
import app.ledgedroid.core.AccountType
import app.ledgedroid.core.BranchColors
import app.ledgedroid.core.Journal
import java.time.format.DateTimeFormatter

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/** Settings: account types, how the account tree looks, and where and when the journal is backed up. */
@Composable
fun SettingsScreen(
    treeStyle: TreeStyle,
    onTreeStyle: (TreeStyle) -> Unit,
    /** The journal's top-level accounts, each with a branch colour. */
    topLevels: List<String>,
    branchHues: Map<String, Float>,
    onBranchHue: (String, Float) -> Unit,
    onShuffleHues: (List<String>) -> Unit,
    ui: BackupUi,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: ((BackupSettings) -> BackupSettings) -> Unit,
    onChooseFolder: () -> Unit,
    onBackUpNow: () -> Unit,
    showTimings: Boolean,
    lastSaveTimings: String?,
    onShowTimings: (Boolean) -> Unit,
    /** The open journal, for its accounts' types; null with none open. */
    journal: Journal?,
    onAccountType: (String, AccountType?) -> Unit,
    quickAdd: Boolean,
    onQuickAdd: (Boolean) -> Unit,
    quickAddDefault: Boolean,
    onQuickAddDefault: (Boolean) -> Unit,
    /** Intelligent mode: hledger-iadd-like suggestions when adding. */
    intelligent: Boolean = false,
    onIntelligent: (Boolean) -> Unit = {},
    /** Its costly parts, for slower phones. */
    intelligentFuzzy: Boolean = true,
    onIntelligentFuzzy: (Boolean) -> Unit = {},
    intelligentDuplicates: Boolean = true,
    onIntelligentDuplicates: (Boolean) -> Unit = {},
    /** Quick add puts its defaults in the box as text, not as a hint. */
    quickAddFill: Boolean = false,
    onQuickAddFill: (Boolean) -> Unit = {},
    /** Quick add: Enter takes the only suggestion left. */
    quickAddEnterTakesOnly: Boolean = false,
    onQuickAddEnterTakesOnly: (Boolean) -> Unit = {},
    /** Share into quick add: open the patterns screen. */
    onSharePatterns: () -> Unit = {},
    sharePatternCount: Int = 0,
    /** Reminders for recurring transactions. */
    alertSettings: AlertSettings = AlertSettings(),
    notificationsAllowed: Boolean = true,
    scheduledTemplates: Int = 0,
    onAlertSettings: (AlertSettings) -> Unit = {},
    /** Included files: the folder they're read from. */
    includeFolder: String? = null,
    onChooseIncludeFolder: () -> Unit = {},
    onForgetIncludeFolder: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onRefresh() }
    val lc = LocalLedgerColors.current
    val s = ui.settings
    var editingColor by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { LedgerTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {

            Heading("Account types")
            AccountTypesSection(journal, onAccountType)

            Heading("Adding transactions")
            ToggleRow(
                "Quick add button",
                "A small button above + that adds a transaction one prompt at a time, like hledger add: " +
                        "Tab cycles through suggestions, and templates can fill in the rest.",
                quickAdd,
                onQuickAdd,
            )
            ToggleRow(
                "Quick add by default",
                if (quickAdd) "+ opens quick add, and the small button the full form. So do the widget, the shortcut " +
                        "and the quick settings tile, with templates already chosen."
                else "+ opens quick add, as do the widget, the shortcut and the quick settings tile, with templates " +
                        "already chosen. Full form in quick add switches to the form.",
                quickAddDefault,
                onQuickAddDefault,
            )
            ToggleRow(
                "Intelligent mode",
                "Adding works like hledger-iadd: your last transaction with the same description is followed, " +
                        "its next account and its amounts suggested until it balances; suggestions match loosely (e:f:c " +
                        "finds expenses:food:coffee), most used first; text ; comment adds a comment; and a duplicate is " +
                        "pointed out before it's added. In the form, picking a payee fills in its last accounts.",
                intelligent,
                onIntelligent,
            )
            // The parts that take the most work, to turn off on a slower phone.
            if (intelligent) {
                Column(Modifier.padding(start = 16.dp)) {
                    ToggleRow(
                        "Loose account matching",
                        if (intelligentFuzzy) "e:f:c or efc finds expenses:food:coffee. Turn off if typing feels slow."
                        else "Off: suggestions have every word typed somewhere (food cof), which is quicker.",
                        intelligentFuzzy,
                        onIntelligentFuzzy,
                    )
                    ToggleRow(
                        "Check for duplicates",
                        if (intelligentDuplicates) "Before adding, look through the journal for the same transaction. Turn off if it's slow with a big journal."
                        else "Off: a transaction just like one in the journal isn't pointed out.",
                        intelligentDuplicates,
                        onIntelligentDuplicates,
                    )
                }
            }
            ToggleRow(
                "Enter takes the only suggestion",
                if (quickAddEnterTakesOnly) "In quick add, when what you've typed leaves one payee, template or account, Enter uses it " +
                        "(\"groc\" becomes expenses:food:groceries). A whole name you've typed is kept as it is."
                else "In quick add, Enter uses what you've typed; Tab picks a suggestion.",
                quickAddEnterTakesOnly,
                onQuickAddEnterTakesOnly,
            )
            ToggleRow(
                "Fill in quick add's defaults",
                if (quickAddFill) "Each prompt starts with its default as text you can edit, like the form (eg a template's account)."
                else "Each prompt shows its default as a grey hint; Enter takes it, typing replaces it.",
                quickAddFill,
                onQuickAddFill,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onSharePatterns).padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Share patterns", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Share a bank message or a note to Ledgedroid to start quick add with it filled in. " +
                                if (sharePatternCount == 0) "Add patterns of your own to read other wording."
                                else "$sharePatternCount pattern${if (sharePatternCount == 1) "" else "s"} of your own.",
                        style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk,
                    )
                }
                Text("Patterns…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }

            Heading("Notifications")
            NotificationsSection(alertSettings, notificationsAllowed, scheduledTemplates, onAlertSettings)

            Heading("Included files")
            IncludedFilesSection(includeFolder, journal?.includedFiles.orEmpty(),
                journal?.warnings?.any { it.message.contains("include directive") } == true || journal?.includedFiles?.isNotEmpty() == true,
                onChooseIncludeFolder, onForgetIncludeFolder)

            Heading("Appearance")
            ToggleRow(
                "Colour branches",
                "Each top-level account in its own colour, tinting its rows, with darker shades of it for the accounts below",
                treeStyle.colors,
            ) { v -> onTreeStyle(treeStyle.copy(colors = v)) }
            if (treeStyle.colors) {
                Text(
                    "Colours are picked at random, well apart from each other. Tap one to change it; long-pressing " +
                            "an account in a report does too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                if (topLevels.isEmpty()) {
                    Text(
                        "Open a journal to see its accounts here.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                ContrastSlider(treeStyle.contrast) { c -> onTreeStyle(treeStyle.copy(contrast = c)) }
                ShadingSlider(
                    treeStyle.shading,
                    // The shades of the first branch, as an example.
                    exampleHue = topLevels.firstNotNullOfOrNull { branchHues[it] } ?: 210f,
                    contrast = treeStyle.contrast,
                ) { v -> onTreeStyle(treeStyle.copy(shading = v)) }
                BranchColorsList(topLevels, branchHues, treeStyle.contrast, onEdit = { editingColor = it })
                if (topLevels.size > 1) {
                    TextButton(onClick = { onShuffleHues(topLevels) }, modifier = Modifier.padding(start = 12.dp)) {
                        Text("New random colours")
                    }
                }
            }
            ToggleRow(
                "Amount bars",
                "A bar under each account showing its share of the account above it",
                treeStyle.bars,
            ) { v -> onTreeStyle(treeStyle.copy(bars = v)) }

            Heading("Speed")
            ToggleRow(
                "Show how long saving takes",
                "Adds the time to the message after each save, to find out what's slow on this phone",
                showTimings,
                onShowTimings,
            )
            lastSaveTimings?.let {
                Text(
                    "Last save: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            Heading("Backups")
            Text(
                "Copies of the journal are saved next to it, named like ${ui.journalName ?: "ledger.journal"}.2026-10-27_193012.bak. " +
                        "If saving a change ever fails, the journal is put back as it was either way.",
                style = MaterialTheme.typography.bodyMedium,
                color = lc.mutedInk,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            if (ui.journalName == null) {
                Text(
                    if (ui.noJournal) "Open your journal from the menu to set up backups."
                    else "Open your journal from the menu (not with Open with) to set up backups.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else {
                // Folder
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Backup folder", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        ui.folderName?.let { "$it. Backups are saved here." }
                            ?: "Not chosen. Android only lets Ledgedroid create files in a folder you choose, so choose the folder that contains ${ui.journalName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.folderName == null) MaterialTheme.colorScheme.error else lc.mutedInk,
                    )
                    OutlinedButton(onClick = onChooseFolder) { Text(if (ui.folderName == null) "Choose folder" else "Change folder") }
                }

                ChoiceRow(
                    title = "Back up regularly",
                    detail = "Checked in the background a few times a day, and when you open the app",
                    value = s.schedule.label,
                    choices = BackupSchedule.entries.map { it.label to it },
                ) { v -> onSettings { it.copy(schedule = v) } }
                ToggleRow(
                    "Back up before each change",
                    "Before adding, editing or deleting a transaction in the app",
                    s.beforeChange,
                ) { v -> onSettings { it.copy(beforeChange = v) } }
                ToggleRow(
                    "Skip if unchanged",
                    "Don't save a new backup when the journal is the same as the latest one",
                    s.skipUnchanged,
                ) { v -> onSettings { it.copy(skipUnchanged = v) } }
                ChoiceRow(
                    title = "Backups to keep",
                    detail = "Older backups are deleted. Only files named like the example above are ever deleted.",
                    value = keepLabel(s.keep),
                    choices = BackupSettings.KEEP_CHOICES.map { keepLabel(it) to it },
                ) { v -> onSettings { it.copy(keep = v) } }

                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val last = ui.lastBackupAt?.let { "Last backup or check: ${Backups.formatTime(it).format(WHEN)}" }
                        ?: "No backups yet"
                    Text(last, style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
                    ui.lastError?.let {
                        Text(
                            "The last backup failed: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (s.enabled && ui.folderName == null) {
                        Text(
                            "Backups are turned on but won't be made until you choose a folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = onBackUpNow, enabled = ui.folderName != null && !ui.busy) { Text("Back up now") }
                }

                if (ui.backups.isNotEmpty()) {
                    Heading("Backups in the folder (${ui.backups.size})")
                    ui.backups.asReversed().forEach { b ->
                        Text(
                            b.time?.format(WHEN) ?: b.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        "To restore one, copy it over the journal with a file manager, or open it here with Open journal file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = lc.mutedInk,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
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
}

private fun keepLabel(n: Int) = if (n == 0) "All" else "$n"

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    detail: String,
    value: String,
    choices: List<Pair<String, T>>,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
        }
        Box {
            TextButton(onClick = { open = true }) { Text(value) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { (label, v) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(v) })
                }
            }
        }
    }
}

/**
 * How different the branch colours are: towards Subtle they're neighbouring, softer colours; towards Stark
 * they're spread round the whole colour wheel and vivid. Colours move smoothly as it's dragged.
 */
@Composable
private fun ContrastSlider(value: Float, onChange: (Float) -> Unit) {
    val lc = LocalLedgerColors.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Colour contrast", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How different the colours are from each other",
            style = MaterialTheme.typography.bodySmall,
            color = lc.mutedInk,
        )
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Subtle", style = MaterialTheme.typography.labelMedium, color = lc.mutedInk)
            Text("Stark", style = MaterialTheme.typography.labelMedium, color = lc.mutedInk)
        }
    }
}

/** How much darker sub-levels' colours get, with the five shades of one branch as an example. */
@Composable
private fun ShadingSlider(value: Float, exampleHue: Float, contrast: Float, onChange: (Float) -> Unit) {
    val lc = LocalLedgerColors.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Sub-level shading", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How much darker each level below a top-level account gets",
            style = MaterialTheme.typography.bodySmall,
            color = lc.mutedInk,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (depth in 0..4) {
                Swatch(androidx.compose.ui.graphics.Color(BranchColors.argb(exampleHue, lc.dark, depth, contrast, value)), 24.dp)
            }
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Same as top level", style = MaterialTheme.typography.labelMedium, color = lc.mutedInk)
            Text("Much darker", style = MaterialTheme.typography.labelMedium, color = lc.mutedInk)
        }
    }
}
