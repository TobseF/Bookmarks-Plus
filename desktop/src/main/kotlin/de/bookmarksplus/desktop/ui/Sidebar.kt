package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.FolderInfo
import de.bookmarksplus.data.Library
import de.bookmarksplus.desktop.AppState

private sealed interface SideRow {
    data class Header(val title: String) : SideRow
    data object FolderHeader : SideRow
    data class Smart(val key: String, val title: String, val icon: ImageVector, val count: Int) : SideRow
    data class FolderRow(val folder: FolderInfo, val depth: Int, val hasChildren: Boolean, val count: Int) : SideRow
    data class TagRow(val id: Long) : SideRow
    data class TagHeader(val collapsed: Boolean, val byUsage: Boolean, val count: Int) : SideRow
    data class Saved(val search: de.bookmarksplus.data.SavedSearch) : SideRow
}

/** Filter of a library view (search text, tags and sorting remain). */
private fun smartFilter(key: String, f: de.bookmarksplus.data.Filter) = f.copy(
    folderId = null,
    onlyRepos = key == "repos", onlyDead = key == "dead", onlySuggested = key == "suggested", trash = key == "trash",
    onlyRedirects = key == "redirects", onlyDuplicates = key == "duplicates", onlyBlocked = key == "blocked", onlyNoThumbnail = key == "nothumb",
)

