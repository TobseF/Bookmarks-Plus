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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import de.bookmarksplus.data.Shots
import de.bookmarksplus.desktop.ImageInput
import de.bookmarksplus.desktop.addScreenshot
import de.bookmarksplus.desktop.setShotHidden
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.Item
import de.bookmarksplus.desktop.AppState
import de.bookmarksplus.desktop.PickerRequest
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Edit

@Composable
fun DetailPane(state: AppState, modifier: Modifier = Modifier) {
    val sel = state.selection
    Column(modifier.fillMaxHeight().background(Palette.Sidebar)) {
        when {
            sel.size == 1 -> state.library.anyItem(sel.first())?.let { if (state.editingId == it.id) EditDetail(state, it) else SingleDetail(state, it) }
            sel.size > 1 -> MultiDetail(state, sel)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(title.uppercase(), style = SectionLabel)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, fontSize = 12.sp, color = Palette.TextFaint, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SingleDetail(state: AppState, item: Item) {
    val lib = state.library
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Box {
            CardImage(state, item, Modifier.fillMaxWidth().aspectRatio(1.9f))
            IconButton(onClick = { state.clearSelection() }, Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                .clip(RoundedCornerShape(50)).background(Palette.Background.copy(alpha = 0.6f))) {
                Icon(Icons.Filled.Close, tr("common.close"), Modifier.size(16.dp), tint = Palette.Text)
            }
        }
        Column(Modifier.padding(18.dp)) {
            if (item.deletedAt != null) TrashBanner(state, setOf(item.id))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Favicon(state, item, 16.dp)
                Spacer(Modifier.width(8.dp))
                Muted(item.displayDomain ?: "", Modifier.weight(1f))
                // Flathub/AlternativeTo: icons show that the pages are stored; click opens them
                DirectoryLinks(state, item, 18.dp, 5.dp)
                if (item.stars != null && (item.app.flathubUrl != null || item.app.alternativetoUrl != null)) Spacer(Modifier.width(8.dp))
                item.stars?.let { StarsBadge(it) }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.ContextMenuArea(items = {
                buildList {
                    add(androidx.compose.foundation.ContextMenuItem(tr("detail.copyTitle")) { state.copyText(item.displayTitle, tr("detail.titleCopied")) })
                    if (item.displayTitle != item.title) add(androidx.compose.foundation.ContextMenuItem(tr("detail.copyOrigTitle")) { state.copyText(item.title, tr("detail.titleCopied")) })
                    add(androidx.compose.foundation.ContextMenuItem(tr("detail.copyTitleUrl")) { state.copyText("${item.displayTitle}\n${item.url}", tr("detail.titleUrlCopied")) })
                    add(androidx.compose.foundation.ContextMenuItem(tr("detail.copyMarkdown")) { state.copyText("[${item.displayTitle}](${item.url})", tr("detail.markdownCopied")) })
                }
            }) {
                Text(item.displayTitle, style = MaterialTheme.typography.titleMedium)
            }
            if (item.aiTitle != null && item.aiTitle != item.title) Row(verticalAlignment = Alignment.CenterVertically) {
                Muted(tr("detail.original", item.title), Modifier.weight(1f), maxLines = 2)
                Text(
                    tr("detail.discardAiTitle"), fontSize = 11.sp, color = Palette.TextFaint,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.dismissAiTitle(item.id) }.padding(4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // website and GitHub repo of equal rank, each with the matching icon in front
            item.siteUrl?.let { UrlRow(state, item, it) { SiteFavicon(state, item, 16.dp) } }
            item.githubUrl?.takeIf { it != item.siteUrl }?.let { UrlRow(state, item, it) { GithubBadge(16.dp) } }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { state.open(item) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("common.open"))
                }
                item.downloadUrl?.let { dl ->
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { state.open(item.copy(url = dl)) },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                }
                Spacer(Modifier.weight(1f))
                ActionIcon(Icons.Filled.ContentCopy, tr("detail.copyUrl")) { state.copyUrl(item) }
                if (item.deletedAt == null) {
                    ActionIcon(Icons.Filled.Edit, tr("detail.editF2")) { state.startEdit(item.id) }
                    ActionIcon(Icons.AutoMirrored.Filled.DriveFileMove, tr("detail.move")) { state.picker = PickerRequest(setOf(item.id), link = false) }
                    RefreshMenu(state, item)
                    ActionIcon(Icons.Filled.Delete, tr("detail.trash")) { state.trash(listOf(item.id)) }
                }
            }

            if (item.deletedAt == null) Section("Tags") { TagEditor(state, setOf(item.id)) }

            if (item.deletedAt == null) AiSuggestions(state, item)
            if (item.deletedAt == null) DuplicateBox(state, item)

            Section(tr("detail.summary")) {
                val busy = item.id in state.aiBusy
                if (item.summary != null) Text(item.summary!!, fontSize = 13.sp, color = Palette.Text, lineHeight = 19.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (item.summary != null) 6.dp else 0.dp)) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = if (busy) MaterialTheme.colorScheme.primary else Palette.TextFaint)
                    Spacer(Modifier.width(6.dp))
                    when {
                        busy -> Muted(tr("detail.aiRunning"), maxLines = 1)
                        else -> Text(
                            when {
                                item.aiAt == null -> tr("detail.aiAnalyse")
                                item.summary == null -> tr("detail.aiNoContent")
                                else -> tr("detail.regenerate")
                            },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.aiFor(listOf(item.id)) }.padding(2.dp),
                        )
                    }
                }
            }

            item.anyDescription?.let { d ->
                Section(tr("detail.description")) {
                    Text(d, fontSize = 13.sp, color = Palette.TextMuted, lineHeight = 19.sp)
                    // original of the page or browser when the uniform AI description is displayed
                    item.originalDescription?.takeIf { item.aiDescription != null && it != d }?.let { o ->
                        Spacer(Modifier.height(6.dp))
                        Text(tr("detail.original", o), fontSize = 11.sp, color = Palette.TextFaint, lineHeight = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (item.shots.isNotEmpty() || item.app.name != null || item.isRepo) Section("Screenshots") { Screenshots(state, item) }

            if (item.deletedAt == null && (item.app.isApp || item.isRepo || item.isSourceforge || item.contentType == "tool"))
                Section(if (item.app.nameSource == "none") tr("detail.programUnrecognized") else tr("detail.program")) { AppSection(state, item) }

            val sf = item.isSourceforge
            if (item.isRepo || sf || item.websiteUrl != null || item.repoUrl != null) Section(tr("detail.project")) {
                item.downloadUrl?.let { dl -> LinkRow(Icons.Filled.Download, item.downloadLabel ?: dl) { state.open(item.copy(url = dl)) } }
                item.stars?.let { KeyValue(if (sf) "Reviews" else "Stars", "%,d".format(it).replace(',', '.')) }
                item.author?.let { KeyValue(if (sf && ',' in it) tr("detail.authors") else tr("detail.author"), it) }
                item.org?.let { KeyValue(tr("detail.organisation"), it) }
                item.lastCommit?.let { KeyValue(if (sf) tr("detail.lastUpdate") else tr("detail.lastCommit"), formatDate(it)) }
                item.lastRelease?.let { KeyValue(if (sf) tr("detail.currentVersion") else tr("detail.lastRelease"), "$it · ${formatDate(item.lastReleaseDate)}") }
                if (sf && item.stars == null) Muted(
                    if (item.scrapedAt == null) tr("detail.sfNotEnriched") else tr("detail.sfMissing"),
                    maxLines = 2,
                ) else if (item.stars == null) Muted(
                    if (item.scrapedAt == null) tr("detail.ghNotEnriched")
                    else tr("detail.ghMissing"),
                    maxLines = 2,
                )
            }

            if (item.hasReadme) Section("README") { ReadmeBox(state, item) }

            val images = imageCandidates(item)
            if (images.size > 1) Section(if (item.domain == "github.com") tr("detail.website") else tr("detail.shareImage")) {
                CardImage(state, item, Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(10.dp)), path = images[1])
            }

            val placements = lib.placementsByItem[item.id] ?: lib.trashPlacementsByItem[item.id].orEmpty()
            Section(if (placements.size > 1) tr("detail.foldersN", placements.size) else tr("detail.folder")) {
                placements.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable { state.filter = state.filter.copy(folderId = p.folderId) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(14.dp), tint = if (p.primary) MaterialTheme.colorScheme.primary else Palette.TextFaint)
                        Spacer(Modifier.width(8.dp))
                        Text(lib.pathString(p.folderId).ifEmpty { "(Wurzel)" }, fontSize = 12.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (placements.size > 1) Box(
                            Modifier.size(20.dp).clip(RoundedCornerShape(50)).clickable { state.removeFromFolder(item.id, p.folderId) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Close, tr("detail.removeLink"), Modifier.size(12.dp), tint = Palette.TextFaint) }
                    }
                }
                if (item.deletedAt == null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SmallLink(tr("detail.move")) { state.picker = PickerRequest(setOf(item.id), link = false) }
                    SmallLink(tr("detail.link")) { state.picker = PickerRequest(setOf(item.id), link = true) }
                }
            }

            if (item.deletedAt == null) Section("Link") { LinkStatus(state, item) }

            Section("Details") {
                KeyValue(tr("detail.added"), formatDate(item.addDate))
                KeyValue(tr("detail.modified"), formatDate(item.lastModified))
                item.lastVisited?.let { KeyValue(tr("detail.visited"), formatDate(it)) }
                KeyValue(tr("detail.enriched"), item.scrapedAt?.let { formatDate(it) } ?: tr("detail.notYet"))
            }
        }
    }
}

