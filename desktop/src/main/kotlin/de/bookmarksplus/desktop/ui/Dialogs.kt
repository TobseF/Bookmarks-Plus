package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import de.bookmarksplus.desktop.AppState

/** All open dialogs of the app. */
@Composable
fun Dialogs(state: AppState) {
    state.picker?.let { FolderPickerDialog(state) }
    state.nameDialog?.let { req ->
        NameDialog(req.title, req.initial, req.confirmLabel, onDismiss = { state.nameDialog = null }) {
            state.nameDialog = null
            req.onConfirm(it)
        }
    }
    state.confirm?.let { req ->
        AlertDialog(
            onDismissRequest = { state.confirm = null },
            title = { Text(req.title) },
            text = { Text(req.text, fontSize = 13.sp, color = Palette.TextMuted) },
            containerColor = Palette.SurfaceHigh,
            confirmButton = {
                Button(onClick = { state.confirm = null; req.onConfirm() }, colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFD93D42), contentColor = androidx.compose.ui.graphics.Color.White)) {
                    Text(req.confirmLabel)
                }
            },
            dismissButton = { TextButton(onClick = { state.confirm = null }) { Text(tr("common.cancel")) } },
        )
    }
    state.readmeDialogId?.let { id -> ReadmeDialog(state, id) }
    state.addRequest?.let { AddDialog(state, it) }
    if (state.firstSyncChoice) FirstSyncDialog(state)
    state.pairDialog?.let { PairingDialog(state, it) }
    state.passphraseRequest?.let { PassphraseDialog(state, it) }
}

/** Passphrase for encrypted export/import. */
@Composable
private fun PassphraseDialog(state: AppState, req: de.bookmarksplus.desktop.PassphraseRequest) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    val pw = androidx.compose.ui.text.input.PasswordVisualTransformation()
    val ok = if (req.repeat) pass.length >= 10 && pass == pass2 else pass.isNotEmpty()
    fun confirm() { if (ok) { state.passphraseRequest = null; req.onConfirm(pass.toCharArray()) } }
    AlertDialog(
        onDismissRequest = { state.passphraseRequest = null },
        title = { Text(req.title) },
        containerColor = Palette.SurfaceHigh,
        text = {
            Column(Modifier.width(460.dp)) {
                Text(req.text, fontSize = 13.sp, color = Palette.TextMuted, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = pw, modifier = Modifier.fillMaxWidth())
                if (req.repeat) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(pass2, { pass2 = it }, label = { Text(tr("passphrase.repeat")) }, singleLine = true, visualTransformation = pw, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            pass.length < 10 -> tr("passphrase.minLength")
                            pass != pass2 -> tr("passphrase.mismatch")
                            else -> ""
                        },
                        fontSize = 11.sp, color = Palette.TextFaint,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { confirm() }, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = { state.passphraseRequest = null }) { Text(tr("common.cancel")) } },
    )
}

/** Confirm pairing of the browser extension (same code as in the extension). */
@Composable
private fun PairingDialog(state: AppState, req: de.bookmarksplus.desktop.PairDialog) {
    fun answer(ok: Boolean) { req.result.complete(ok); state.pairDialog = null }
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text(tr("pair.title")) },
        containerColor = Palette.SurfaceHigh,
        text = {
            Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("pair.text", req.client), fontSize = 13.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(16.dp))
                Text(req.code, fontSize = 40.sp, color = Palette.Text, letterSpacing = 8.sp)
                Spacer(Modifier.height(8.dp))
                Text(tr("pair.confirmHint"), fontSize = 12.sp, color = Palette.TextFaint)
            }
        },
        confirmButton = { Button(onClick = { answer(true) }) { Text(tr("pair.connect")) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(tr("pair.reject")) } },
    )
}

