package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.Period
import de.bookmarksplus.data.SortField
import de.bookmarksplus.desktop.AppState
import de.bookmarksplus.desktop.ViewMode

@Composable
fun TopBar(state: AppState, searchFocus: FocusRequester, onImport: () -> Unit, onExport: () -> Unit) {
    val f = state.filter
    val lib = state.library
    val saved = state.savedSearches.firstOrNull { it.filter == f }
    val title = saved?.name ?: f.folderId?.let { lib.folders[it]?.title } ?: when {
        f.onlyRepos -> tr("nav.repos")
        f.onlyDead -> tr("nav.deadLinks")
        f.onlySuggested -> tr("nav.aiSuggestions")
        f.trash -> tr("nav.trash")
        f.onlyDuplicates -> tr("nav.duplicates")
        f.onlyRedirects -> tr("nav.redirects")
        f.onlyBlocked -> tr("nav.blocked")
        f.onlyNoThumbnail -> tr("nav.noThumb")
        else -> tr("nav.all")
    }
    val path = f.folderId?.let { lib.path(it).dropLast(1).joinToString(" / ") { p -> p.title } }.orEmpty()

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 1100.dp
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.width(if (compact) 170.dp else 220.dp)) {
            if (path.isNotEmpty()) Muted(path)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.results.size}", color = Palette.TextFaint, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        SearchField(state, searchFocus, Modifier.weight(1f))
        if (f.isNarrowing && saved == null && !f.trash) IconButton(onClick = {
            state.nameDialog = de.bookmarksplus.desktop.NameRequest(tr("search.save"), f.text.trim(), tr("common.save")) { state.saveSearch(it) }
        }) { Icon(Icons.Filled.BookmarkAdd, tr("search.save"), tint = Palette.TextMuted) }
        IconButton(onClick = { state.startAdd() }) { Icon(Icons.Filled.Add, tr("top.newBookmarkTip"), tint = Palette.TextMuted) }
        ViewSwitcher(state, compact)
        SortMenu(state, compact)
        // switch display language of the AI contents
        val lang = state.contentLang
        Text(
            lang.code.uppercase(), fontSize = 12.sp, color = Palette.TextMuted,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(6.dp))
                .clickable { state.changeContentLang(if (lang == de.bookmarksplus.data.ContentLang.DE) de.bookmarksplus.data.ContentLang.EN else de.bookmarksplus.data.ContentLang.DE) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (state.syncTarget != de.bookmarksplus.desktop.SyncTarget.None) {
            IconButton(onClick = { state.syncNow() }, enabled = !state.syncRunning) {
                Icon(
                    if (state.syncRunning) Icons.Filled.CloudSync else if (state.syncFailed) Icons.Filled.CloudOff else Icons.Filled.CloudDone,
                    state.syncStatus ?: tr("sync.now"),
                    tint = if (state.syncRunning) MaterialTheme.colorScheme.primary else Palette.TextMuted,
                )
            }
        }
        IconButton(onClick = { state.showSettings = true }) { Icon(Icons.Filled.Settings, tr("settings.title"), tint = Palette.TextMuted) }
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, tr("top.more"), tint = Palette.TextMuted) }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(tr("top.newBookmarkMenu")) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { more = false; state.startAdd() })
                HorizontalDivider(color = Palette.Outline)
                DropdownMenuItem(text = { Text(tr("maint.importHtml")) }, leadingIcon = { Icon(Icons.Filled.FileDownload, null) }, onClick = { more = false; onImport() })
                DropdownMenuItem(text = { Text(tr("maint.exportHtml")) }, leadingIcon = { Icon(Icons.Filled.FileUpload, null) }, onClick = { more = false; onExport() })
                HorizontalDivider(color = Palette.Outline)
                DropdownMenuItem(
                    text = { Text(if (f.folderId != null) tr("top.newFolderIn", title) else tr("top.newFolder")) },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    onClick = { more = false; state.newFolder(f.folderId) },
                )
                if (lib.trash.isNotEmpty()) DropdownMenuItem(
                    text = { Text(tr("maint.emptyTrash", lib.trash.size)) },
                    leadingIcon = { Icon(Icons.Filled.DeleteForever, null) },
                    onClick = { more = false; state.emptyTrash() },
                )
                HorizontalDivider(color = Palette.Outline)
                if (state.linkProgress.running) {
                    DropdownMenuItem(text = { Text(tr("maint.stopLinkCheck")) }, leadingIcon = { Icon(Icons.Filled.Stop, null) }, onClick = { more = false; state.stopLinkCheck() })
                } else {
                    DropdownMenuItem(text = { Text(tr("maint.checkAll", lib.items.size)) }, leadingIcon = { Icon(Icons.Filled.LinkOff, null) }, onClick = { more = false; state.checkLinks() })
                    DropdownMenuItem(text = { Text(tr("top.checkShown", state.results.size)) }, leadingIcon = { Icon(Icons.Filled.LinkOff, null) }, onClick = { more = false; state.checkLinks(state.results.map { it.id }) })
                }
                if (lib.duplicateGroups.isNotEmpty()) DropdownMenuItem(
                    text = { Text(tr("top.mergeAllDupes", lib.duplicateGroups.size)) },
                    leadingIcon = { Icon(Icons.Filled.MergeType, null) },
                    onClick = { more = false; state.mergeDuplicates() },
                )
                HorizontalDivider(color = Palette.Outline)
                if (state.enrichProgress.running) {
                    DropdownMenuItem(text = { Text(tr("top.stopEnrich")) }, leadingIcon = { Icon(Icons.Filled.Stop, null) }, onClick = { more = false; state.stopEnrich() })
                } else {
                    DropdownMenuItem(
                        text = { Text(tr("top.enrichMissing", state.pendingCount)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                        enabled = state.pendingCount > 0,
                        onClick = { more = false; state.enrichAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("top.reloadShown", state.results.size)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { more = false; state.enrich(state.results.map { it.id }) },
                    )
                }
                HorizontalDivider(color = Palette.Outline)
                if (state.aiProgress.running) {
                    DropdownMenuItem(text = { Text(tr("top.stopAi")) }, leadingIcon = { Icon(Icons.Filled.Stop, null) }, onClick = { more = false; state.stopAi() })
                } else {
                    DropdownMenuItem(
                        text = { Text(tr("top.aiAll", state.aiPendingCount)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                        enabled = state.aiPendingCount > 0,
                        onClick = { more = false; state.aiAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("top.aiShown", state.results.size)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                        onClick = { more = false; state.aiFor(state.results.map { it.id }) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SearchField(state: AppState, focus: FocusRequester, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = Palette.TextFaint)
        Spacer(Modifier.width(8.dp))
        PlainField(
            value = state.filter.text,
            onValueChange = { state.filter = state.filter.copy(text = it) },
            placeholder = tr("top.searchHint"),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (state.filter.text.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, tr("common.clear"),
                Modifier.size(16.dp).clip(RoundedCornerShape(50)).clickable { state.filter = state.filter.copy(text = "") },
                tint = Palette.TextMuted,
            )
        } else {
            Text(tr("top.ctrlF"), fontSize = 11.sp, color = Palette.TextFaint, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ViewSwitcher(state: AppState, compact: Boolean) {
    Row(
        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val icons: Map<ViewMode, ImageVector> = mapOf(
            ViewMode.List to Icons.AutoMirrored.Filled.ViewList,
            ViewMode.Tree to Icons.Filled.AccountTree,
            ViewMode.Cards to Icons.Filled.GridView,
        )
        ViewMode.entries.forEach { m ->
            val sel = state.viewMode == m
            Row(
                Modifier.height(30.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { state.changeView(m) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icons.getValue(m), m.label, Modifier.size(16.dp), tint = if (sel) Color.White else Palette.TextMuted)
                if (!compact) {
                    Spacer(Modifier.width(6.dp))
                    Text(m.label, fontSize = 12.sp, color = if (sel) Color.White else Palette.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun SortMenu(state: AppState, compact: Boolean) {
    var open by remember { mutableStateOf(false) }
    val f = state.filter
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp), tint = Palette.TextMuted)
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Text(f.sort.label, color = Palette.Text, fontSize = 13.sp)
            }
            Spacer(Modifier.width(4.dp))
            Icon(if (f.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null, Modifier.size(14.dp), tint = Palette.TextMuted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, color = if (s == f.sort) MaterialTheme.colorScheme.primary else Palette.Text) },
                    onClick = { state.changeSort(s, if (s == f.sort) !f.descending else s != SortField.Title && s != SortField.Domain && s != SortField.Manual); open = false },
                    trailingIcon = if (s == f.sort) ({ Icon(if (f.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null, Modifier.size(14.dp)) }) else null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Filter bar

@Composable
fun FilterBar(state: AppState) {
    val f = state.filter
    val lib = state.library
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleChip("Repos", f.onlyRepos, Icons.Filled.Code) { state.filter = f.copy(onlyRepos = !f.onlyRepos) }
        PeriodChip(tr("sort.added"), f.added) { state.filter = state.filter.copy(added = it) }
        PeriodChip(tr("sort.modified"), f.updated) { state.filter = state.filter.copy(updated = it) }
        MenuChip(
            label = "Stars", value = f.minStars?.let { "≥ ${formatStars(it)}" },
            options = listOf<Pair<String, Long?>>(tr("period.any") to null, "≥ 10" to 10, "≥ 100" to 100, "≥ 1k" to 1000, "≥ 10k" to 10_000, "≥ 50k" to 50_000),
            onPick = { state.filter = state.filter.copy(minStars = it) },
        )
        MenuChip(
            label = "Domain", value = f.domain,
            options = listOf<Pair<String, String?>>(tr("common.all") to null) + lib.domains.take(40).map { (d, n) -> "$d  ($n)" to d },
            onPick = { state.filter = state.filter.copy(domain = it) },
        )
        MenuChip(
            label = "Tags", value = if (f.tagIds.isEmpty()) null else "${f.tagIds.size}",
            options = if (lib.tags.isEmpty()) listOf<Pair<String, Long?>>(tr("top.noTags") to null)
            else lib.tags.map { (if (it.id in f.tagIds) "✓ " else "    ") + it.label to it.id },
            onPick = { id -> if (id != null) state.filter = state.filter.copy(tagIds = state.filter.tagIds.let { if (id in it) it - id else it + id }) },
        )
        f.tagIds.mapNotNull { lib.tagsById[it] }.forEach { t ->
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { state.filter = state.filter.copy(tagIds = state.filter.tagIds - t.id) }) {
                TagChip(t) { Icon(Icons.Filled.Close, null, Modifier.size(12.dp), tint = Palette.TextMuted) }
            }
        }
        if (f.isNarrowing) {
            TextButton(onClick = { state.resetFilters() }) { Text(tr("common.reset"), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ChipBox(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Palette.Surface)
            .border(1.dp, if (active) accent.copy(alpha = 0.6f) else Palette.Outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, icon: ImageVector, onClick: () -> Unit) {
    ChipBox(active, onClick) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (active) MaterialTheme.colorScheme.primary else Palette.TextMuted)
        Text(label, fontSize = 12.sp, color = Palette.Text)
    }
}

@Composable
private fun PeriodChip(label: String, value: Period, onPick: (Period) -> Unit) {
    MenuChip(label, value.takeIf { it != Period.Any }?.label, Period.entries.map { it.label to it }, onPick = { onPick(it ?: Period.Any) })
}

@Composable
private fun <T> MenuChip(label: String, value: String?, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChipBox(value != null, onClick = { open = true }) {
            Text(if (value == null) label else "$label: $value", fontSize = 12.sp, color = Palette.Text, maxLines = 1)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp), tint = Palette.TextMuted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, (text, v) ->
                if (i == 1 && options.size > 6) HorizontalDivider(color = Palette.Outline)
                DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { onPick(v); open = false })
            }
        }
    }
}