@Composable
fun Sidebar(state: AppState, modifier: Modifier = Modifier) {
    val lib = state.library
    val expanded = state.sidebarExpanded
    val inbox = remember(lib) { state.inboxFolderId(create = false) }
    val rows = remember(lib, expanded, state.savedSearches, inbox, state.tagsCollapsed, state.tagsByUsage) {
        buildRows(lib, expanded, inbox, state.savedSearches, state.tagsCollapsed, state.tagsByUsage)
    }
    val f = state.filter
    val accent = MaterialTheme.colorScheme.primary
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // folder to scroll to after expanding
    var scrollTarget by remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(scrollTarget, rows) {
        val target = scrollTarget ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it is SideRow.FolderRow && it.folder.id == target }
        if (idx >= 0) listState.animateScrollToItem((idx - 3).coerceAtLeast(0))
        scrollTarget = null
    }

    Column(modifier.fillMaxHeight().background(Palette.Sidebar)) {
        Row(Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Bookmarks, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
            Text("+", style = MaterialTheme.typography.titleMedium, color = accent)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            items(rows, key = { r ->
                when (r) {
                    is SideRow.Header -> "h:${r.title}"
                    is SideRow.FolderHeader -> "h:Ordner"
                    is SideRow.Smart -> "s:${r.key}"
                    is SideRow.Saved -> "q:${r.search.id}"
                    is SideRow.FolderRow -> "f:${r.folder.id}"
                    is SideRow.TagRow -> "t:${r.id}"
                    is SideRow.TagHeader -> "h:tags"
                }
            }) { r ->
                when (r) {
                    is SideRow.Header -> Text(r.title.uppercase(), style = SectionLabel, modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp))
                    is SideRow.FolderHeader -> Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("nav.foldersHeader"), style = SectionLabel, modifier = Modifier.weight(1f))
                        val target = state.selection.firstOrNull()?.let { lib.primaryFolder(it) }
                        HeaderIcon(Icons.Filled.MyLocation, tr("nav.jumpToFolder"), enabled = target != null) {
                            val folderId = target ?: return@HeaderIcon
                            val ancestors = generateSequence(lib.folders[folderId]?.parentId) { lib.folders[it]?.parentId }
                                .filter { it != lib.rootId }.toSet()
                            state.sidebarExpanded = state.sidebarExpanded + ancestors
                            state.filter = f.copy(folderId = folderId, trash = false)
                            scrollTarget = folderId
                        }
                        HeaderIcon(Icons.Filled.UnfoldMore, tr("nav.expandAll")) {
                            state.sidebarExpanded = lib.folders.keys.filterTo(HashSet()) { lib.childrenOf(it).isNotEmpty() }
                        }
                        HeaderIcon(Icons.Filled.UnfoldLess, tr("nav.collapseAll")) { state.sidebarExpanded = emptySet() }
                    }
                    is SideRow.TagHeader -> Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable { state.toggleTagsCollapsed() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (r.collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, if (r.collapsed) tr("nav.tagsExpand") else tr("nav.tagsCollapse"), Modifier.size(16.dp), tint = Palette.TextFaint)
                            Spacer(Modifier.width(2.dp))
                            Text("TAGS", style = SectionLabel)
                            if (r.collapsed) Text("  ${r.count}", fontSize = 11.sp, color = Palette.TextFaint)
                        }
                        Text(
                            if (r.byUsage) tr("tags.frequency") else "A–Z", fontSize = 11.sp, color = accent,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.toggleTagsByUsage() }.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    is SideRow.Smart -> {
                        val selected = if (r.key == "inbox") f.folderId == inbox && !f.trash else {
                            val sf = smartFilter(r.key, f)
                            f.folderId == null && f.onlyRepos == sf.onlyRepos && f.onlyDead == sf.onlyDead && f.onlySuggested == sf.onlySuggested &&
                                f.trash == sf.trash && f.onlyRedirects == sf.onlyRedirects && f.onlyDuplicates == sf.onlyDuplicates && f.onlyBlocked == sf.onlyBlocked && f.onlyNoThumbnail == sf.onlyNoThumbnail
                        }
                        val dropModifier = if (r.key == "inbox" && inbox != null) Modifier.dropTarget(state, inbox) else Modifier
                        Box(dropModifier) { SideItem(selected || (r.key == "inbox" && state.dropHover == inbox && inbox != null), depth = 0, onClick = {
                            state.filter = if (r.key == "inbox") smartFilter("all", f).copy(folderId = inbox) else smartFilter(r.key, f)
                            state.clearSelection()
                        }) {
                            Icon(r.icon, null, Modifier.size(16.dp), tint = if (selected) accent else Palette.TextMuted)
                            Label(r.title, r.count, selected)
                        } }
                    }
                    is SideRow.Saved -> {
                        val selected = f == r.search.filter
                        androidx.compose.foundation.ContextMenuArea(items = {
                            listOf(
                                androidx.compose.foundation.ContextMenuItem(tr("search.overwrite")) { state.updateSavedSearch(r.search.id) },
                                androidx.compose.foundation.ContextMenuItem(tr("common.renameMenu")) {
                                    state.nameDialog = de.bookmarksplus.desktop.NameRequest(tr("search.rename"), r.search.name, tr("common.rename")) { state.renameSavedSearch(r.search.id, it) }
                                },
                                androidx.compose.foundation.ContextMenuItem(tr("common.delete")) { state.deleteSavedSearch(r.search.id) },
                            )
                        }) {
                            SideItem(selected, depth = 0, onClick = { state.filter = r.search.filter; state.clearSelection() }) {
                                Icon(Icons.Filled.Search, null, Modifier.size(16.dp), tint = if (selected) accent else Palette.TextMuted)
                                val count = remember(lib, r.search.filter) { de.bookmarksplus.data.FilterEngine.apply(lib, r.search.filter, System.currentTimeMillis() / 1000).size }
                                Label(r.search.name, count, selected)
                            }
                        }
                    }
                    is SideRow.FolderRow -> Box(Modifier.dropTarget(state, r.folder.id)) { FolderContextMenu(state, r.folder.id) {
                        val selected = (f.folderId == r.folder.id && !f.trash) || state.dropHover == r.folder.id
                        val isOpen = r.folder.id in expanded
                        SideItem(selected, depth = r.depth, onClick = { state.filter = f.copy(folderId = r.folder.id, trash = false) }) {
                            Box(
                                Modifier.size(16.dp).clip(CircleShape).clickable(enabled = r.hasChildren) {
                                    state.sidebarExpanded = if (isOpen) expanded - r.folder.id else expanded + r.folder.id
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (r.hasChildren) Icon(
                                    if (isOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                                    Modifier.size(14.dp), tint = Palette.TextFaint,
                                )
                            }
                            Icon(
                                if (selected) Icons.Filled.Folder else Icons.Outlined.Folder, null,
                                Modifier.size(15.dp), tint = if (selected) accent else Palette.TextMuted,
                            )
                            Label(r.folder.title, r.count, selected)
                        }
                    } }
                    is SideRow.TagRow -> {
                        val tag = lib.tagsById[r.id] ?: return@items
                        val selected = tag.id in f.tagIds
                        SideItem(selected, depth = 0, onClick = {
                            state.filter = f.copy(tagIds = if (selected) f.tagIds - tag.id else f.tagIds + tag.id)
                        }) {
                            Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(Color(tag.color.toInt())))
                            Label(tag.label, tag.usage.toInt(), selected)
                        }
                    }
                }
            }
        }
        EnrichPanel(state)
        AiPanel(state)
        BatchPanel(state)
    }
}