/** ↻ with selection: reload everything, thumbnail only, app data, AI. */
@Composable
private fun RefreshMenu(state: AppState, item: Item) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionIcon(Icons.Filled.Refresh, tr("detail.refreshMenu")) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceHigh) {
            @Composable
            fun entry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label, fontSize = 13.sp) },
                leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = Palette.TextMuted) },
                onClick = { open = false; action() },
            )
            entry(Icons.Filled.Refresh, tr("detail.reloadAll")) { state.enrich(listOf(item.id)) }
            entry(Icons.Filled.Public, tr("detail.refreshThumb")) { state.refreshThumbnails(listOf(item.id)) }
            entry(Icons.Filled.Apps, tr("detail.refreshApp")) { state.appsFor(item.id) }
            entry(Icons.Filled.AutoAwesome, tr("detail.aiAnalyseShort")) { state.aiFor(listOf(item.id)) }
        }
    }
}

/** Up to three app screenshots; click shows them large. */
@Composable
private fun Screenshots(state: AppState, item: Item) {
    var open by remember(item.id) { mutableStateOf<Int?>(null) }
    var showHidden by remember(item.id) { mutableStateOf(false) }
    val hiddenCount = item.shots.count { it.hidden }
    val visible = item.shots.filter { !it.hidden }
    val shown = if (showHidden) item.shots else visible
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedButton(
            onClick = {
                ImageInput.pickFile(tr("detail.addScreenshot"))?.let { f ->
                    if (f.length() > ImageInput.MAX_FILE_BYTES) state.notify(tr("detail.fileTooLarge")) else state.addScreenshot(item.id, ImageInput.readFile(f))
                }
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(30.dp),
        ) { Icon(Icons.Filled.UploadFile, null, Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text(tr("detail.file"), fontSize = 12.sp) }
        androidx.compose.material3.OutlinedButton(
            onClick = { state.addScreenshot(item.id, ImageInput.fromClipboard()) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(30.dp),
        ) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text(tr("detail.clipboard"), fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        if (hiddenCount > 0) Text(
            if (showHidden) tr("detail.hideHidden") else tr("detail.hiddenN", hiddenCount), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { showHidden = !showHidden }.padding(4.dp),
        )
    }
    if (shown.isEmpty()) Muted(tr("detail.noScreenshots"), maxLines = 2)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        shown.forEach { s ->
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.weight(1f).aspectRatio(4f / 3f).clip(shape).border(1.dp, Palette.OutlineSoft, shape)
                    .clickable(enabled = !s.hidden) { open = visible.indexOfFirst { it.hash == s.hash }.takeIf { it >= 0 } },
            ) {
                ScreenshotImage(
                    state, item, s.path, Modifier.fillMaxSize().then(if (s.hidden) Modifier.alpha(0.35f) else Modifier),
                    androidx.compose.ui.layout.ContentScale.Crop,
                )
                Text(
                    Shots.label(s.source), fontSize = 10.sp, color = Color.White, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 1.dp),
                )
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)).clickable { state.setShotHidden(item.id, s.hash, !s.hidden) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (s.hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (s.hidden) tr("detail.show") else tr("detail.hide"),
                        Modifier.size(14.dp), tint = Color.White,
                    )
                }
            }
        }
        // pad rows so that a single image does not take the full width
        repeat((3 - shown.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
    }
    open?.let { i -> ScreenshotViewer(state, item, i, onIndex = { open = it }, onClose = { open = null }) }
}

