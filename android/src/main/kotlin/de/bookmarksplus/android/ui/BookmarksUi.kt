package de.bookmarksplus.android.ui

import de.bookmarksplus.android.R
import de.bookmarksplus.android.str
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.bookmarksplus.android.BookmarkStore
import de.bookmarksplus.android.SyncOutcome
import de.bookmarksplus.data.Filter
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.SortField
import de.bookmarksplus.sync.FirstSyncMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(@androidx.annotation.StringRes private val labelRes: Int) {
    All(R.string.tab_all), Folders(R.string.tab_folders), Inbox(R.string.tab_inbox);

    val label: String get() = str(labelRes)
}

/** Sort orders offered on the phone (Manual applies in the folder view). */
private val SORTS = listOf(SortField.Added, SortField.Modified, SortField.Title, SortField.Domain, SortField.Stars, SortField.LastCommit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksUi(store: BookmarkStore, accent: Color, onAccent: (Color) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib by store.library.collectAsState()
    val loaded by store.loaded.collectAsState()
    val syncing by store.syncRunning.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(Tab.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var folderPath by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var tagIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var reposOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortField.Added) }
    var descending by rememberSaveable { mutableStateOf(true) }
    var cards by remember { mutableStateOf(store.cardView) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var firstSyncChoice by remember { mutableStateOf(false) }

    fun runSync(mode: FirstSyncMode = FirstSyncMode.Ask, quiet: Boolean = false) {
        scope.launch {
            when (val r = store.syncNow(mode)) {
                is SyncOutcome.Done -> if (!quiet || r.pulled > 0) snackbar.showSnackbar(r.text)
                is SyncOutcome.Failed -> snackbar.showSnackbar(r.message)
                SyncOutcome.NeedsFirstSyncChoice -> firstSyncChoice = true
                SyncOutcome.NotConfigured -> if (!quiet) showSettings = true
            }
        }
    }

    // synchronize once at startup (as on the desktop)
    LaunchedEffect(Unit) { if (store.syncConfigured) runSync(quiet = true) }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(store, accent, onAccent, onBack = { showSettings = false }, onSync = { runSync() })
        if (firstSyncChoice) FirstSyncDialog(onChoice = { firstSyncChoice = false; it?.let { m -> runSync(m) } })
        return
    }

    BackHandler(enabled = detailId == null && (query.isNotEmpty() || (tab == Tab.Folders && folderPath.isNotEmpty()) || tab != Tab.All)) {
        when {
            query.isNotEmpty() -> query = ""
            tab == Tab.Folders && folderPath.isNotEmpty() -> folderPath = folderPath.dropLast(1)
            else -> tab = Tab.All
        }
    }

    val inboxId = remember(lib) { lib.childrenOf(lib.rootId).firstOrNull { it.title.equals("Inbox", true) }?.id }
    val browsing = tab == Tab.Folders && query.isBlank()
    val currentFolder = if (tab == Tab.Folders) folderPath.lastOrNull()?.takeIf { it in lib.folders } ?: lib.rootId else null
    val filter = Filter(
        text = query,
        folderId = when (tab) { Tab.All -> null; Tab.Folders -> currentFolder; Tab.Inbox -> inboxId ?: -1L },
        includeSubfolders = !browsing,
        tagIds = tagIds,
        onlyRepos = reposOnly,
        sort = if (browsing) SortField.Manual else sort,
        descending = if (browsing) false else descending,
    )
    val items by produceState(emptyList<Item>(), lib, filter) {
        value = withContext(Dispatchers.Default) { if (filter.folderId == -1L) emptyList() else store.search(lib, filter) }
    }
    val subFolders = if (browsing && currentFolder != null) lib.childrenOf(currentFolder) else emptyList()

    Scaffold(
        topBar = {
            SearchBarRow(
                query = query, onQuery = { query = it },
                cards = cards, onToggleView = { cards = !cards; store.cardView = cards },
                onSettings = { showSettings = true },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Palette.Sidebar) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { if (tab == t && t == Tab.Folders) folderPath = emptyList(); tab = t },
                        icon = {
                            Icon(
                                when (t) { Tab.All -> Icons.Default.Bookmarks; Tab.Folders -> Icons.Default.Folder; Tab.Inbox -> Icons.Default.Inbox },
                                null,
                            )
                        },
                        label = { Text(if (t == Tab.Inbox && inboxId != null) str(R.string.tab_inbox_count, lib.placementsByFolder[inboxId]?.size ?: 0) else t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Palette.Background,
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = syncing, onRefresh = { runSync() },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            Column(Modifier.fillMaxSize()) {
                FilterRow(
                    lib, tagIds, onTags = { showTags = true },
                    reposOnly = reposOnly, onRepos = { reposOnly = !reposOnly },
                    sort = sort, descending = descending, sortEnabled = !browsing,
                    onSort = { f -> if (f == sort) descending = !descending else { sort = f; descending = f != SortField.Title && f != SortField.Domain } },
                    count = items.size,
                )
                if (tab == Tab.Folders && currentFolder != null) {
                    Breadcrumb(lib, currentFolder, onNavigate = { id -> folderPath = if (id == lib.rootId) emptyList() else lib.path(id).map { it.id } })
                }
                when {
                    !loaded -> EmptyState(str(R.string.list_loading), "")
                    lib.items.isEmpty() && tab != Tab.Inbox -> EmptyState(
                        str(R.string.list_empty_title),
                        str(R.string.list_empty_text1) +
                            str(R.string.list_empty_text2),
                    ) { Button(onClick = { showSettings = true }) { Text(str(R.string.list_setup_sync)) } }
                    tab == Tab.Inbox && items.isEmpty() && query.isBlank() -> EmptyState(
                        str(R.string.inbox_empty_title),
                        str(R.string.inbox_empty_text),
                    )
                    items.isEmpty() && subFolders.isEmpty() -> EmptyState(str(R.string.search_nothing), if (query.isNotBlank()) str(R.string.search_no_hits, query) else str(R.string.folder_empty))
                    cards -> CardGrid(store, lib, subFolders, items, onFolder = { folderPath = folderPath + it }, onItem = { detailId = it.id })
                    else -> ItemList(store, lib, subFolders, items, onFolder = { folderPath = folderPath + it }, onItem = { detailId = it.id }, onOpen = { openUrl(context, it.url) })
                }
            }
        }
    }

    detailId?.let { id -> lib.itemsById[id]?.let { DetailSheet(store, lib, it, onDismiss = { detailId = null }) } ?: run { detailId = null } }
    if (showTags) TagSheet(lib, tagIds, onToggle = { id -> tagIds = if (id in tagIds) tagIds - id else tagIds + id }, onClear = { tagIds = emptySet() }, onDismiss = { showTags = false })
    if (firstSyncChoice) FirstSyncDialog(onChoice = { firstSyncChoice = false; it?.let { m -> runSync(m) } })
}

@Composable
private fun SearchBarRow(query: String, onQuery: (String) -> Unit, cards: Boolean, onToggleView: () -> Unit, onSettings: () -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().background(Palette.Background).statusBarsPadding()
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            placeholder = { Text(str(R.string.search_hint), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery(""); focus.clearFocus() }) { Icon(Icons.Default.Close, str(R.string.common_clear)) } },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.SurfaceHigh, unfocusedContainerColor = Palette.SurfaceHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        )
        IconButton(onClick = onToggleView) {
            Icon(if (cards) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, if (cards) str(R.string.view_list) else str(R.string.view_cards))
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, str(R.string.settings_title)) }
    }
}