/** Small icon action in the header of a list. */
@Composable
internal fun HeaderIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(16.dp), tint = if (enabled) Palette.TextMuted else Palette.TextFaint.copy(alpha = 0.4f))
    }
}

/** Start of the AI evaluation and notices (tokens, errors). */
@Composable
private fun AiPanel(state: AppState) {
    val p = state.aiProgress
    val pending = state.aiPendingCount
    // start buttons for open work only while no batch is running (progress is shown by the BatchPanel)
    val offer = pending > 0 && !state.batchRunning
    if (!offer && p.fatal == null) return
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (offer) {
            Text(tr("nav.aiPending", pending), fontSize = 12.sp, color = Palette.TextMuted)
            Row(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, accent, RoundedCornerShape(8.dp)).clickable { state.aiAll() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(tr("detail.aiAnalyseShort"), fontSize = 12.sp, color = accent)
            }
        }
        val tokens = p.promptTokens + p.completionTokens
        if (tokens > 0) Text(
            tr("nav.tokens").format(tokens).replace(',', '.') + (if (p.cost > 0) " · $%.3f".format(p.cost) else ""),
            fontSize = 11.sp, color = Palette.TextFaint,
        )
        if (p.errors > 0) Text("${p.errors} fehlgeschlagen" + (p.lastError?.let { " – $it" } ?: ""), fontSize = 11.sp, color = Palette.TextFaint, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
        p.fatal?.takeIf { !p.running }?.let { Text(it, fontSize = 11.sp, color = Palette.Star, lineHeight = 14.sp) }
    }
}

