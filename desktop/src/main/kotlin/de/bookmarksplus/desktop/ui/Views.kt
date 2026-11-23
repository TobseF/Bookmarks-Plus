package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.desktop.AppState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import de.bookmarksplus.desktop.PickerRequest

// ---------------------------------------------------------------- Common

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.itemInteraction(state: AppState, item: Item, ordered: () -> List<Long>): Modifier {
    val windowInfo = LocalWindowInfo.current
    val mods = windowInfo.keyboardModifiers
    val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    // drag onto a folder in the sidebar: move (with Ctrl: link)
    return onGloballyPositioned { coords[0] = it }
        .pointerInput(item.id) {
            detectDragGestures(
                onDragStart = { off -> coords[0]?.takeIf { it.isAttached }?.localToWindow(off)?.let { state.startDrag(item.id, it.x, it.y) } },
                onDrag = { change, _ -> change.consume(); coords[0]?.takeIf { it.isAttached }?.localToWindow(change.position)?.let { state.dragTo(it.x, it.y) } },
                onDragEnd = { state.endDrag(link = windowInfo.keyboardModifiers.isCtrlPressed) },
                onDragCancel = { state.drag = null; state.dropHover = null },
            )
        }
        .combinedClickable(
        onClick = { state.select(item.id, toggle = mods.isCtrlPressed || mods.isMetaPressed, range = mods.isShiftPressed, ordered = ordered()) },
        onDoubleClick = { state.open(item) },
    )
}

/** Right-click menu of a bookmark; acts on the whole selection if the entry belongs to it. */
@Composable
private fun ItemContextMenu(state: AppState, item: Item, content: @Composable () -> Unit) {
    ContextMenuArea(items = {
        // right-click on a non-selected entry selects it
        if (item.id !in state.selection) state.select(item.id)
        val ids = state.targetsFor(item.id)
        val n = ids.size
        val many = if (n > 1) " ($n)" else ""
        if (item.deletedAt != null) listOf(
            ContextMenuItem(tr("ctx.restore", many)) { state.restore(ids) },
            ContextMenuItem(tr("ctx.deleteForever", many)) { state.deleteForever(ids) },
            ContextMenuItem(tr("detail.copyUrl")) { state.copyUrl(item) },
        ) else buildList {
            add(ContextMenuItem(tr("common.openInBrowser")) { ids.mapNotNull { state.library.itemsById[it] }.take(20).forEach(state::open) })
            add(ContextMenuItem(tr("detail.copyUrl")) { state.copyUrl(item) })
            if (n == 1) {
                add(ContextMenuItem(tr("ctx.renameF2")) { state.startEdit(item.id, focusTitle = true) })
                add(ContextMenuItem(tr("ctx.edit")) { state.startEdit(item.id) })
            }
            add(ContextMenuItem(tr("ctx.moveTo", many)) { state.picker = PickerRequest(ids, link = false) })
            add(ContextMenuItem(tr("ctx.linkWith", many)) { state.picker = PickerRequest(ids, link = true) })
            val folder = state.filter.folderId
            if (n == 1 && folder != null && (state.library.placementsByItem[item.id]?.size ?: 0) > 1 &&
                state.library.placementsByItem[item.id]?.any { it.folderId == folder } == true
            ) add(ContextMenuItem(tr("ctx.removeFromOnly", state.library.folders[folder]?.title)) { state.removeFromFolder(item.id, folder) })
            add(ContextMenuItem(tr("ctx.reload", many)) { state.enrich(ids) })
            if (ids.any { state.library.itemsById[it]?.linkStatus != null }) add(ContextMenuItem(tr("ctx.clearLinkStatus", many)) { state.clearLinkStatus(ids) })
            if (ids.any { state.library.itemsById[it]?.linkIgnore == false }) add(ContextMenuItem(tr("ctx.ignore", many)) { state.setLinkIgnore(ids, true) })
            if (ids.any { state.library.itemsById[it]?.linkIgnore == true }) add(ContextMenuItem(tr("ctx.unignore", many)) { state.setLinkIgnore(ids, false) })
            add(ContextMenuItem(tr("ctx.refreshThumb", many)) { state.refreshThumbnails(ids) })
            if (n == 1) add(ContextMenuItem(tr("ctx.chooseThumb")) { state.thumbPicker = item.id })
            add(ContextMenuItem(tr("ctx.ai", many)) { state.aiFor(ids) })
            add(ContextMenuItem(tr("ctx.appData", many)) { state.updateApps(ids, force = true) })
            add(ContextMenuItem(tr("ctx.trash", many)) { state.trash(ids) })
        }
    }) { content() }
}