@Composable
private fun FilterRow(
    lib: Library, tagIds: Set<Long>, onTags: () -> Unit,
    reposOnly: Boolean, onRepos: () -> Unit,
    sort: SortField, descending: Boolean, sortEnabled: Boolean, onSort: (SortField) -> Unit,
    count: Int,
) {
    var sortMenu by remember { mutableStateOf(false) }
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Box {
                FilterChip(
                    selected = false, enabled = sortEnabled, onClick = { sortMenu = true },
                    label = { Text(if (sortEnabled) sort.label else str(R.string.sort_manual)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) },
                    trailingIcon = if (sortEnabled) ({ Icon(if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, Modifier.size(16.dp)) }) else null,
                )
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SORTS.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            onClick = { onSort(f); sortMenu = false },
                            trailingIcon = if (f == sort) ({ Icon(if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null) }) else null,
                        )
                    }
                }
            }
        }
        item {
            FilterChip(
                selected = tagIds.isNotEmpty(), onClick = onTags,
                label = { Text(if (tagIds.isEmpty()) "Tags" else tagIds.mapNotNull { lib.tagsById[it]?.label }.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp)) },
            )
        }
        item {
            FilterChip(
                selected = reposOnly, onClick = onRepos, label = { Text("Repos") },
                leadingIcon = { Icon(if (reposOnly) Icons.Default.Check else Icons.Default.Code, null, Modifier.size(18.dp)) },
            )
        }
        item { Muted("$count", Modifier.padding(start = 4.dp)) }
    }
}

