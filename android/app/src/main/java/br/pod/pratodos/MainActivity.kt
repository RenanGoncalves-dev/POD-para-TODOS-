package br.pod.pratodos

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val Red = Color(0xFFFF4D4D)
private val DarkBg = Color(0xFF0F0F12)
private val DarkCard = Color(0xFF1A1A20)
private val LightBg = Color(0xFFF4F5F8)
private val LightCard = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            val isLight by vm.lightTheme.collectAsState()
            val scheme = if (isLight) {
                lightColorScheme(primary = Red, background = LightBg, surface = LightCard, onPrimary = Color.White)
            } else {
                darkColorScheme(primary = Red, background = DarkBg, surface = DarkCard, onPrimary = Color.White)
            }
            MaterialTheme(colorScheme = scheme) { Screen(vm, isLight) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // reconhece cartão inserido/removido
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                Regex("""https?://\S+""").find(t)?.value?.let { vm.download(it) }
            }
            @Suppress("DEPRECATION")
            (i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                vm.importUris(listOf(uri))
            }
        }
    }
}

private fun catIcon(c: Category) = when (c) { Category.PODCASTS -> "🎙"; Category.MUSICAS -> "🎵"; Category.DJSETS -> "🎛" }

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun fmtSize(b: Long) = if (b > 1 shl 30) "%.1f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