/** Right-click menu of a folder (sidebar and tree view). */
@Composable
fun FolderContextMenu(state: AppState, folderId: Long, content: @Composable () -> Unit) {
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem(tr("common.open")) { state.filter = state.filter.copy(folderId = folderId, trash = false) },
            ContextMenuItem(tr("ctx.newSubfolder")) { state.newFolder(folderId) },
            ContextMenuItem(tr("common.renameMenu")) { state.renameFolder(folderId) },
            ContextMenuItem(tr("ctx.moveToPlain")) { state.picker = PickerRequest(emptySet(), link = false, folderMove = folderId) },
            ContextMenuItem(tr("ctx.delete")) { state.deleteFolder(folderId) },
        )
    }) { content() }
}

@Composable
private fun TagDots(state: AppState, item: Item, max: Int = 3) {
    val tags = state.library.tagsOf[item.id].orEmpty().mapNotNull { state.library.tagsById[it] }.sortedBy { it.label }
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(max).forEach { TagChip(it, small = true) }
        if (tags.size > max) Text("+${tags.size - max}", fontSize = 11.sp, color = Palette.TextFaint)
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Link, null, Modifier.size(40.dp), tint = Palette.TextFaint)
            Spacer(Modifier.height(10.dp))
            Text(text, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun rowBackground(selected: Boolean, hovered: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.primaryContainer
    hovered -> Palette.SurfaceHigh
    else -> Color.Transparent
}

// ---------------------------------------------------------------- List

@Composable
fun ListView(state: AppState) {
    val items = state.results
    if (items.isEmpty()) return EmptyState(tr("list.noneFound"))
    val listState = rememberLazyListState()
    LaunchedEffect(state.filter) { listState.scrollToItem(0) }
    val ids = remember(items) { items.map { it.id } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("list.titleHeader"), style = SectionLabel, modifier = Modifier.weight(1f).padding(start = 26.dp))
            Text("DOMAIN", style = SectionLabel, modifier = Modifier.width(170.dp))
            Text(tr("nav.foldersHeader"), style = SectionLabel, modifier = Modifier.width(220.dp))
            Text("STARS", style = SectionLabel, modifier = Modifier.width(64.dp))
            Text(tr("list.addedHeader"), style = SectionLabel, modifier = Modifier.width(96.dp))
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), listState, contentPadding = PaddingValues(start = 12.dp, end = 8.dp, bottom = 24.dp)) {
                items(items, key = { it.id }) { item -> ListRow(state, item) { ids } }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ListRow(state: AppState, item: Item, ordered: () -> List<Long>) {
    val (src, hovered) = rememberHover()
    val selected = item.id in state.selection
    val lib = state.library
    ItemContextMenu(state, item) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
                .background(rowBackground(selected, hovered)).hover(src)
                .itemInteraction(state, item, ordered).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Favicon(state, item, 16.dp)
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.displayTitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    color = if (item.isDead) Palette.TextFaint else Palette.Text)
                if (item.isDead) DeadBadge()
                DirectoryLinks(state, item, 12.dp, 3.dp)
                TagDots(state, item)
            }
            Spacer(Modifier.width(12.dp))
            Muted(item.domain ?: "", Modifier.width(170.dp))
            val folderText = lib.primaryFolder(item.id)?.let { f -> lib.path(f).takeLast(2).joinToString(" / ") { it.title } }
                ?: lib.trashPlacementsByItem[item.id]?.firstOrNull()?.let { p -> lib.path(p.folderId).takeLast(2).joinToString(" / ") { it.title } }
                ?: item.trashPath?.split(" / ")?.takeLast(2)?.joinToString(" / ")
            Muted(folderText ?: "", Modifier.width(220.dp))
            Box(Modifier.width(64.dp)) { item.stars?.let { StarsBadge(it) } }
            Muted(formatDate(item.addDate), Modifier.width(96.dp))
        }
    }
}