@Composable
private fun Breadcrumb(lib: Library, folderId: Long, onNavigate: (Long) -> Unit) {
    val path = lib.path(folderId)
    val state = rememberLazyListState()
    LaunchedEffect(folderId) { state.scrollToItem(path.size) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text(
                str(R.string.nav_bookmarks), Modifier.clip(MaterialTheme.shapes.small).clickable { lib.rootId?.let(onNavigate) }.padding(4.dp),
                style = MaterialTheme.typography.bodySmall, color = if (path.isEmpty()) MaterialTheme.colorScheme.primary else Palette.TextMuted,
            )
        }
        items(path, key = { it.id }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("/", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                Text(
                    f.title, Modifier.clip(MaterialTheme.shapes.small).clickable { onNavigate(f.id) }.padding(4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (f.id == folderId) MaterialTheme.colorScheme.primary else Palette.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ItemList(
    store: BookmarkStore, lib: Library, folders: List<de.bookmarksplus.data.FolderInfo>, items: List<Item>,
    onFolder: (Long) -> Unit, onItem: (Item) -> Unit, onOpen: (Item) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(folders, key = { "f${it.id}" }) { FolderRow(lib, it) { onFolder(it.id) } }
        if (folders.isNotEmpty() && items.isNotEmpty()) item { HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Palette.OutlineSoft) }
        items(items, key = { "b${it.id}" }) { item ->
            ItemRow(store, lib, item, onClick = { onItem(item) }, onOpen = { onOpen(item) })
        }
    }
}

@Composable
private fun CardGrid(
    store: BookmarkStore, lib: Library, folders: List<de.bookmarksplus.data.FolderInfo>, items: List<Item>,
    onFolder: (Long) -> Unit, onItem: (Item) -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(folders, key = { "f${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { FolderRow(lib, it) { onFolder(it.id) } }
        items(items, key = { "b${it.id}" }) { item -> ItemCard(store, lib, item) { onItem(item) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSheet(lib: Library, selected: Set<Long>, onToggle: (Long) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var filterText by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tags", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) TextButton(onClick = onClear) { Text(str(R.string.common_reset)) }
        }
        TextField(
            filterText, { filterText = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(str(R.string.tags_search)) }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            shape = MaterialTheme.shapes.medium,
        )
        val tags = remember(lib, filterText) {
            lib.tags.filter { it.usage > 0 && (filterText.isBlank() || it.label.contains(filterText, true) || it.name.contains(filterText, true)) }
                .sortedWith(compareByDescending<de.bookmarksplus.data.TagInfo> { it.id in selected }.thenByDescending { it.usage })
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(tags, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(t.id) }.padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = t.id in selected, onCheckedChange = { onToggle(t.id) })
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color)))
                    Spacer(Modifier.width(10.dp))
                    Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Muted(t.usage.toString())
                }
            }
        }
    }
}

/** First sync although links already exist locally (e.g. previously shared): merge or adopt the state from the target. */
@Composable
fun FirstSyncDialog(onChoice: (FirstSyncMode?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(str(R.string.first_sync_title)) },
        text = {
            Text(
                str(R.string.first_sync_text1) +
                    str(R.string.first_sync_text2) +
                    str(R.string.first_sync_text3),
            )
        },
        confirmButton = { Button(onClick = { onChoice(FirstSyncMode.Merge) }) { Text(str(R.string.first_sync_merge)) } },
        dismissButton = { TextButton(onClick = { onChoice(FirstSyncMode.TakeRemote) }) { Text(str(R.string.first_sync_pull)) } },
    )
}
