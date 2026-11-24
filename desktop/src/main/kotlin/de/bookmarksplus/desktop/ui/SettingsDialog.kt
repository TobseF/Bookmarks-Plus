package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.TagRepository
import de.bookmarksplus.desktop.AppState

private enum class SettingsTab {
    Look, Ai, Enrich, Sync, Browser, Tags, Maintenance;

    val label: String get() = tr("settings.tab.${name.lowercase()}")
}

/** Most recently opened tab (for the lifetime of the app). */
private var lastTab = SettingsTab.Look

@Composable
fun SettingsDialog(state: AppState, onImport: () -> Unit = {}, onExport: () -> Unit = {}) {
    var tab by remember { mutableStateOf(lastTab) }
    AlertDialog(
        onDismissRequest = { state.showSettings = false },
        confirmButton = { TextButton(onClick = { state.showSettings = false }) { Text(tr("common.done")) } },
        title = { Text(tr("settings.title")) },
        containerColor = Palette.SurfaceHigh,
        text = {
            Column(Modifier.width(580.dp)) {
                Row(Modifier.fillMaxWidth().border(1.dp, Palette.Outline, RoundedCornerShape(10.dp)).padding(3.dp)) {
                    SettingsTab.entries.forEach { t ->
                        val sel = t == tab
                        Box(
                            Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (sel) Palette.Surface else Color.Transparent)
                                .clickable { tab = t; lastTab = t },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t.label, fontSize = 12.sp, color = if (sel) Palette.Text else Palette.TextMuted, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val scroll = remember(tab) { androidx.compose.foundation.ScrollState(0) }
                Column(Modifier.fillMaxWidth().height(520.dp).verticalScroll(scroll)) {
                    when (tab) {
                        SettingsTab.Look -> LookSettings(state)
                        SettingsTab.Ai -> AiSettings(state)
                        SettingsTab.Enrich -> EnrichSettings(state)
                        SettingsTab.Sync -> SyncSettings(state)
                        SettingsTab.Browser -> ExtensionSettings(state)
                        SettingsTab.Tags -> TagSettings(state)
                        SettingsTab.Maintenance -> MaintenanceSettings(state, onImport, onExport)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // the dialog covers the toast of the main window, so show it here as well
                Toast(state, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookSettings(state: AppState) {
    var hex by remember { mutableStateOf(String.format("#%06X", state.accent.toArgb() and 0xFFFFFF)) }
    Spacer(Modifier.height(16.dp))
    Text(tr("settings.languageHeader"), style = SectionLabel)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        de.bookmarksplus.i18n.UiLang.entries.forEach { l -> Choice(l.label, state.uiLang == l) { state.changeUiLang(l) } }
    }
    Spacer(Modifier.height(20.dp))
    Text(tr("settings.accentHeader"), style = SectionLabel)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ACCENTS.forEach { p ->
            val sel = p.color.toArgb() == state.accent.toArgb()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(p.color)
                        .border(2.dp, if (sel) Color.White else Color.Transparent, CircleShape)
                        .clickable { state.changeAccent(p.color); hex = String.format("#%06X", p.color.toArgb() and 0xFFFFFF) },
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.height(4.dp))
                Text(p.name, fontSize = 11.sp, color = Palette.TextMuted)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = hex,
        onValueChange = {
            hex = it
            it.removePrefix("#").takeIf { s -> s.length == 6 }?.toLongOrNull(16)
                ?.let { v -> state.changeAccent(Color(0xFF000000 or v)) }
        },
        label = { Text(tr("settings.customColor")) },
        singleLine = true,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            hint?.let { Text(it, fontSize = 11.sp, color = Palette.TextFaint) }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EnrichSettings(state: AppState) {
    Spacer(Modifier.height(16.dp))
    Text("GITHUB", style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    var token by remember { mutableStateOf(state.githubToken) }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it; state.changeGithubToken(it) },
        label = { Text(tr("settings.ghToken")) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        supportingText = { Text(tr("settings.ghTokenHint"), fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Text(tr("settings.ghPreviewHeader"), style = SectionLabel)
    Spacer(Modifier.height(6.dp))
    Text(
        tr("settings.readmeCardHint1") +
            tr("settings.readmeCardHint2"),
        fontSize = 11.sp, color = Palette.TextFaint, lineHeight = 15.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(tr("settings.readmeLines"), fontSize = 13.sp, modifier = Modifier.weight(1f))
        listOf(30, 60, 120, 250).forEach { n -> Choice(n.toString(), state.readmeImageLines == n) { state.changeReadmeImage(lines = n) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(tr("settings.minImageHeight"), fontSize = 13.sp, modifier = Modifier.weight(1f))
        listOf(0 to tr("common.off"), 120 to "120 px", 200 to "200 px", 300 to "300 px").forEach { (h, l) -> Choice(l, state.readmeImageMinHeight == h) { state.changeReadmeImage(minHeight = h) } }
    }
    Spacer(Modifier.height(16.dp))
    Text("SCREENSHOTS", style = SectionLabel)
    Spacer(Modifier.height(6.dp))
    SwitchRow(tr("settings.screenshots"), state.thumbnails) { state.changeThumbnails(it) }
    SwitchRow(tr("settings.screenshotsDark"), state.darkScreenshots, "prefers-color-scheme: dark") { state.changeDarkScreenshots(it) }
    SwitchRow(tr("settings.realBrowser"), state.realBrowser) { state.changeRealBrowser(it) }
    if (state.realBrowser) SwitchRow(tr("settings.showBrowser"), state.visibleBrowser) { state.changeVisibleBrowser(it) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(tr("settings.parallelTabs"), fontSize = 13.sp, modifier = Modifier.weight(1f))
        listOf(1, 2, 3, 4).forEach { n -> Choice(n.toString(), state.browserWorkers == n) { state.changeBrowserWorkers(n) } }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        tr("settings.screenshotHint1") +
            tr("settings.screenshotHint2"),
        fontSize = 11.sp, color = Palette.TextFaint, lineHeight = 15.sp,
    )
    Spacer(Modifier.height(16.dp))
    Text(tr("settings.appsHeader"), style = SectionLabel)
    Spacer(Modifier.height(6.dp))
    SwitchRow(tr("settings.flathub"), state.appFlathub, tr("settings.flathubHint")) { state.changeAppSettings(flathub = it) }
    SwitchRow(tr("settings.alternativeTo"), state.appAlternativeTo, tr("settings.alternativeToHint")) { state.changeAppSettings(alternativeTo = it) }
    if (state.appAlternativeTo) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(tr("settings.minInterval"), fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(10, 20, 45, 90).forEach { n -> Choice("$n s", state.appAtInterval == n) { state.changeAppSettings(interval = n) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(tr("settings.maxPerDay"), fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(100, 400, 1000).forEach { n -> Choice(n.toString(), state.appAtDailyLimit == n) { state.changeAppSettings(daily = n) } }
        }
        val ext = state.extensionBridge.available
        SwitchRow(
            tr("settings.viaExtension"), state.appViaExtension,
            if (ext) tr("settings.extensionConnected") else tr("settings.noExtension"),
        ) { state.changeAppSettings(viaExtension = it) }
        SwitchRow(tr("settings.viaOwnBrowser"), state.appViaBrowser, tr("settings.ownBrowserHint")) { state.changeAppSettings(viaBrowser = it) }
        Text(
            tr("settings.atHint1") +
                tr("settings.atHint2"),
            fontSize = 11.sp, color = Palette.TextFaint, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(tr("settings.secretsHint"), fontSize = 11.sp, color = Palette.TextFaint)
}

/** Tags: filter, rename (click on the name), change color, delete. */
@Composable
private fun TagSettings(state: AppState) {
    val lib = state.library
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var sortByUsage by remember { mutableStateOf(true) }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, placeholder = { Text(tr("tags.filter")) }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Choice(tr("tags.frequency"), sortByUsage) { sortByUsage = true }
        Choice("A–Z", !sortByUsage) { sortByUsage = false }
    }
    Spacer(Modifier.height(4.dp))
    Text(tr("tags.countHint", lib.tags.size), fontSize = 11.sp, color = Palette.TextFaint)
    Spacer(Modifier.height(8.dp))
    val q = query.trim().removePrefix("#")
    val shown = lib.tags
        .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true) }
        .let { l ->
            if (sortByUsage) l.sortedWith(compareByDescending<de.bookmarksplus.data.TagInfo> { it.usage }.thenBy { it.name.lowercase() })
            else l.sortedBy { it.name.lowercase() }
        }
    if (shown.isEmpty()) Text(if (lib.tags.isEmpty()) tr("tags.none") else tr("tags.noMatch"), fontSize = 12.sp, color = Palette.TextMuted)
    shown.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
            Box(Modifier.width(210.dp)) {
                if (editing == t.id) {
                    TagNameField(t.name, onDone = { state.renameTag(t.id, it); editing = null }, onCancel = { editing = null })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(50)).clickable { editing = t.id }) { TagChip(t) }
                        Spacer(Modifier.width(6.dp))
                        Text(t.usage.toString(), fontSize = 11.sp, color = Palette.TextFaint)
                    }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TagRepository.PALETTE.forEach { c ->
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(Color(c.toInt()))
                            .border(1.5.dp, if (c == t.color) Color.White else Color.Transparent, CircleShape)
                            .clickable { state.recolorTag(t.id, c) },
                    )
                }
            }
            IconButton(onClick = { editing = t.id }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Edit, tr("common.rename"), Modifier.size(15.dp), tint = Palette.TextMuted)
            }
            IconButton(onClick = { state.deleteTag(t.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Delete, tr("tags.delete"), Modifier.size(16.dp), tint = Palette.TextMuted)
            }
        }
    }
}

@Composable
private fun TagNameField(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PlainField(
        value = name, onValueChange = { name = it }, fontSize = 12.sp,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(name.text) }),
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Surface)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onCancel(); true } else false },
    )
}

/** Maintenance: import/export, https conversion, check links, duplicates, trash. */
@Composable
private fun MaintenanceSettings(state: AppState, onImport: () -> Unit, onExport: () -> Unit) {
    val lib = state.library
    Spacer(Modifier.height(16.dp))
    Text(tr("settings.importExportHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    Text(tr("maint.htmlHint"), fontSize = 12.sp, color = Palette.TextMuted)
    Spacer(Modifier.height(8.dp))
    Row {
        Choice(tr("maint.importHtml"), false, onImport)
        Choice(tr("maint.exportHtml"), false, onExport)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        tr("maint.encryptedHint1") +
            tr("maint.encryptedHint2"),
        fontSize = 12.sp, color = Palette.TextMuted, lineHeight = 16.sp,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice(tr("maint.exportFile"), false) { de.bookmarksplus.desktop.Pickers.saveFile(tr("export.encrypted"), "bookmarks.bmpx")?.let { state.exportEncrypted(it, folder = false) } }
        Choice(tr("maint.exportFiles"), false) { de.bookmarksplus.desktop.Pickers.folder(tr("maint.chooseExportFolder"))?.let { state.exportEncrypted(it, folder = true) } }
        Choice(tr("maint.importEncrypted"), false) { de.bookmarksplus.desktop.Pickers.encryptedSource()?.let { state.importEncrypted(it) } }
    }

    Spacer(Modifier.height(22.dp))
    Text(tr("maint.httpsHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    val http = remember(lib) { state.httpItems() }
    var confirm by remember(lib) { mutableStateOf(false) }
    Text(
        if (http.isEmpty()) tr("maint.httpsAllDone")
        else tr("maint.httpsCount", http.size) +
            tr("maint.httpsHint"),
        fontSize = 12.sp, color = Palette.TextMuted, lineHeight = 16.sp,
    )
    if (http.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!confirm) {
                Choice(tr("maint.httpsSwitch", http.size), false) { confirm = true }
            } else {
                Choice(tr("maint.httpsConfirm", http.size), true) { confirm = false; state.upgradeToHttps() }
                Choice(tr("common.cancel"), false) { confirm = false }
            }
        }
        if (confirm) Text(
            http.take(3).joinToString("\n") { it.url } + if (http.size > 3) "\n…" else "",
            fontSize = 11.sp, color = Palette.TextFaint, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }

    Spacer(Modifier.height(22.dp))
    Text(tr("maint.checkHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    Row {
        if (state.linkProgress.running) Choice(tr("maint.stopLinkCheck"), false) { state.stopLinkCheck() }
        else Choice(tr("maint.checkAll", lib.items.count { !it.linkIgnore }), false) { state.checkLinks() }
        if (lib.duplicateGroups.isNotEmpty()) Choice(tr("maint.mergeDupes", lib.duplicateGroups.size), false) { state.mergeDuplicates() }
    }
    // GitHub projects (repo bookmarks and project pages with repo) without README
    val noReadme = remember(lib) {
        lib.items.count { !it.hasReadme && (it.repoUrl?.startsWith("https://github.com/", true) == true || it.domain == "github.com") }
    }
    if (noReadme > 0) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.enrichProgress.running) Text(tr("maint.running", state.enrichProgress.done, state.enrichProgress.total), fontSize = 12.sp, color = Palette.TextMuted)
            else Choice(tr("maint.loadReadmes", noReadme), false) { state.loadMissingReadmes() }
        }
    }
    // GitHub avatars and icons of the project websites
    val noIcons = remember(lib) {
        lib.items.count { it.isRepo && (it.avatarHash == null || (it.isGithubRepo && it.websiteUrl != null && it.websiteIconHash == null)) }
    }
    if (noIcons > 0 && !state.enrichProgress.running) {
        Spacer(Modifier.height(6.dp))
        Row { Choice(tr("maint.loadIcons", noIcons), false) { state.loadMissingIcons() } }
    }
    if (lib.trash.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Row { Choice(tr("maint.emptyTrash", lib.trash.size), false) { state.emptyTrash() } }
    }

    Spacer(Modifier.height(22.dp))
    Text(tr("settings.appsHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    val p = state.appProgress
    Text(
        tr("maint.appsHint1") +
            tr("maint.appsHint2") +
            tr("maint.appsHint3", state.appCount),
        fontSize = 12.sp, color = Palette.TextMuted, lineHeight = 16.sp,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (p.running) {
            Choice(tr("common.stop"), false) { state.stopApps() }
            Text(tr("maint.phaseProgress", p.phase, p.done, p.total) + (p.current?.let { " · $it" } ?: ""), fontSize = 12.sp, color = Palette.TextMuted,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        } else {
            Choice(tr("batch.appData"), false) { state.updateApps() }
            Choice(tr("maint.appsAll"), false) { state.updateApps(force = true) }
        }
    }
    if (p.running || p.named + p.flathub + p.alternativeTo > 0) Text(
        listOfNotNull(
            "${p.named} Namen", "${p.flathub} × Flathub", "${p.alternativeTo} × AlternativeTo",
            p.alternativeToVia?.let { tr("maint.via", it) },
            p.alternativeToPausedUntil?.takeIf { it > System.currentTimeMillis() }?.let {
                tr("maint.atPausedUntil") + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()))
            },
            p.cost.takeIf { it > 0 }?.let { "KI ≈ $" + "%.3f".format(it) },
            p.lastError?.let { tr("common.errorWith", it) },
        ).joinToString(" · "),
        fontSize = 11.sp, color = Palette.TextFaint, modifier = Modifier.padding(top = 6.dp),
    )

    Spacer(Modifier.height(22.dp))
    Text(tr("settings.ghPreviewsHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    val repos = remember(lib) { lib.items.count { it.domain == "github.com" && it.hasReadme } }
    Text(
        tr("maint.cardsHint1", repos) +
            tr("maint.cardsHint2"),
        fontSize = 12.sp, color = Palette.TextMuted, lineHeight = 16.sp,
    )
    Spacer(Modifier.height(8.dp))
    Row {
        if (state.enrichProgress.running) Text(tr("maint.running", state.enrichProgress.done, state.enrichProgress.total), fontSize = 12.sp, color = Palette.TextMuted)
        else Choice(tr("maint.cardsRebuild", repos), false) { state.rerenderGithubCards() }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 12.sp, color = if (selected) Color.White else Palette.Text) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSettings(state: AppState) {
    Spacer(Modifier.height(16.dp))
    Text("KI", style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        de.bookmarksplus.enrich.ai.AiProvider.entries.forEach { p ->
            Choice(p.label, state.aiProvider == p) { state.changeAiProvider(p) }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (state.aiProvider == de.bookmarksplus.enrich.ai.AiProvider.Custom) {
        var base by remember { mutableStateOf(state.aiBaseUrl) }
        OutlinedTextField(
            value = base, onValueChange = { base = it; state.changeAiBaseUrl(it) },
            label = { Text(tr("ai.baseUrl")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
    var key by remember(state.aiProvider) { mutableStateOf(state.aiKey) }
    OutlinedTextField(
        value = key, onValueChange = { key = it; state.changeAiKey(it) },
        label = { Text("API-Key ${state.aiProvider.label}") }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        supportingText = state.aiProvider.keyUrl?.let { u -> { Text(tr("ai.createKey", u), fontSize = 11.sp) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    ModelField(state, tr("ai.textModel"), state.aiModel) { state.changeAiModel(it) }
    Spacer(Modifier.height(6.dp))
    ModelField(state, tr("ai.tagModel"), state.aiTagModel) { state.changeAiTagModel(it) }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Choice(tr("ai.loadModels"), false) { state.loadAiModels() }
        Choice(tr("common.testConnection"), false) { state.testAi() }
    }
    state.aiTestMessage?.let { Text(it, fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 6.dp)) }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("ai.contentLang"), fontSize = 13.sp)
            Text(tr("ai.contentLangHint"), fontSize = 11.sp, color = Palette.TextFaint)
        }
        de.bookmarksplus.data.ContentLang.entries.forEach { l -> Choice(l.label, state.contentLang == l) { state.changeContentLang(l) } }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("ai.parallel"), fontSize = 13.sp, modifier = Modifier.weight(1f))
        listOf(1, 2, 4, 8).forEach { n -> Choice(n.toString(), state.aiParallel == n) { state.changeAiParallel(n) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("ai.autoTags"), fontSize = 13.sp)
            Text(tr("ai.autoTagsHint"), fontSize = 11.sp, color = Palette.TextFaint)
        }
        androidx.compose.material3.Switch(checked = state.aiAutoTags, onCheckedChange = { state.changeAiAutoTags(it) })
    }
    val pending = state.aiPendingCount.toLong()
    if (pending > 0) {
        val svc = de.bookmarksplus.enrich.ai.AiService
        val tokens = pending * (svc.TEXT_IN + svc.TEXT_OUT + svc.TAG_IN + svc.TAG_OUT)
        val cost = svc.estimateCost(pending, state.aiModels.firstOrNull { it.id == state.aiModel }, state.aiModels.firstOrNull { it.id == state.aiTagModel })
        Text(
            tr("ai.estimate", pending).format(tokens / 1e6) +
                (cost?.let { " ≈ $%.2f".format(it) } ?: tr("ai.estimatePriceHint")),
            fontSize = 11.sp, color = Palette.TextFaint, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SyncSettings(state: AppState) {
    val target = state.syncTarget
    Spacer(Modifier.height(16.dp))
    Text(tr("settings.syncHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        de.bookmarksplus.desktop.SyncTarget.entries.forEach { t -> Choice(t.label, target == t) { state.changeSyncTarget(t) } }
    }
    Spacer(Modifier.height(8.dp))
    val pw = androidx.compose.ui.text.input.PasswordVisualTransformation()
    when (target) {
        de.bookmarksplus.desktop.SyncTarget.Server -> {
            var url by remember { mutableStateOf(state.syncServerUrl) }
            var user by remember { mutableStateOf(state.syncServerUser) }
            var pass by remember { mutableStateOf(state.syncServerPassword) }
            OutlinedTextField(url, { url = it; state.changeSyncServer(url = it) }, label = { Text(tr("sync.serverUrl")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it; state.changeSyncServer(user = it) }, label = { Text(tr("field.user")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it; state.changeSyncServer(password = it) }, label = { Text(tr("field.password")) }, singleLine = true, visualTransformation = pw, modifier = Modifier.weight(1f))
            }
        }
        de.bookmarksplus.desktop.SyncTarget.Ftp -> {
            var host by remember { mutableStateOf(state.ftpHost) }
            var port by remember { mutableStateOf(state.ftpPort) }
            var user by remember { mutableStateOf(state.ftpUser) }
            var pass by remember { mutableStateOf(state.ftpPassword) }
            var dir by remember { mutableStateOf(state.ftpDir) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it; state.changeFtp(host = it) }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(port, { port = it; state.changeFtp(port = it) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(92.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it; state.changeFtp(user = it) }, label = { Text(tr("field.user")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(pass, { pass = it; state.changeFtp(password = it) }, label = { Text(tr("field.password")) }, singleLine = true, visualTransformation = pw, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(dir, { dir = it; state.changeFtp(dir = it) }, label = { Text(tr("sync.ftpFolder")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("sync.ftps"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = state.ftpTls, onCheckedChange = { state.changeFtp(tls = it) })
            }
        }
        de.bookmarksplus.desktop.SyncTarget.Folder -> {
            var folder by remember { mutableStateOf(state.syncFolder) }
            OutlinedTextField(
                folder, { folder = it; state.changeSyncFolder(it) }, label = { Text(tr("sync.folderHint")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        de.bookmarksplus.desktop.SyncTarget.None -> {
            Text(tr("sync.noTargetHint"), fontSize = 12.sp, color = Palette.TextFaint)
        }
    }
    if (target != de.bookmarksplus.desktop.SyncTarget.None) {
        Spacer(Modifier.height(10.dp))
        if (state.syncKeyReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color(0xFF30A46C))
                Spacer(Modifier.width(6.dp))
                Text(tr("sync.keyReady"), fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        } else {
            var pass by remember { mutableStateOf("") }
            var pass2 by remember { mutableStateOf("") }
            Text(
                tr("sync.keyHint1") +
                    tr("sync.keyHint2"),
                fontSize = 12.sp, color = Palette.TextMuted, lineHeight = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = pw, modifier = Modifier.weight(1f))
                OutlinedTextField(pass2, { pass2 = it }, label = { Text(tr("passphrase.repeat")) }, singleLine = true, visualTransformation = pw, modifier = Modifier.weight(1f))
            }
            val ok = pass.length >= 10 && pass == pass2
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Choice(tr("sync.setupKey"), ok) { if (ok) state.setPassphrase(pass) }
                Text(
                    when {
                        pass.isEmpty() -> tr("passphrase.min10")
                        pass.length < 10 -> tr("passphrase.tooShort")
                        pass != pass2 -> tr("passphrase.mismatch")
                        else -> ""
                    },
                    fontSize = 11.sp, color = Palette.TextFaint,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("sync.auto"), fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(0 to tr("common.off"), 5 to "5 min", 15 to "15 min", 60 to "1 h").forEach { (m, l) -> Choice(l, state.syncInterval == m) { state.changeSyncInterval(m) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("sync.withThumbs"), fontSize = 13.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = state.syncMedia, onCheckedChange = { state.changeSyncMedia(it) })
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(tr("common.testConnection"), false) { state.testSyncTarget() }
            // the progress is shown as a task at the bottom left of the sidebar, so close the settings
            Choice(if (state.syncRunning) tr("common.running") else tr("sync.now"), false) { state.showSettings = false; state.syncNow() }
            Choice(tr("backup.now"), false) { state.backupNow() }
        }
    }
    state.syncStatus?.let { Text(it, fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 6.dp)) }

    // backups
    Spacer(Modifier.height(10.dp))
    androidx.compose.runtime.LaunchedEffect(target, state.syncKeyReady) { if (state.syncKeyReady) state.loadRemoteBackups() }
    val local = remember { state.localBackups() }
    if (state.remoteBackups.isNotEmpty() || local.isNotEmpty()) {
        Text(tr("backup.list"), fontSize = 13.sp)
        state.remoteBackups.take(5).forEach { name ->
            BackupRow("☁ " + prettyBackup(name.substringAfterLast('/'))) { state.restoreBackup(remoteName = name) }
        }
        local.take(7).forEach { f -> BackupRow("💾 " + prettyBackup(f.name)) { state.restoreBackup(local = f) } }
    }
}

private fun prettyBackup(name: String): String {
    val m = Regex("(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").find(name) ?: return name
    val (y, mo, d, h, mi) = m.destructured
    return "$d.$mo.$y $h:$mi"
}

@Composable
private fun BackupRow(label: String, onRestore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Text(label, fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.weight(1f))
        Text(
            tr("backup.restoreLower"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onRestore).padding(2.dp),
        )
    }
}

@Composable
private fun ExtensionSettings(state: AppState) {
    Spacer(Modifier.height(16.dp))
    Text(tr("settings.extensionHeader"), style = SectionLabel)
    Spacer(Modifier.height(8.dp))
    val port = state.apiPort
    Text(
        if (port != null) tr("api.ready", port)
        else tr("api.notStarted") + (state.apiError?.let { ": $it" } ?: ""),
        fontSize = 12.sp, color = Palette.TextMuted,
    )
    Text(
        tr("api.installHint"),
        fontSize = 11.sp, color = Palette.TextFaint, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
    )
    if (state.apiTokens.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(tr("api.connectedLabel"), fontSize = 13.sp)
        state.apiTokens.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    "${t.client} · seit ${java.time.Instant.ofEpochSecond(t.created).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                    fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.weight(1f),
                )
                Text(
                    tr("api.disconnect"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.revokeToken(t.hash) }.padding(2.dp),
                )
            }
        }
    }
}

/** Model name with selection list (after "Load models") incl. prices. */
@Composable
private fun ModelField(state: AppState, label: String, current: String, onChange: (String) -> Unit) {
    var model by remember(state.aiProvider, label) { mutableStateOf(current) }
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = model, onValueChange = { model = it; onChange(it); menu = state.aiModels.isNotEmpty() },
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        val matches = state.aiModels.filter { model.isBlank() || it.id.contains(model.trim(), ignoreCase = true) || it.name.contains(model.trim(), ignoreCase = true) }.take(40)
        androidx.compose.material3.DropdownMenu(menu && matches.isNotEmpty(), onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            matches.forEach { m ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.id, fontSize = 12.sp)
                            m.pricePerMillion?.let { pr ->
                                val out = m.completionPrice?.times(1_000_000)
                                Text(tr("ai.price").format(pr, out?.let { "$%.3f".format(it) } ?: "?"), fontSize = 10.sp, color = Palette.TextFaint)
                            }
                        }
                    },
                    onClick = { model = m.id; onChange(m.id); menu = false },
                )
            }
        }
    }
}