/** Large view: arrow keys page through, Esc or X at the top right closes; arrow buttons at the image edge. */
@Composable
private fun ScreenshotViewer(state: AppState, item: Item, index: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val n = item.screenshots.size
    val shot = item.shots.filter { !it.hidden }.getOrNull(index)
    val path = shot?.path ?: return
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun go(delta: Int) { if (n > 1) onIndex((index + delta + n) % n) }
    // for programs the program name is enough
    val title = item.app.name?.takeIf { it.isNotBlank() } ?: item.displayTitle
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier.padding(24.dp).widthIn(max = 1500.dp).fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh)
                .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(16.dp))
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.DirectionLeft -> { go(-1); true }
                        androidx.compose.ui.input.key.Key.DirectionRight -> { go(1); true }
                        androidx.compose.ui.input.key.Key.Escape -> { onClose(); true }
                        else -> false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (n > 1) {
                    Spacer(Modifier.width(10.dp))
                    Text("${index + 1} / $n", fontSize = 12.sp, color = Palette.TextMuted)
                }
                shot?.let { s ->
                    Spacer(Modifier.width(10.dp))
                    Text(Shots.label(s.source), fontSize = 11.sp, color = Palette.TextMuted)
                }
                Spacer(Modifier.weight(1f))
                shot?.let { s ->
                    IconButton(onClick = {
                        state.setShotHidden(item.id, s.hash, true)
                        if (n <= 1) onClose() else onIndex(minOf(index, n - 2))
                    }) { Icon(Icons.Filled.VisibilityOff, tr("detail.hide"), tint = Palette.TextMuted) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("common.close"), tint = Palette.Text) }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 860.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                ScreenshotImage(
                    state, item, path, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    androidx.compose.ui.layout.ContentScale.Fit,
                )
                if (n > 1) {
                    ArrowButton(Icons.Filled.ChevronLeft, tr("common.back"), Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) { go(-1) }
                    ArrowButton(Icons.Filled.ChevronRight, tr("common.next"), Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) { go(1) }
                }
            }
            if (n > 1) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(n) { k ->
                        Box(
                            Modifier.padding(horizontal = 4.dp).size(if (k == index) 9.dp else 7.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (k == index) MaterialTheme.colorScheme.primary else Palette.TextFaint)
                                .clickable { onIndex(k) },
                        )
                    }
                }
            } else Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(Palette.Background.copy(alpha = 0.6f))
            .border(1.dp, Palette.OutlineSoft, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(30.dp), tint = Palette.Text) }
}