private fun freeSpace(v: Volume) = runCatching { StatFs(v.dir.absolutePath).availableBytes }.getOrDefault(0L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: MainVm, isLight: Boolean) {
    val ctx = LocalContext.current
    val bgColor = if (isLight) LightBg else DarkBg
    val cardColor = if (isLight) LightCard else DarkCard
    val activeCardColor = if (isLight) Color(0xFFFFE5E5) else Color(0xFF2A1A1E)

    val episodes by vm.episodes.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val selectedPlId by vm.selectedPlaylistId.collectAsState()
    val jobs by DownloadManager.jobs.collectAsState()
    val ready by DownloadManager.ready.collectAsState()
    val initErr by DownloadManager.initError.collectAsState()
    val p by vm.ui.collectAsState()
    val selected by vm.selected.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val category by vm.category.collectAsState()
    val transferStatus by vm.transferStatus.collectAsState()
    val hasCookies by vm.hasCookies.collectAsState()
    val ytDlpUpdating by vm.ytDlpUpdating.collectAsState()
    val ytDlpUpdateStatus by vm.ytDlpUpdateStatus.collectAsState()
    val editingEpisode by vm.editingEpisode.collectAsState()
    val editStatus by vm.editStatus.collectAsState()
    val isEditing by vm.isEditing.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    val catPlaylists = playlists.filter { it.category == category }
    val activePlaylist = catPlaylists.firstOrNull { it.id == selectedPlId }
    val shown = episodes.filter { ep ->
        ep.category == category &&
            (activePlaylist == null || ep.id in activePlaylist.items) &&
            (searchQuery.isBlank() || ep.title.contains(searchQuery, ignoreCase = true))
    }

    var moving by remember { mutableStateOf<Episode?>(null) }
    var playlistTarget by remember { mutableStateOf<Episode?>(null) }
    var creatingPlaylistWithEp by remember { mutableStateOf<Episode?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showMergePlaylistsDialog by remember { mutableStateOf(false) }
    var showAntiBotDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showStorage by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Episode?>(null) }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pickAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val pickCookieLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("POD", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor),
                actions = {
                    IconButton(onClick = { showAntiBotDialog = true }) {
                        Text("🍪", fontSize = 18.sp)
                    }
                    IconButton(onClick = { vm.toggleTheme() }) {
                        Icon(if (isLight) Icons.Default.DarkMode else Icons.Default.LightMode, "Tema")
                    }
                    TextButton(onClick = { showStorage = true }) {
                        Icon(if (selected.removable) Icons.Default.SdCard else Icons.Default.PhoneAndroid, null, tint = Red)
                        Spacer(Modifier.width(6.dp))
                        Text(if (selected.removable) "Cartão" else "Celular")
                    }
                },
            )
        },
        bottomBar = { if (p.episode != null) PlayerBar(p, vm, isLight) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.values().forEach { c ->
                        val n = episodes.count { it.category == c }
                        FilterChip(
                            selected = c == category, onClick = { vm.setCategory(c) },
                            label = { Text("${catIcon(c)} ${c.label} ($n)", maxLines = 1, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red, selectedLabelColor = Color.White),
                        )
                    }
                }
            }

            // Barra de Playlists da categoria atual
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = activePlaylist == null,
                        onClick = { vm.selectPlaylist(null) },
                        label = { Text("Todas", fontSize = 12.sp) },
                    )
                    catPlaylists.forEach { pl ->
                        FilterChip(
                            selected = activePlaylist?.id == pl.id,
                            onClick = { vm.selectPlaylist(pl.id) },
                            label = { Text("🎶 ${pl.name} (${pl.items.size})", fontSize = 12.sp) },
                        )
                    }
                    AssistChip(
                        onClick = { creatingPlaylistWithEp = null; newPlaylistName = ""; showNewPlaylistDialog = true },
                        label = { Text("+ Nova playlist", fontSize = 12.sp, color = Red) },
                    )
                    if (catPlaylists.size >= 2) {
                        AssistChip(
                            onClick = { showMergePlaylistsDialog = true },
                            label = { Text("🔀 Juntar playlists", fontSize = 12.sp) },
                        )
                    }
                }
            }

            item {
                val internalVol = volumes.firstOrNull { !it.removable }
                val sdVol = volumes.firstOrNull { it.removable }
                val internalEpsCount = if (internalVol != null) {
                    episodes.count { it.file.absolutePath.startsWith(internalVol.dir.absolutePath) }
                } else 0

                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(cardColor).padding(14.dp)) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Link (YouTube, Spotify, Jovem Nerd, .mp3) ou nome") },
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.primaryClip?.getItemAt(0)?.text?.let { url = it.toString() }
                            }) { Icon(Icons.Default.ContentPaste, "Colar") }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.download(url); url = "" }, enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    ) {
                        val dest = category.label + (activePlaylist?.let { " → ${it.name}" } ?: "")
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Baixar áudio em $dest", fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = Red)
                        Spacer(Modifier.width(8.dp))
                        Text("Importar arquivo (.mp3/.m4a) do celular", fontSize = 13.sp)
                    }
                    if (sdVol != null && internalVol != null && internalEpsCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.transferAllBetweenVolumes(internalVol, sdVol, onlyCategory = null) },
                            enabled = transferStatus == null,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.SdCard, null, tint = Red)
                            Spacer(Modifier.width(8.dp))
                            Text("📲➡️💾 Passar da Memória p/ Cartão SD ($internalEpsCount)", fontSize = 13.sp)
                        }
                    }
                    if (transferStatus != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(transferStatus!!, color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        when {
                            initErr != null -> "⚠ Falha ao iniciar: $initErr"
                            !ready -> "Preparando o baixador (1ª vez demora um pouco)…"
                            else -> "Salvando somente áudio + capa em: ${selected.label}"
                        },
                        color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (jobs.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloads", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { DownloadManager.clearFinished() }) { Text("Limpar") }
                    }
                }
                items(jobs.reversed(), key = { it.id }) { j ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardColor).padding(12.dp)) {
                        Text(j.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        when (j.status) {
                            Status.RUNNING -> if (j.progress > 0f) LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            else -> {}
                        }
                        Text(
                            (if (j.status == Status.ERROR) "⚠ " else if (j.status == Status.DONE) "✓ " else "") + j.text,
                            fontSize = 12.sp, color = if (j.status == Status.ERROR) Red else Color.Gray, maxLines = 2,
                        )
                        if (j.status == Status.ERROR && (j.text.contains("bot", ignoreCase = true) || j.text.contains("confirm", ignoreCase = true))) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = { showAntiBotDialog = true },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("🍪 Toque aqui para resolver o anti-bot", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("🔍 Filtrar na biblioteca…") },
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, "Limpar") }
                        }
                    },
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val headerTitle = activePlaylist?.let { "🎶 ${it.name} (${shown.size})" } ?: "${category.label} (${shown.size})"
                    Text(headerTitle, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (shown.isNotEmpty()) {
                        TextButton(onClick = { vm.play(shown.first(), shown) }) {
                            Icon(Icons.Default.PlayArrow, null, tint = Red)
                            Spacer(Modifier.width(4.dp))
                            Text("Tocar tudo", color = Red)
                        }
                    }
                    if (activePlaylist != null) {
                        IconButton(onClick = { vm.deletePlaylist(activePlaylist) }) {
                            Icon(Icons.Default.DeleteOutline, "Excluir playlist", tint = Color.Gray)
                        }
                    }
                }
            }

            if (shown.isEmpty()) item {
                val msg = if (activePlaylist != null) {
                    "A playlist \"${activePlaylist.name}\" está vazia. Toque no ícone de playlist em qualquer áudio ou baixe um link direto nela."
                } else {
                    "Nada em ${category.label} ainda. Cole um link ou digite o nome acima para baixar."
                }
                Text(msg, color = Color.Gray)
            }

            items(shown, key = { it.file.absolutePath }) { ep ->
                val active = p.episode?.id == ep.id
                val epPls = catPlaylists.filter { ep.id in it.items }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (active) activeCardColor else cardColor)
                        .clickable { vm.play(ep, shown) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = ep.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A32)),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val resumeText = if (ep.savedPosMs > 10_000L) " · ⏱ ${fmtTime(ep.savedPosMs)}" else ""
                        val plText = if (epPls.isNotEmpty()) " · 🎶 " + epPls.joinToString { it.name } else ""
                        Text("${fmtSize(ep.size)} · ${ep.volume}$resumeText$plText", color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        if (active && p.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null,
                        tint = Red, modifier = Modifier.size(30.dp),
                    )
                    IconButton(onClick = { vm.setEditingEpisode(ep) }) { Icon(Icons.Default.ContentCut, "Cortar / Editar áudio", tint = Color.Gray) }
                    IconButton(onClick = { playlistTarget = ep }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Playlists", tint = Color.Gray) }
                    IconButton(onClick = { moving = ep }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Mover", tint = Color.Gray) }
                    IconButton(onClick = { confirmDelete = ep }) { Icon(Icons.Default.DeleteOutline, "Apagar", tint = Color.Gray) }
                }
            }
        }
    }

    if (showNewPlaylistDialog) AlertDialog(
        onDismissRequest = { showNewPlaylistDialog = false },
        title = { Text("Nova playlist em ${category.label}") },
        text = {
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                singleLine = true,
                placeholder = { Text("Ex: Favoritas, Treino, Viagem") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.createPlaylist(newPlaylistName, creatingPlaylistWithEp?.id)
                showNewPlaylistDialog = false
            }) { Text("Criar", color = Red) }
        },
        dismissButton = { TextButton(onClick = { showNewPlaylistDialog = false }) { Text("Cancelar") } },
    )

    playlistTarget?.let { ep ->
        AlertDialog(
            onDismissRequest = { playlistTarget = null },
            title = { Text("Organizar em playlists") },
            text = {
                Column {
                    Text(ep.title, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    val list = playlists.filter { it.category == ep.category }
                    list.forEach { pl ->
                        val has = ep.id in pl.items
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleInPlaylist(pl, ep) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = has, onCheckedChange = { vm.toggleInPlaylist(pl, ep) })
                            Text(pl.name)
                        }
                    }
                    TextButton(onClick = {
                        playlistTarget = null
                        creatingPlaylistWithEp = ep
                        newPlaylistName = ""
                        showNewPlaylistDialog = true
                    }) {
                        Text("+ Criar nova playlist com este áudio", color = Red)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { playlistTarget = null }) { Text("Concluído") } },
        )
    }

    if (showMergePlaylistsDialog && catPlaylists.size >= 2) {
        var srcPl by remember { mutableStateOf(catPlaylists.first()) }
        var dstPl by remember { mutableStateOf(activePlaylist ?: catPlaylists.last()) }
        var deleteSrc by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showMergePlaylistsDialog = false },
            title = { Text("🔀 Juntar Playlists (Spotify + YouTube)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Copiar faixas da playlist:", fontSize = 12.sp, color = Color.Gray)
                    catPlaylists.forEach { pl ->
                        Row(
                            Modifier.fillMaxWidth().clickable { srcPl = pl },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = srcPl.id == pl.id, onClick = { srcPl = pl })
                            Text("${pl.name} (${pl.items.size})", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Para dentro da playlist de destino:", fontSize = 12.sp, color = Color.Gray)
                    catPlaylists.forEach { pl ->
                        Row(
                            Modifier.fillMaxWidth().clickable { dstPl = pl },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = dstPl.id == pl.id, onClick = { dstPl = pl })
                            Text("${pl.name} (${pl.items.size})", fontSize = 13.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteSrc = !deleteSrc },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteSrc, onCheckedChange = { deleteSrc = it })
                        Text("Excluir playlist de origem após juntar", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = srcPl.id != dstPl.id,
                    onClick = {
                        vm.mergePlaylists(srcPl.id, dstPl.id, deleteSrc)
                        showMergePlaylistsDialog = false
                    },
                ) { Text("Juntar", color = Red) }
            },
            dismissButton = { TextButton(onClick = { showMergePlaylistsDialog = false }) { Text("Cancelar") } },
        )
    }

    if (showStorage) {
        val internalVol = volumes.firstOrNull { !it.removable }
        val sdVol = volumes.firstOrNull { it.removable }
        val internalEps = if (internalVol != null) episodes.filter { it.file.absolutePath.startsWith(internalVol.dir.absolutePath) } else emptyList()
        val internalPods = internalEps.filter { it.category == Category.PODCASTS }
        val sdEps = if (sdVol != null) episodes.filter { it.file.absolutePath.startsWith(sdVol.dir.absolutePath) } else emptyList()

        AlertDialog(
            onDismissRequest = { showStorage = false },
            confirmButton = { TextButton(onClick = { showStorage = false }) { Text("OK") } },
            title = { Text("Armazenamento e Cartão SD") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    volumes.forEach { v ->
                        val vEps = episodes.filter { it.file.absolutePath.startsWith(v.dir.absolutePath) }
                        val vBytes = vEps.sumOf { it.size }
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.select(v) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v.dir == selected.dir, onClick = { vm.select(v) })
                            Column {
                                Text(v.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${vEps.size} áudios (${fmtSize(vBytes)}) · ${fmtSize(freeSpace(v))} livres",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("🔄 Transferir arquivos entre memórias", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                    if (transferStatus != null) {
                        Text(transferStatus!!, color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (internalVol != null && sdVol != null) {
                        Button(
                            onClick = { vm.transferAllBetweenVolumes(internalVol, sdVol, onlyCategory = Category.PODCASTS) },
                            enabled = internalPods.isNotEmpty() && transferStatus == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("📲➡️💾 Passar Podcasts p/ SD (${internalPods.size})", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { vm.transferAllBetweenVolumes(internalVol, sdVol, onlyCategory = null) },
                            enabled = internalEps.isNotEmpty() && transferStatus == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("📲➡️💾 Passar TUDO p/ Cartão SD (${internalEps.size})", fontSize = 13.sp)
                        }
                        if (sdEps.isNotEmpty()) {
                            TextButton(
                                onClick = { vm.transferAllBetweenVolumes(sdVol, internalVol, onlyCategory = null) },
                                enabled = transferStatus == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("💾➡️📲 Passar do Cartão SD p/ Celular (${sdEps.size})", fontSize = 12.sp)
                            }
                        }
                    } else {
                        Text(
                            "Cartão SD não detectado no momento. Insira o cartão de memória no celular para liberar os botões de transferir seus podcasts da memória interna para o SD.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { showStorage = false; showAntiBotDialog = true }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("🍪 Anti-Bot & Cookies do YouTube", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(if (hasCookies) "✓ cookies.txt ativo" else "Usando clientes móveis (Android/iOS)", fontSize = 11.sp, color = Color.Gray)
                        }
                        TextButton(onClick = { showStorage = false; showAntiBotDialog = true }) {
                            Text("Configurar", color = Red, fontSize = 12.sp)
                        }
                    }
                }
            },
        )
    }

    if (showAntiBotDialog) AlertDialog(
        onDismissRequest = { showAntiBotDialog = false },
        title = { Text("🍪 Anti-Bot do YouTube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Se o YouTube bloquear downloads pedindo login para confirmar que você não é robô ('Sign in to confirm you're not a bot'), resolva com as opções abaixo:",
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
                if (ytDlpUpdateStatus != null) {
                    Text(ytDlpUpdateStatus!!, color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { vm.updateYtDlp() },
                    enabled = !ytDlpUpdating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (ytDlpUpdating) "Atualizando…" else "🔄 1. Atualizar Motor yt-dlp", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { pickCookieLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("📁 2. Importar cookies.txt", fontSize = 13.sp)
                }
                if (hasCookies) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("✓ cookies.txt ativo no app", color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { vm.removeCookies() }) { Text("Remover", color = Color.Gray, fontSize = 12.sp) }
                    }
                } else {
                    Text(
                        "Dica: No PC, use a extensão 'Get cookies.txt LOCALLY' no Chrome/Firefox logado no YouTube, salve o arquivo e envie para o celular.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAntiBotDialog = false }) { Text("Fechar") } },
    )

    moving?.let { ep ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Mover ou Transferir") },
            text = {
                Column {
                    Text("Mudar de categoria:", fontSize = 12.sp, color = Color.Gray)
                    Category.values().forEach { c ->
                        TextButton(onClick = { vm.move(ep, c); moving = null }, enabled = c != ep.category) {
                            Text("${catIcon(c)}  ${c.label}", fontSize = 15.sp)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Transferir entre Memória e Cartão SD:", fontSize = 12.sp, color = Color.Gray)
                    val otherVols = volumes.filterNot { ep.file.absolutePath.startsWith(it.dir.absolutePath) }
                    if (otherVols.isEmpty()) {
                        Text(
                            "Salvo em: ${ep.volume} (insira um cartão SD para transferir)",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        otherVols.forEach { targetVol ->
                            TextButton(
                                onClick = { vm.transferEpisodeToVolume(ep, targetVol); moving = null },
                            ) {
                                val icon = if (targetVol.removable) "💾" else "📲"
                                Text("$icon  Transferir para ${targetVol.label}", fontSize = 15.sp, color = Red)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancelar") } },
        )
    }

    confirmDelete?.let { ep ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Apagar áudio?") }, text = { Text(ep.title) },
            confirmButton = { TextButton(onClick = { vm.delete(ep); confirmDelete = null }) { Text("Apagar", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }

    editingEpisode?.let { ep ->
        AudioEditDialog(
            ep = ep,
            vm = vm,
            p = p,
            isEditing = isEditing,
            editStatus = editStatus,
            onDismiss = { vm.setEditingEpisode(null) },
        )
    }
}

@Composable
fun PlayerBar(p: PlayerUi, vm: MainVm, isLight: Boolean) {
    val barBg = if (isLight) Color(0xFFFFFFFF) else Color(0xFF202028)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(barBg)
            .navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(p.episode?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { p.episode?.let { vm.setEditingEpisode(it) } }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCut, "Cortar áudio", tint = Red, modifier = Modifier.size(18.dp))
            }
            TextButton(onClick = { vm.cycleSleepTimer() }) {
                Text(if (p.sleepMinutesLeft > 0) "⏲️ ${p.sleepMinutesLeft}m" else "⏲️ Off", fontSize = 12.sp, color = if (p.sleepMinutesLeft > 0) Red else Color.Gray)
            }
            TextButton(onClick = { vm.cycleSpeed() }) {
                Text("${p.speed}x", color = Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Slider(
            value = if (p.dur > 0) p.pos.toFloat() / p.dur else 0f,
            onValueChange = { vm.seekTo((it * p.dur).toLong()) },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${fmtTime(p.pos)} / ${fmtTime(p.dur)}", fontSize = 11.sp, color = Color.Gray)
            IconButton(onClick = { vm.prevTrack() }) { Icon(Icons.Default.SkipPrevious, "Anterior") }
            IconButton(onClick = { vm.skip(-15_000) }) { Icon(Icons.Default.Replay10, "-15s") }
            FilledIconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(48.dp)) {
                Icon(if (p.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { vm.skip(30_000) }) { Icon(Icons.Default.Forward30, "+30s") }
            IconButton(onClick = { vm.nextTrack() }) { Icon(Icons.Default.SkipNext, "Próximo") }
            IconButton(onClick = { vm.toggleShuffle() }) {
                Icon(Icons.Default.Shuffle, "Aleatório", tint = if (p.shuffle) Red else Color.Gray)
            }
        }
    }
}

@Composable
fun AudioEditDialog(
    ep: Episode,
    vm: MainVm,
    p: PlayerUi,
    isEditing: Boolean,
    editStatus: String?,
    onDismiss: () -> Unit,
) {
    var startText by remember { mutableStateOf("00:00") }
    var endText by remember { mutableStateOf("") }
    var showAdCut by remember { mutableStateOf(false) }
    var adStartText by remember { mutableStateOf("") }
    var adEndText by remember { mutableStateOf("") }
    var replaceOriginal by remember { mutableStateOf(true) }

    val isCurrentTrack = p.episode?.id == ep.id && p.pos > 1000L

    AlertDialog(
        onDismissRequest = { if (!isEditing) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✂️ Editar / Cortar Áudio", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(ep.title, fontSize = 12.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)

                if (isCurrentTrack) {
                    val posSec = p.pos / 1000f
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("⏱ Posição atual do player:", fontSize = 11.sp, color = Color.Gray)
                                Text(fmtTime(p.pos), fontWeight = FontWeight.Bold, color = Red, fontSize = 14.sp)
                            }
                            OutlinedButton(
                                onClick = { startText = AudioEditor.formatSecondsToTime(posSec) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Usar como início", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Text("1. Cortar início (pular introdução / emails):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    singleLine = true,
                    label = { Text("Início (ex: 24:30)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                // Chips de tempo rápido para pular introduções longas
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(1, 5, 10, 15, 20, 25).forEach { mins ->
                        AssistChip(
                            onClick = {
                                val curSec = AudioEditor.parseTimeToSeconds(startText) ?: 0f
                                startText = AudioEditor.formatSecondsToTime(curSec + mins * 60f)
                            },
                            label = { Text("+$mins min", fontSize = 11.sp) },
                        )
                    }
                    AssistChip(
                        onClick = { startText = "00:00" },
                        label = { Text("Zerar", fontSize = 11.sp) },
                    )
                }

                Spacer(Modifier.height(2.dp))
                Text("2. Cortar fim (opcional):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    singleLine = true,
                    placeholder = { Text("Deixar vazio para ir até o fim") },
                    label = { Text("Fim (ex: 1:30:00)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { showAdCut = !showAdCut },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showAdCut, onCheckedChange = { showAdCut = it })
                    Column {
                        Text("Remover propaganda no meio do episódio", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Recorta o intervalo de anúncio e junta as partes", fontSize = 11.sp, color = Color.Gray)
                    }
                }

                if (showAdCut) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = adStartText,
                            onValueChange = { adStartText = it },
                            label = { Text("Início anúncio") },
                            placeholder = { Text("12:00") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        OutlinedTextField(
                            value = adEndText,
                            onValueChange = { adEndText = it },
                            label = { Text("Fim anúncio") },
                            placeholder = { Text("14:30") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { replaceOriginal = !replaceOriginal },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = replaceOriginal, onCheckedChange = { replaceOriginal = it })
                    Column {
                        Text("Substituir arquivo original", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(if (replaceOriginal) "Economiza espaço na memória" else "Salva uma nova versão \"(Editado)\"", fontSize = 11.sp, color = Color.Gray)
                    }
                }

                if (isEditing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (editStatus != null) {
                    Text(
                        editStatus,
                        color = if (editStatus.startsWith("✓")) Color(0xFF4CAF50) else Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isEditing,
                onClick = {
                    val sSec = AudioEditor.parseTimeToSeconds(startText) ?: 0f
                    val eSec = AudioEditor.parseTimeToSeconds(endText)
                    val adPair = if (showAdCut) {
                        val a1 = AudioEditor.parseTimeToSeconds(adStartText)
                        val a2 = AudioEditor.parseTimeToSeconds(adEndText)
                        if (a1 != null && a2 != null && a1 < a2) a1 to a2 else null
                    } else null

                    val opts = AudioEditor.EditOptions(
                        trimStartSec = sSec,
                        trimEndSec = eSec,
                        adInterval = adPair,
                        replaceOriginal = replaceOriginal,
                    )
                    vm.editEpisodeAudio(ep, opts)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
            ) {
                if (isEditing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Processando…")
                } else {
                    Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cortar Áudio")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !isEditing, onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}