/** First sync when data already exists locally and at the target. */
@Composable
private fun FirstSyncDialog(state: AppState) {
    fun pick(mode: de.bookmarksplus.sync.FirstSyncMode) { state.firstSyncChoice = false; state.syncNow(mode) }
    AlertDialog(
        onDismissRequest = { state.firstSyncChoice = false },
        title = { Text(tr("firstSync.title")) },
        containerColor = Palette.SurfaceHigh,
        text = {
            Column(Modifier.width(500.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(tr("firstSync.text"), fontSize = 13.sp, color = Palette.TextMuted)
                listOf(
                    Triple(de.bookmarksplus.sync.FirstSyncMode.TakeRemote, tr("firstSync.pull"), tr("firstSync.pullText")),
                    Triple(de.bookmarksplus.sync.FirstSyncMode.Merge, tr("dupes.merge"), tr("firstSync.mergeText")),
                    Triple(de.bookmarksplus.sync.FirstSyncMode.ReplaceRemote, tr("firstSync.push"), tr("firstSync.pushText")),
                ).forEach { (mode, title, text) ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface).clickable { pick(mode) }.padding(12.dp),
                    ) {
                        Text(title, fontSize = 14.sp, color = Palette.Text)
                        Text(text, fontSize = 12.sp, color = Palette.TextFaint)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { state.firstSyncChoice = false }) { Text(tr("common.later")) } },
    )
}

/** New bookmark: URL (prefilled from the clipboard), optional title, target inbox or current folder. */
@Composable
private fun AddDialog(state: AppState, req: de.bookmarksplus.desktop.AddRequest) {
    var url by remember { mutableStateOf(req.url) }
    var title by remember { mutableStateOf(req.title) }
    val current = state.filter.folderId?.takeIf { !state.filter.trash && it in state.library.folders }
    val inbox = remember { state.inboxFolderId(create = false) }
    var toCurrent by remember { mutableStateOf(current != null && current != inbox) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val valid = url.trim().let { it.length > 3 && (it.contains('.') || it.contains("://")) }
    fun submit() {
        if (!valid) return
        state.addRequest = null
        state.addBookmark(url, title, if (toCurrent) current else null)
    }
    AlertDialog(
        onDismissRequest = { state.addRequest = null },
        title = { Text(tr("add.title")) },
        containerColor = Palette.SurfaceHigh,
        text = {
            Column(Modifier.width(480.dp)) {
                OutlinedTextField(
                    url, { url = it }, singleLine = true, label = { Text("URL") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    title, { title = it }, singleLine = true, label = { Text(tr("add.titleField")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("add.saveIn"), fontSize = 13.sp, color = Palette.TextMuted, modifier = Modifier.padding(end = 10.dp))
                    TargetChip("Inbox", !toCurrent) { toCurrent = false }
                    if (current != null && current != inbox) {
                        Spacer(Modifier.width(6.dp))
                        TargetChip(state.library.folders[current]?.title.orEmpty(), toCurrent) { toCurrent = true }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("add.hintStart") + (if (state.aiConfig.usable) tr("add.hintAi") else "") + tr("add.hintEnd"),
                    fontSize = 11.sp, color = Palette.TextFaint,
                )
            }
        },
        confirmButton = { Button(onClick = { submit() }, enabled = valid) { Text(tr("common.save")) } },
        dismissButton = { TextButton(onClick = { state.addRequest = null }) { Text(tr("common.cancel")) } },
    )
}

@Composable
private fun TargetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else Palette.Surface).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(14.dp), tint = if (selected) androidx.compose.ui.graphics.Color.White else Palette.TextMuted)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = if (selected) androidx.compose.ui.graphics.Color.White else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        containerColor = Palette.SurfaceHigh,
        text = {
            OutlinedTextField(
                value, { value = it }, singleLine = true, label = { Text(tr("field.name")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.text.isNotBlank()) onConfirm(value.text) }),
                modifier = Modifier.width(380.dp).focusRequester(focus),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("common.cancel")) } },
    )
}