@Composable
private fun ScreenshotImage(state: AppState, item: Item, path: String, modifier: Modifier, scale: androidx.compose.ui.layout.ContentScale) {
    val bmp = rememberItemImage(state, item, path)
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, modifier, contentScale = scale)
    else Box(modifier.background(Palette.Surface))
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, url: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = Palette.TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MultiDetail(state: AppState, ids: Set<Long>) {
    Column(Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("multi.selected", ids.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.clearSelection() }) { Icon(Icons.Filled.Close, tr("multi.clear"), tint = Palette.TextMuted) }
        }
        Muted(tr("multi.hint"), maxLines = 2)
        if (ids.any { it in state.library.trashById }) {
            Spacer(Modifier.height(12.dp))
            TrashBanner(state, ids.filterTo(HashSet()) { it in state.library.trashById })
            return@Column
        }
        Section(tr("multi.manage")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { state.picker = PickerRequest(ids, link = false) }) { Text(tr("common.move")) }
                OutlinedButton(onClick = { state.picker = PickerRequest(ids, link = true) }) { Text(tr("common.link")) }
                IconButton(onClick = { state.trash(ids) }) { Icon(Icons.Filled.Delete, tr("multi.trash"), tint = Palette.TextMuted) }
            }
        }
        val lib0 = state.library
        val sel = ids.mapNotNull { lib0.itemsById[it] }
        val dupGroups = sel.mapNotNull { lib0.duplicateGroups[it.urlNorm] }.distinct()
        if (sel.size == 2) Section(tr("multi.mergeTwo")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(tr("multi.mergeTwoText"), maxLines = 4)
                sel.forEachIndexed { i, keep ->
                    val other = sel[1 - i]
                    OutlinedButton(onClick = { state.mergeInto(keep.id, listOf(other.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.MergeType, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("multi.keep", keep.title.ifBlank { keep.url }), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val redirects = sel.count { it.health == de.bookmarksplus.data.LinkHealth.Redirect && it.finalUrl != null }
        if (dupGroups.isNotEmpty() || redirects > 0) Section(tr("multi.cleanup")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dupGroups.isNotEmpty()) OutlinedButton(onClick = { state.mergeDuplicates(ids) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("multi.mergeDupes", dupGroups.size))
                }
                if (redirects > 0) OutlinedButton(onClick = { state.applyRedirects(ids) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("multi.applyRedirects", redirects))
                }
                OutlinedButton(onClick = { state.checkLinks(ids) }, modifier = Modifier.fillMaxWidth()) { Text(tr("multi.checkLinks", ids.size)) }
            }
        }
        Section(tr("multi.tagsForAll")) { TagEditor(state, ids) }
        Section(tr("batch.enrich")) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { state.enrich(ids) }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("multi.reload", ids.size))
                }
                OutlinedButton(onClick = { state.aiFor(ids) }) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("KI")
                }
                OutlinedButton(onClick = { state.updateApps(ids, force = true) }, enabled = !state.appProgress.running) {
                    Icon(Icons.Filled.Apps, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("detail.appData"))
                }
            }
        }
        val lib = state.library
        val items = ids.mapNotNull { lib.itemsById[it] }
        val withTags = items.count { lib.openTagSuggestions(it).isNotEmpty() }
        val withFolder = items.count { lib.openFolderSuggestion(it) != null }
        if (withTags > 0 || withFolder > 0) Section(tr("multi.aiSuggestions")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (withTags > 0) OutlinedButton(onClick = { state.acceptTags(ids) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("multi.applyTags", withTags))
                }
                if (withFolder > 0) {
                    // most frequent target folders for orientation
                    items.mapNotNull { lib.openFolderSuggestion(it) }.groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }.take(4).forEach { (path, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Folder, null, Modifier.size(13.dp), tint = Palette.TextFaint)
                                Spacer(Modifier.width(6.dp))
                                Text("$path · $n", fontSize = 12.sp, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.acceptFolder(ids) }) { Text(tr("multi.moveN", withFolder)) }
                        OutlinedButton(onClick = { state.acceptFolder(ids, link = true) }) { Text(tr("common.link")) }
                    }
                    Text(
                        tr("multi.discardFolders"), fontSize = 12.sp, color = Palette.TextFaint,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.dismissFolder(ids) }.padding(2.dp),
                    )
                }
            }
        }
    }
}