// ---------------------------------------------------------------- Tree

private sealed interface TreeRow {
    data class F(val id: Long, val title: String, val depth: Int, val count: Int, val open: Boolean) : TreeRow
    data class B(val item: Item, val depth: Int, val placementId: Long, val title: String) : TreeRow
}

private fun buildTree(lib: Library, rootId: Long, visible: Set<Long>, narrowing: Boolean, expanded: Set<Long>): List<TreeRow> {
    val rows = ArrayList<TreeRow>()
    // number of matching entries per folder (recursive), to hide empty folders when filtering
    val matchCount = HashMap<Long, Int>()
    fun count(id: Long): Int = matchCount.getOrPut(id) {
        lib.placementsByFolder[id].orEmpty().count { it.bookmarkId in visible } + lib.childrenOf(id).sumOf { count(it.id) }
    }
    fun walk(folderId: Long, depth: Int) {
        val subs = lib.childrenOf(folderId).map { it.position to it }
        val ps = lib.placementsByFolder[folderId].orEmpty().map { it.position to it }
        (subs + ps).sortedBy { it.first }.forEach { (_, e) ->
            when (e) {
                is de.bookmarksplus.data.FolderInfo -> {
                    val n = count(e.id)
                    if (narrowing && n == 0) return@forEach
                    val open = if (narrowing) e.id !in expanded else e.id in expanded // open by default when filtering
                    rows += TreeRow.F(e.id, e.title, depth, n, open)
                    if (open) walk(e.id, depth + 1)
                }
                is de.bookmarksplus.data.PlacementInfo -> {
                    val item = lib.itemsById[e.bookmarkId] ?: return@forEach
                    if (e.bookmarkId !in visible) return@forEach
                    rows += TreeRow.B(item, depth, e.id, e.title ?: item.displayTitle)
                }
            }
        }
    }
    walk(rootId, 0)
    return rows
}

