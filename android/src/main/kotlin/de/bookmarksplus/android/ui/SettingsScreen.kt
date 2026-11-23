package de.bookmarksplus.android.ui

import de.bookmarksplus.android.AppLang
import de.bookmarksplus.i18n.UiLang
import de.bookmarksplus.android.R
import de.bookmarksplus.android.str
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.bookmarksplus.android.BookmarkStore
import de.bookmarksplus.android.SyncTarget
import de.bookmarksplus.android.SyncWorker
import de.bookmarksplus.data.ContentLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: BookmarkStore, accent: Color, onAccent: (Color) -> Unit, onBack: () -> Unit, onSync: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncing by store.syncRunning.collectAsState()
    val status by store.syncStatus.collectAsState()
    val lib by store.library.collectAsState()

    var target by remember { mutableStateOf(store.syncTarget) }
    var server by remember { mutableStateOf(store.server) }
    var ftp by remember { mutableStateOf(store.ftp) }
    var passphrase by remember { mutableStateOf("") }
    var keyReady by remember { mutableStateOf(store.keyReady) }
    var media by remember { mutableStateOf(store.syncMedia) }
    var wifiOnly by remember { mutableStateOf(store.syncWifiOnly) }
    var interval by remember { mutableStateOf(store.syncInterval) }
    var lang by remember { mutableStateOf(store.contentLang) }
    var uiLang by remember { mutableStateOf(AppLang.get(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun reschedule() = scope.launch(Dispatchers.IO) { SyncWorker.schedule(context, store) }
    fun saveTarget() { store.server = server; store.ftp = ftp }
    fun job(block: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            message = try { block() } finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Palette.Background) { Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(str(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.common_back)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Sync")
            Text(
                str(R.string.settings_sync_hint1) +
                    str(R.string.settings_sync_hint2),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SyncTarget.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = target == t,
                        onClick = { if (target != t) { target = t; store.syncTarget = t; keyReady = store.keyReady; reschedule() } },
                        shape = SegmentedButtonDefaults.itemShape(i, SyncTarget.entries.size),
                    ) { Text(t.label) }
                }
            }

            when (target) {
                SyncTarget.Server -> {
                    Field(str(R.string.field_server_url), server.url, { server = server.copy(url = it); saveTarget() }, "https://sync.example.org", KeyboardType.Uri)
                    Field(str(R.string.field_username), server.user, { server = server.copy(user = it); saveTarget() })
                    Field(str(R.string.field_password), server.password, { server = server.copy(password = it); saveTarget() }, secret = true)
                }
                SyncTarget.Ftp -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Host", ftp.host, { ftp = ftp.copy(host = it); saveTarget() }, modifier = Modifier.weight(1f), keyboard = KeyboardType.Uri)
                        Field("Port", ftp.port, { ftp = ftp.copy(port = it); saveTarget() }, modifier = Modifier.width(96.dp), keyboard = KeyboardType.Number)
                    }
                    Field(str(R.string.field_username), ftp.user, { ftp = ftp.copy(user = it); saveTarget() })
                    Field(str(R.string.field_password), ftp.password, { ftp = ftp.copy(password = it); saveTarget() }, secret = true)
                    Field(str(R.string.field_directory), ftp.dir, { ftp = ftp.copy(dir = it); saveTarget() })
                    SwitchRow("FTPS (TLS)", str(R.string.field_ftps), ftp.tls) { ftp = ftp.copy(tls = it); saveTarget() }
                }
                SyncTarget.None -> {}
            }

            if (target != SyncTarget.None) {
                OutlinedButton(onClick = { job { store.testConnection() } }, enabled = !busy) { Text(str(R.string.common_test_connection)) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (keyReady) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(str(R.string.sync_key_ready), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(str(R.string.settings_passphrase_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                    }
                }
                Field(if (keyReady) str(R.string.settings_passphrase_again) else "Passphrase", passphrase, { passphrase = it }, secret = true)
                Button(
                    onClick = {
                        val p = passphrase
                        job {
                            val msg = store.setPassphrase(p)
                            keyReady = store.keyReady
                            if (keyReady) { passphrase = ""; reschedule(); onSync() }
                            msg
                        }
                    },
                    enabled = !busy && passphrase.isNotEmpty(),
                ) { Text(str(R.string.settings_setup_key)) }

                SwitchRow(str(R.string.settings_sync_thumbs), str(R.string.settings_sync_thumbs_hint), media) {
                    media = it; store.syncMedia = it
                }
                SwitchRow(str(R.string.settings_wifi_only), str(R.string.settings_wifi_only_hint), wifiOnly) {
                    wifiOnly = it; store.syncWifiOnly = it; reschedule()
                }
                Text(str(R.string.settings_auto_sync), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to str(R.string.common_off), 15 to "15 min", 60 to "1 h", 360 to "6 h").forEach { (m, label) ->
                        FilterChip(selected = interval == m, onClick = { interval = m; store.syncInterval = m; reschedule() }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onSync, enabled = keyReady && !syncing) { Text(str(R.string.sync_now)) }
                    if (syncing) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted) }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            message?.let {
                Surface(color = Palette.SurfaceHigh, shape = MaterialTheme.shapes.small) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionTitle(str(R.string.settings_appearance))
            Text(str(R.string.settings_language), style = MaterialTheme.typography.bodyMedium)
            val activity = context as? android.app.Activity
            SingleChoiceSegmentedButtonRow {
                UiLang.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = uiLang == l,
                        onClick = {
                            if (uiLang != l) {
                                uiLang = l
                                AppLang.set(context, l)
                                scope.launch(Dispatchers.IO) { store.reload() }
                                activity?.recreate()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, UiLang.entries.size),
                    ) { Text(l.label) }
                }
            }
            Text(str(R.string.settings_accent), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ACCENTS.forEach { a ->
                    val selected = a.color == accent
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(a.color)
                            .border(2.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                            .clickable { onAccent(a.color) },
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Default.Check, a.name, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
            Text(str(R.string.settings_content_lang), style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow {
                ContentLang.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = lang == l,
                        onClick = { lang = l; store.contentLang = l; scope.launch(Dispatchers.IO) { store.reload() } },
                        shape = SegmentedButtonDefaults.itemShape(i, ContentLang.entries.size),
                    ) { Text(l.label) }
                }
            }

            SectionTitle(str(R.string.settings_about))
            Text(
                str(R.string.settings_about_counts, lib.items.size, lib.folders.size, lib.tags.size) + "\n" +
                    str(R.string.settings_about_text1) +
                    str(R.string.settings_about_text2),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
            )
        }
    }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.size(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value, onChange, modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
    )
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}