/** Open AI suggestions: tags (click adopts, × discards) and target folder (move / link). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSuggestions(state: AppState, item: Item) {
    val lib = state.library
    val tags = lib.openTagSuggestions(item)
    val folder = lib.openFolderSuggestion(item)
    if (tags.isEmpty() && folder == null) return
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.06f)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(13.dp), tint = accent)
            Spacer(Modifier.width(6.dp))
            Text(tr("suggest.header"), style = SectionLabel, modifier = Modifier.weight(1f))
            if (tags.size > 1) Text(
                tr("suggest.applyAllTags"), fontSize = 11.sp, color = accent,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.acceptTags(listOf(item.id)) }.padding(2.dp),
            )
        }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { name ->
                    val existing = lib.tags.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    val color = androidx.compose.ui.graphics.Color((existing?.color ?: de.bookmarksplus.data.TagRepository.colorFor(name)).toInt())
                    Row(
                        Modifier.height(24.dp).clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(50))
                            .clickable { state.acceptTags(listOf(item.id), only = name) }.padding(start = 9.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, tr("common.apply"), Modifier.size(11.dp), tint = color)
                        Spacer(Modifier.width(3.dp))
                        Text(lib.tagLabel(name), fontSize = 12.sp, color = Palette.Text)
                        Box(Modifier.padding(start = 3.dp).size(18.dp).clip(RoundedCornerShape(50)).clickable { state.dismissTag(item.id, name) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, tr("common.discard"), Modifier.size(11.dp), tint = Palette.TextFaint)
                        }
                    }
                }
            }
        }
        if (folder != null) {
            Spacer(Modifier.height(10.dp))
            val exists = lib.folderByPath(folder) != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null, Modifier.size(14.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(folder, fontSize = 12.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!exists) Text(tr("tags.new"), fontSize = 10.sp, color = accent, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    tr("common.move") to { state.acceptFolder(listOf(item.id)) },
                    tr("common.link") to { state.acceptFolder(listOf(item.id), link = true) },
                    tr("common.discard") to { state.dismissFolder(listOf(item.id)) },
                ).forEachIndexed { i, (label, action) ->
                    Text(
                        label, fontSize = 12.sp, color = if (i < 2) accent else Palette.TextFaint,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = action).padding(2.dp),
                    )
                }
            }
        }
    }
}

/** Show/remove tags and add new tags (with automatic color). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(state: AppState, ids: Set<Long>) {
    val lib = state.library
    val counts = HashMap<Long, Int>()
    ids.forEach { id -> lib.tagsOf[id].orEmpty().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    val assigned = counts.keys.mapNotNull { lib.tagsById[it] }.sortedBy { it.name.lowercase() }
    var input by remember(ids) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var adding by remember(ids) { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(adding) { if (adding) runCatching { focus.requestFocus() } }
    fun close() { adding = false; input = ""; menu = false }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        assigned.forEach { t ->
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { state.toggleTag(ids, t.id) }) {
                TagChip(t.copy(label = if (ids.size > 1) "${t.label} · ${counts[t.id]}" else t.label)) {
                    Icon(Icons.Filled.Close, tr("common.remove"), Modifier.size(12.dp), tint = Palette.TextMuted)
                }
            }
        }
        // subtle ＋ at the end of the tags; opens input and suggestions
        val label = if (adding) tr("common.done") else tr("tags.add")
        Row(
            Modifier.height(24.dp).clip(RoundedCornerShape(50))
                .border(1.dp, if (adding) Palette.Outline else Palette.OutlineSoft, RoundedCornerShape(50))
                .clickable { if (adding) close() else adding = true }
                .padding(horizontal = if (assigned.isEmpty() || adding) 9.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (adding) Icons.Filled.Close else Icons.Filled.Add, label, Modifier.size(13.dp), tint = Palette.TextMuted)
            if (assigned.isEmpty() && !adding) {
                Spacer(Modifier.width(4.dp))
                Text(tr("tags.tag"), fontSize = 12.sp, color = Palette.TextMuted)
            }
        }
    }
    if (!adding) return
    Spacer(Modifier.height(10.dp))
    val suggestions = lib.tags.filter { it.id !in counts.keys && (input.isBlank() || it.name.contains(input.trim().removePrefix("#"), ignoreCase = true) || it.label.contains(input.trim().removePrefix("#"), ignoreCase = true)) }.take(8)
    Box {
        Row(
            Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Outline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            run {
                PlainField(
                    value = input,
                    onValueChange = { input = it; menu = it.isNotBlank() },
                    placeholder = tr("tags.inputHint"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (input.isBlank()) { close(); return@KeyboardActions }
                        val exact = lib.tags.firstOrNull { it.name.equals(input.trim().removePrefix("#"), ignoreCase = true) || it.label.equals(input.trim().removePrefix("#"), ignoreCase = true) }
                        if (exact != null) state.toggleTag(ids, exact.id) else state.addNewTag(ids, input)
                        input = ""; menu = false
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                        if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && it.key == androidx.compose.ui.input.key.Key.Escape) { close(); true } else false
                    },
                )
            }
        }
        DropdownMenu(menu && suggestions.isNotEmpty(), onDismissRequest = { menu = false }) {
            suggestions.forEach { t ->
                DropdownMenuItem(text = { TagChip(t, small = true) }, onClick = { state.toggleTag(ids, t.id); input = ""; menu = false })
            }
        }
    }
    val popular = lib.tags.filter { it.id !in counts.keys }.sortedByDescending { it.usage }.take(8)
    if (popular.isNotEmpty() && input.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(tr("tags.frequent"), fontSize = 11.sp, color = Palette.TextFaint)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            popular.forEach { t ->
                Box(Modifier.clip(RoundedCornerShape(50)).clickable { state.toggleTag(ids, t.id) }) { TagChip(t, small = true) }
            }
        }
    }
}

@Composable
private fun ReadmeBox(state: AppState, item: Item) {
    val text = remember(item.id, item.scrapedAt, state.library) { state.readme(item.id).orEmpty() }
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Background)
            .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        Box(if (expanded) Modifier else Modifier.heightIn(max = 420.dp).clipToBounds()) {
            Markdown(state, text, readmeBase(item), Modifier.wrapContentHeight(Alignment.Top, unbounded = !expanded))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallLink(if (expanded) tr("readme.showLess") else tr("readme.full")) { expanded = !expanded }
            SmallLink(tr("readme.large")) { state.readmeDialogId = item.id }
            SmallLink(tr("common.edit")) { state.startEdit(item.id) }
        }
    }
}

@Composable
private fun SmallLink(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(2.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Text(label, fontSize = 12.sp, color = Palette.Text, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp))
        },
        delayMillis = 400,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, label, Modifier.size(18.dp), tint = Palette.TextMuted) }
    }
}

/** State of the link with redirect target and recheck. */
@Composable
private fun LinkStatus(state: AppState, item: Item) {
    val h = item.health
    val color = when (h) {
        de.bookmarksplus.data.LinkHealth.Ok -> androidx.compose.ui.graphics.Color(0xFF30A46C)
        de.bookmarksplus.data.LinkHealth.Dead, de.bookmarksplus.data.LinkHealth.Unreachable -> MaterialTheme.colorScheme.primary
        de.bookmarksplus.data.LinkHealth.Unknown -> Palette.TextFaint
        else -> Palette.Star
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        val detail = buildString {
            append(h.label)
            item.linkStatus?.takeIf { it > 0 }?.let { append(" · HTTP $it") }
            item.linkError?.let {
                append(" · ").append(
                    when (it) { "parked" -> tr("linkErr.parked"); "dns" -> tr("linkErr.dns"); "timeout" -> tr("linkErr.timeout"); "ssl" -> tr("linkErr.ssl"); "connect" -> tr("linkErr.connect"); "loop" -> tr("linkErr.loop"); else -> it },
                )
            }
        }
        Text(detail, fontSize = 12.sp, color = Palette.Text, modifier = Modifier.weight(1f))
        item.statusCheckedAt?.let { Muted(formatRelative(it)) }
    }
    if (h == de.bookmarksplus.data.LinkHealth.Redirect && item.finalUrl != null) {
        Spacer(Modifier.height(6.dp))
        Text("→ ${item.finalUrl}", fontSize = 12.sp, color = Palette.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (item.redirectsHome) Muted(tr("health.homeOnly"), maxLines = 2)
    }
    if (h == de.bookmarksplus.data.LinkHealth.Blocked) Muted(tr("health.blockedHint"), maxLines = 2)
    if (item.linkIgnore) Muted(tr("health.ignoredHint"), maxLines = 2)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallLink(tr("health.checkNow")) { state.checkLinks(listOf(item.id)) }
        if (h != de.bookmarksplus.data.LinkHealth.Unknown) SmallLink(tr("health.clear")) { state.clearLinkStatus(listOf(item.id)) }
        if (item.linkIgnore) SmallLink(tr("health.unignore")) { state.setLinkIgnore(listOf(item.id), false) }
        else SmallLink(tr("health.ignore")) { state.setLinkIgnore(listOf(item.id), true) }
        if (h == de.bookmarksplus.data.LinkHealth.Redirect && item.finalUrl != null && !item.redirectsHome) SmallLink(tr("health.applyTarget")) { state.applyRedirects(listOf(item.id)) }
        if (item.isDead || item.redirectsHome) SmallLink(tr("health.openArchive")) { state.open(item.copy(url = "https://web.archive.org/web/2020/${item.url}")) }
    }
}