@Composable
fun TreeView(state: AppState) {
    val lib = state.library
    val root = state.filter.folderId ?: lib.rootId ?: return EmptyState(tr("list.emptyImport"))
    val visible = state.resultIds
    val narrowing = state.filter.isNarrowing
    val expanded = state.treeExpanded
    val rows = remember(lib, root, visible, narrowing, expanded) { buildTree(lib, root, visible, narrowing, expanded) }
    if (rows.isEmpty()) return EmptyState(tr("list.noneFound"))
    val ids = remember(rows) { rows.filterIsInstance<TreeRow.B>().map { it.item.id } }
    val listState = rememberLazyListState()
    // bookmark to scroll to after expanding
    var scrollTarget by remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    LaunchedEffect(scrollTarget, rows) {
        val target = scrollTarget ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it is TreeRow.B && it.item.id == target }
        if (idx >= 0) listState.animateScrollToItem((idx - 3).coerceAtLeast(0))
        scrollTarget = null
    }
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        val selId = state.selection.firstOrNull()
        val folderId = selId?.let { lib.primaryFolder(it) }
        // when filtering, "in expanded" means collapsed (default open)
        val allFolders = lib.folders.keys.filterTo(HashSet()) { it != lib.rootId && lib.childrenOf(it).isNotEmpty() }
        HeaderIcon(Icons.Filled.MyLocation, tr("nav.jumpToFolder"), enabled = folderId != null) {
            val ancestors = generateSequence(folderId) { lib.folders[it]?.parentId }.filter { it != lib.rootId }.toSet()
            state.treeExpanded = if (narrowing) expanded - ancestors else expanded + ancestors
            scrollTarget = selId
        }
        HeaderIcon(Icons.Filled.UnfoldMore, tr("nav.expandAll")) { state.treeExpanded = if (narrowing) emptySet() else allFolders }
        HeaderIcon(Icons.Filled.UnfoldLess, tr("nav.collapseAll")) { state.treeExpanded = if (narrowing) allFolders else emptySet() }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(start = 12.dp, end = 20.dp, bottom = 24.dp)) {
            items(rows, key = { r -> if (r is TreeRow.F) "f${r.id}" else "p${(r as TreeRow.B).placementId}" }) { r ->
                when (r) {
                    is TreeRow.F -> FolderContextMenu(state, r.id) {
                        val (src, hovered) = rememberHover()
                        Row(
                            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (hovered) Palette.SurfaceHigh else Color.Transparent).hover(src)
                                .clickable {
                                    // when filtering, "in expanded" means collapsed (default open)
                                    state.treeExpanded = if (r.id in expanded) expanded - r.id else expanded + r.id
                                }
                                .padding(start = (12 + r.depth * 20).dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(if (r.open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = Palette.TextFaint)
                            Icon(if (r.open) Icons.Outlined.Folder else Icons.Filled.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                            Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text("${r.count}", fontSize = 11.sp, color = Palette.TextFaint)
                        }
                    }
                    is TreeRow.B -> {
                        val (src, hovered) = rememberHover()
                        val selected = r.item.id in state.selection
                        val linked = (lib.placementsByItem[r.item.id]?.size ?: 0) > 1
                        ItemContextMenu(state, r.item) {
                            Row(
                                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                                    .background(rowBackground(selected, hovered)).hover(src)
                                    .itemInteraction(state, r.item) { ids }
                                    .padding(start = (12 + r.depth * 20 + 22).dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Favicon(state, r.item, 15.dp)
                                Text(r.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    color = if (r.item.isDead) Palette.TextFaint else Palette.Text)
                                if (r.item.isDead) DeadBadge()
                                if (linked) Icon(Icons.Filled.Link, tr("tree.linked"), Modifier.size(13.dp), tint = Palette.TextFaint)
                                TagDots(state, r.item, max = 2)
                                Spacer(Modifier.weight(1f))
                                r.item.stars?.let { StarsBadge(it) }
                                Muted(r.item.domain ?: "", Modifier.width(160.dp))
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    }
}

// ---------------------------------------------------------------- Cards

@Composable
fun CardsView(state: AppState) {
    val items = state.results
    if (items.isEmpty()) return EmptyState(tr("list.noneFound"))
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.filter) { gridState.scrollToItem(0) }
    val ids = remember(items) { items.map { it.id } }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(250.dp), Modifier.fillMaxSize(), gridState,
            contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.id }) { item -> Card(state, item) { ids } }
        }
        VerticalScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Card(state: AppState, item: Item, ordered: () -> List<Long>) {
    val (src, hovered) = rememberHover()
    val selected = item.id in state.selection
    val accent = MaterialTheme.colorScheme.primary
    val border = when {
        selected -> accent
        hovered -> Palette.Outline.copy(alpha = 1f).let { Color(0xFF3A3A45) }
        else -> Palette.OutlineSoft
    }
    ItemContextMenu(state, item) {
        Column(
            Modifier.fillMaxWidth().height(292.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Palette.Surface)
                .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(12.dp))
                .hover(src).itemInteraction(state, item, ordered),
        ) {
            CardImage(state, item, Modifier.fillMaxWidth().aspectRatio(1.9f))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Favicon(state, item, 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Muted(item.displayDomain ?: item.url, Modifier.weight(1f))
                    if (item.isDead) DeadBadge()
                    DirectoryLinks(state, item, 13.dp, 3.dp)
                    if (item.app.flathubUrl != null || item.app.alternativetoUrl != null) Spacer(Modifier.width(6.dp))
                    item.stars?.let { StarsBadge(it) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(4.dp))
                val text = item.summary ?: item.anyDescription
                if (!text.isNullOrBlank()) Muted(text.replace('\n', ' '), maxLines = 2)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TagDots(state, item, max = 2) }
                    Text(formatRelative(item.addDate), fontSize = 11.sp, color = Palette.TextFaint)
                }
            }
        }
    }
}

@Composable
fun DeadBadge() {
    Text(
        tr("health.dead"), fontSize = 10.sp, color = Color(0xFFFF8A80),
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x33FF5252)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