/** Choose target folder: search over folder paths, otherwise indented tree. */
@Composable
private fun FolderPickerDialog(state: AppState) {
    val req = state.picker ?: return
    val lib = state.library
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Long?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // folders in tree order (depth, path)
    val all = remember(lib) {
        val out = ArrayList<Triple<Long, Int, String>>()
        fun walk(id: Long, depth: Int) {
            lib.childrenOf(id).forEach { f -> out += Triple(f.id, depth, lib.pathString(f.id)); walk(f.id, depth + 1) }
        }
        lib.rootId?.let { walk(it, 0) }
        out
    }
    val excluded = remember(req) { req.folderMove?.let { lib.descendants(it) }.orEmpty() }
    // default: the first two levels are expanded (root folder and its subfolders), deeper ones collapsed
    val expandable = remember(all) { all.map { it.first }.filter { lib.childrenOf(it).isNotEmpty() }.toSet() }
    var expanded by remember(all) { mutableStateOf(all.filter { it.second <= 1 && it.first in expandable }.map { it.first }.toSet()) }
    val shown = all.filter { (id, _, path) ->
        id !in excluded && (
            if (query.isNotBlank()) query.trim().split(' ').all { path.contains(it, ignoreCase = true) }
            else generateSequence(lib.folders[id]?.parentId) { lib.folders[it]?.parentId }.takeWhile { it != lib.rootId }.all { it in expanded }
        )
    }
    val title = when {
        req.folderMove != null -> tr("moveDlg.folderTitle", lib.folders[req.folderMove]?.title)
        req.link -> tr("moveDlg.linkTitle", req.ids.size)
        else -> tr("moveDlg.moveTitle", req.ids.size)
    }
    fun apply(target: Long) {
        state.picker = null
        if (req.folderMove != null) state.moveFolder(req.folderMove, target) else state.moveItems(req.ids, target, req.link)
    }
    AlertDialog(
        onDismissRequest = { state.picker = null },
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        containerColor = Palette.SurfaceHigh,
        text = {
            Column(Modifier.width(520.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query, { query = it; chosen = null }, singleLine = true, label = { Text(tr("moveDlg.search")) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { (chosen ?: shown.firstOrNull()?.first)?.let(::apply) }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    androidx.compose.material3.IconButton(onClick = { expanded = expandable }) { Icon(Icons.Filled.UnfoldMore, tr("moveDlg.expandAll"), tint = Palette.TextMuted) }
                    androidx.compose.material3.IconButton(onClick = { expanded = emptySet() }) { Icon(Icons.Filled.UnfoldLess, tr("moveDlg.collapseAll"), tint = Palette.TextMuted) }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                    if (req.folderMove != null && query.isBlank()) item {
                        PickerRow(tr("moveDlg.topLevelParen"), 0, chosen == lib.rootId) { chosen = lib.rootId }
                    }
                    items(shown, key = { it.first }) { (id, depth, path) ->
                        val tree = query.isBlank()
                        PickerRow(
                            if (tree) lib.folders[id]?.title.orEmpty() else path, if (tree) depth else 0, chosen == id,
                            expandState = if (tree && id in expandable) id in expanded else null,
                            onToggle = { expanded = if (id in expanded) expanded - id else expanded + id },
                            onDouble = { apply(id) },
                        ) { chosen = id }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("moveDlg.newSubIn") + (chosen?.let { lib.folders[it]?.title } ?: tr("moveDlg.topLevel")) + " …",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable {
                            val parent = chosen ?: lib.rootId ?: return@clickable
                            val keep = req
                            state.picker = null
                            state.nameDialog = de.bookmarksplus.desktop.NameRequest(tr("folder.new"), query.trim(), tr("moveDlg.createApply")) { name ->
                                if (name.isNotBlank()) {
                                    val id = state.repo.createFolder(parent, name)
                                    state.reload()
                                    state.picker = keep
                                    apply(id)
                                }
                            }
                        }.padding(2.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { chosen?.let(::apply) }, enabled = chosen != null) {
                Text(if (req.link) tr("common.link") else tr("common.move"))
            }
        },
        dismissButton = { TextButton(onClick = { state.picker = null }) { Text(tr("common.cancel")) } },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PickerRow(
    label: String, depth: Int, selected: Boolean, expandState: Boolean? = null, onToggle: () -> Unit = {},
    onDouble: () -> Unit = {}, onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = onDouble)
            .padding(start = (4 + depth * 16).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // expand/collapse arrow; folders without subfolders keep the width for alignment
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).let { if (expandState != null) it.clickable(onClick = onToggle) else it }, contentAlignment = Alignment.Center) {
            if (expandState != null) Icon(
                if (expandState) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, if (expandState) tr("common.collapse") else tr("common.expand"),
                Modifier.size(16.dp), tint = Palette.TextMuted,
            )
        }
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Filled.Folder, null, Modifier.size(15.dp), tint = if (selected) accent else Palette.TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.Text)
    }
}

/** README in a separate, large window. */
@Composable
private fun ReadmeDialog(state: AppState, id: Long) {
    val item = state.library.anyItem(id) ?: return
    val text = remember(id, item.scrapedAt) { state.readme(id).orEmpty() }
    DialogWindow(
        onCloseRequest = { state.readmeDialogId = null },
        title = tr("readme.title", item.displayTitle),
        state = rememberDialogState(size = DpSize(980.dp, 860.dp)),
    ) {
        AppTheme(state.accent) {
            Surface(Modifier.fillMaxSize(), color = Palette.Background, contentColor = Palette.Text) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 28.dp)) {
                    Markdown(state, text, readmeBase(item))
                }
            }
        }
    }
}

fun readmeBase(item: de.bookmarksplus.data.Item): MdBase =
    (de.bookmarksplus.util.Urls.githubRepo(item.repoUrl ?: item.url))?.let { (o, r) -> MdBase.github(o, r) } ?: MdBase(item.url, item.url)