/** Same URL stored multiple times: show other entries and merge them. */
@Composable
private fun DuplicateBox(state: AppState, item: Item) {
    val lib = state.library
    val others = lib.duplicatesOf(item)
    if (others.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, Palette.Star.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).background(Palette.Star.copy(alpha = 0.05f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${others.size} DUPLIKAT${if (others.size > 1) "E" else ""}", style = SectionLabel)
        others.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { state.select(o.id) }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(o.displayTitle, fontSize = 12.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val folder = lib.primaryFolder(o.id)?.let { lib.pathString(it) }.orEmpty()
                    Muted("$folder · ${formatDate(o.addDate)}", maxLines = 1)
                    if (o.url != item.url) Muted(o.url, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SmallLink(tr("dupes.mergeHere")) { state.mergeInto(item.id, others.map { it.id }) }
        }
        Muted(tr("dupes.mergeHint"), maxLines = 2)
    }
}

/** Notice for entries in the trash with restore / delete permanently. */
@Composable
private fun TrashBanner(state: AppState, ids: Set<Long>) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val single = ids.singleOrNull()?.let { state.library.trashById[it] }
        Text(if (single != null) tr("trash.since", formatDate(single.deletedAt)) else tr("trash.count", ids.size), fontSize = 12.sp, color = Palette.TextMuted)
        single?.trashPath?.let { Muted(tr("trash.restoreTo", it), maxLines = 2) }
        val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { state.restore(ids) }, contentPadding = pad) { Text(tr("backup.restore"), maxLines = 1) }
            OutlinedButton(onClick = { state.deleteForever(ids) }, contentPadding = pad) { Text(tr("delete.confirm"), maxLines = 1) }
        }
    }
}