/** Start of enrichment and GitHub notices at the bottom of the sidebar. */
@Composable
private fun EnrichPanel(state: AppState) {
    val p = state.enrichProgress
    val pending = state.pendingCount
    val offer = pending > 0 && !state.batchRunning
    val paused = (p.githubPausedUntil ?: 0) > System.currentTimeMillis() / 1000
    if (!offer && !paused && !state.enricher.githubTokenRejected) return
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (offer) {
            Text(tr("nav.enrichPending", pending), fontSize = 12.sp, color = Palette.TextMuted)
            Row(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)).background(accent).clickable { state.enrichAll() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(tr("nav.enrichNow"), fontSize = 12.sp, color = Color.White)
            }
        }
        if (state.enricher.githubTokenRejected) Text(
            tr("nav.ghTokenRejected"),
            fontSize = 11.sp, color = Palette.Star, lineHeight = 14.sp,
        )
        p.githubPausedUntil?.let { until ->
            if (until > System.currentTimeMillis() / 1000) Text(
                tr("nav.ghLimitUntil") +
                    java.time.Instant.ofEpochSecond(until).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0) +
                    tr("nav.ghLimitHint"),
                fontSize = 11.sp, color = Palette.Star, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun SideItem(selected: Boolean, depth: Int, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val (src, hovered) = rememberHover()
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        hovered -> Palette.SurfaceHigh
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(bg).hover(src).clickable(onClick = onClick)
            .padding(start = (10 + depth * 14).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Label(title: String, count: Int, selected: Boolean) {
    Text(
        title, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Palette.Text,
    )
    if (count > 0) Text(count.toString(), fontSize = 11.sp, color = Palette.TextFaint)
}

private fun buildRows(lib: Library, expanded: Set<Long>, inbox: Long?, saved: List<de.bookmarksplus.data.SavedSearch>, tagsCollapsed: Boolean, tagsByUsage: Boolean): List<SideRow> {
    val rows = ArrayList<SideRow>()
    rows += SideRow.Header(tr("nav.library"))
    rows += SideRow.Smart("all", tr("nav.all"), Icons.Filled.Bookmarks, lib.items.size)
    if (inbox != null) rows += SideRow.Smart("inbox", "Inbox", Icons.Filled.Inbox, lib.recursiveCounts[inbox] ?: 0)
    rows += SideRow.Smart("repos", tr("nav.repos"), Icons.Filled.Code, lib.items.count { it.isRepo })
    val suggested = lib.items.count { lib.hasOpenSuggestion(it) }
    if (suggested > 0) rows += SideRow.Smart("suggested", tr("nav.aiSuggestions"), Icons.Filled.AutoAwesome, suggested)
    val dupes = lib.duplicateGroups.values.sumOf { it.size }
    if (dupes > 0) rows += SideRow.Smart("duplicates", tr("nav.duplicates"), Icons.Filled.ContentCopy, dupes)
    val dead = lib.items.count { it.isDead }
    if (dead > 0) rows += SideRow.Smart("dead", tr("nav.deadLinks"), Icons.Filled.LinkOff, dead)
    val redirects = lib.items.count { it.health == de.bookmarksplus.data.LinkHealth.Redirect }
    if (redirects > 0) rows += SideRow.Smart("redirects", tr("nav.redirects"), Icons.Filled.Shortcut, redirects)
    val blocked = lib.items.count { it.health == de.bookmarksplus.data.LinkHealth.Blocked || it.health == de.bookmarksplus.data.LinkHealth.Error }
    if (blocked > 0) rows += SideRow.Smart("blocked", tr("nav.blocked"), Icons.Filled.Block, blocked)
    val noThumb = lib.items.count { it.lacksThumbnail }
    if (noThumb > 0) rows += SideRow.Smart("nothumb", tr("nav.noThumb"), Icons.Filled.ImageNotSupported, noThumb)
    if (lib.trash.isNotEmpty()) rows += SideRow.Smart("trash", tr("nav.trash"), Icons.Filled.Delete, lib.trash.size)
    if (saved.isNotEmpty()) {
        rows += SideRow.Header(tr("nav.savedSearches"))
        saved.forEach { rows += SideRow.Saved(it) }
    }

    rows += SideRow.FolderHeader
    fun walk(parent: Long, depth: Int) {
        for (f in lib.childrenOf(parent)) {
            val subs = lib.childrenOf(f.id)
            rows += SideRow.FolderRow(f, depth, subs.isNotEmpty(), lib.recursiveCounts[f.id] ?: 0)
            if (f.id in expanded) walk(f.id, depth + 1)
        }
    }
    lib.rootId?.let { walk(it, 0) }

    if (lib.tags.isNotEmpty()) {
        rows += SideRow.TagHeader(tagsCollapsed, tagsByUsage, lib.tags.size)
        if (!tagsCollapsed) {
            val sorted = if (tagsByUsage) lib.tags.sortedWith(compareByDescending<de.bookmarksplus.data.TagInfo> { it.usage }.thenBy { it.label.lowercase() })
            else lib.tags.sortedBy { it.label.lowercase() }
            sorted.forEach { rows += SideRow.TagRow(it.id) }
        }
    }
    return rows
}

/** Drop target for drag & drop: reports the visible position of the folder in the window. */
@Composable
fun Modifier.dropTarget(state: AppState, folderId: Long): Modifier {
    androidx.compose.runtime.DisposableEffect(folderId) { onDispose { state.dropTargets.remove(folderId) } }
    return onGloballyPositioned { c -> state.dropTargets[folderId] = c.boundsInWindow() }
}