/** Edit mode: title, URL, description, summary and README (Markdown/HTML raw). */
@Composable
private fun EditDetail(state: AppState, item: Item) {
    var title by remember(item.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(item.title, androidx.compose.ui.text.TextRange(0, item.title.length))) }
    var url by remember(item.id) { mutableStateOf(item.url) }
    var desc by remember(item.id) { mutableStateOf(item.description.orEmpty()) }
    var summary by remember(item.id) { mutableStateOf(item.summary.orEmpty()) }
    val originalReadme = remember(item.id) { if (item.hasReadme) state.readme(item.id).orEmpty() else "" }
    var readme by remember(item.id) { mutableStateOf(originalReadme) }
    var app by remember(item.id) { mutableStateOf(item.app) }
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(item.id) { runCatching { titleFocus.requestFocus() } }
    fun save() = state.saveEdit(item.id, title.text, url, desc, summary, readme.takeIf { it != originalReadme }, app)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)
            .onPreviewKeyEvent { e ->
                when {
                    e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown -> false
                    e.key == androidx.compose.ui.input.key.Key.Escape -> { state.editingId = null; true }
                    e.isCtrlPressed && e.key == androidx.compose.ui.input.key.Key.S -> { save(); true }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tr("common.edit"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.editingId = null }) { Icon(Icons.Filled.Close, tr("common.cancel"), tint = Palette.TextMuted) }
        }
        androidx.compose.material3.OutlinedTextField(
            title, { title = it }, label = { Text(tr("field.title")) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
        )
        if (item.aiTitle != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("KI-Titel: ${item.aiTitle}", Modifier.weight(1f), maxLines = 2)
            SmallLink(tr("common.applyLower")) { title = androidx.compose.ui.text.input.TextFieldValue(item.aiTitle!!) }
        }
        androidx.compose.material3.OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(desc, { desc = it }, label = { Text(tr("detail.description")) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(summary, { summary = it }, label = { Text(tr("detail.summary")) }, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth())
        AppEditor(app) { app = it }
        if (item.hasReadme || item.isRepo) {
            androidx.compose.material3.OutlinedTextField(
                readme, { readme = it }, label = { Text("README (Markdown / HTML)") }, minLines = 10, maxLines = 30,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, color = Palette.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            Muted(tr("edit.readmeHint"), maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save() }, enabled = title.text.isNotBlank() && url.isNotBlank()) { Text(tr("common.save")) }
            OutlinedButton(onClick = { state.editingId = null }) { Text(tr("common.cancel")) }
            Spacer(Modifier.weight(1f))
            Muted(tr("edit.keys"))
        }
    }
}

/** Clickable URL with a leading icon; right-click copies or opens. */
@Composable
private fun UrlRow(state: AppState, item: Item, url: String, icon: @Composable () -> Unit) {
    androidx.compose.foundation.ContextMenuArea(items = {
        listOf(
            androidx.compose.foundation.ContextMenuItem(tr("detail.copyUrl")) { state.copyText(url, tr("clip.urlCopied")) },
            androidx.compose.foundation.ContextMenuItem(tr("detail.copyTitleUrl")) { state.copyText("${item.displayTitle}\n$url", tr("detail.titleUrlCopied")) },
            androidx.compose.foundation.ContextMenuItem(tr("detail.copyMarkdown")) { state.copyText("[${item.displayTitle}]($url)", tr("detail.markdownCopied")) },
            androidx.compose.foundation.ContextMenuItem(tr("common.openInBrowser")) { state.open(item.copy(url = url)) },
        )
    }) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { state.open(item.copy(url = url)) }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Favicon of the project's website (for GitHub bookmarks the stored website icon), otherwise globe. */
@Composable
private fun SiteFavicon(state: AppState, item: Item, size: androidx.compose.ui.unit.Dp) {
    val hash = if (item.isGithubRepo) item.websiteIconHash else item.websiteIconHash ?: item.faviconHash
    val bmp = state.favicon(hash)
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(3.dp)))
    else Icon(Icons.Filled.Public, null, Modifier.size(size), tint = Palette.TextMuted)
}
