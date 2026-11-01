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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage

private val Red = Color(0xFFFF4D4D)
private val Bg = Color(0xFF0F0F12)
private val Card = Color(0xFF1A1A20)

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Card, onPrimary = Color.White)
            ) { Screen(vm) }
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
fun Screen(vm: MainVm) {
    val ctx = LocalContext.current
    val episodes by vm.episodes.collectAsState()
    val jobs by DownloadManager.jobs.collectAsState()
    val ready by DownloadManager.ready.collectAsState()
    val initErr by DownloadManager.initError.collectAsState()
    val p by vm.ui.collectAsState()
    val selected by vm.selected.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val category by vm.category.collectAsState()
    val shown = episodes.filter { it.category == category }
    var moving by remember { mutableStateOf<Episode?>(null) }
    var url by remember { mutableStateOf("") }
    var showStorage by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Episode?>(null) }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("POD", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                actions = {
                    TextButton(onClick = { showStorage = true }) {
                        Icon(if (selected.removable) Icons.Default.SdCard else Icons.Default.PhoneAndroid, null, tint = Red)
                        Spacer(Modifier.width(6.dp))
                        Text(if (selected.removable) "Cartão" else "Celular", color = Color.White)
                    }
                },
            )
        },
        bottomBar = { if (p.episode != null) PlayerBar(p, vm) },
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
            item {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(14.dp)) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Link do vídeo, playlist ou canal") },
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
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Baixar em ${category.label}", fontSize = 16.sp)
                    }
                    Text(
                        when {
                            initErr != null -> "⚠ Falha ao iniciar: $initErr"
                            !ready -> "Preparando o baixador (1ª vez demora um pouco)…"
                            else -> "Salvando em: ${selected.label}"
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
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp)) {
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
                    }
                }
            }

            item { Text("${category.label} (${shown.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            if (shown.isEmpty()) item {
                Text("Nada em ${category.label} ainda. Cole um link acima ou compartilhe um vídeo do YouTube com este app.", color = Color.Gray)
            }
            items(shown, key = { it.file.absolutePath }) { ep ->
                val active = p.episode?.id == ep.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (active) Color(0xFF2A1A1E) else Card)
                        .clickable { vm.play(ep) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = ep.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A32)),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${fmtSize(ep.size)} · ${ep.volume}", color = Color.Gray, fontSize = 12.sp)
                    }
                    Icon(
                        if (active && p.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null,
                        tint = Red, modifier = Modifier.size(32.dp),
                    )
                    IconButton(onClick = { moving = ep }) { Icon(Icons.Default.DriveFileMove, "Mover", tint = Color.Gray) }
                    IconButton(onClick = { confirmDelete = ep }) { Icon(Icons.Default.DeleteOutline, "Apagar", tint = Color.Gray) }
                }
            }
        }
    }

    if (showStorage) AlertDialog(
        onDismissRequest = { showStorage = false },
        confirmButton = { TextButton(onClick = { showStorage = false }) { Text("OK") } },
        title = { Text("Onde salvar") },
        text = {
            Column {
                volumes.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.select(v) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v.dir == selected.dir, onClick = { vm.select(v) })
                        Column {
                            Text(v.label)
                            Text("${fmtSize(freeSpace(v))} livres", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
                if (volumes.size == 1) Text("Cartão de memória não detectado. Insira o cartão e volte para esta tela.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text("Episódios que já estiverem nas pastas do celular ou do cartão aparecem sozinhos na biblioteca.", fontSize = 12.sp, color = Color.Gray)
            }
        },
    )

    moving?.let { ep ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Mover para") },
            text = {
                Column {
                    Category.values().forEach { c ->
                        TextButton(onClick = { vm.move(ep, c); moving = null }, enabled = c != ep.category) {
                            Text("${catIcon(c)}  ${c.label}", fontSize = 16.sp)
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
            title = { Text("Apagar episódio?") }, text = { Text(ep.title) },
            confirmButton = { TextButton(onClick = { vm.delete(ep); confirmDelete = null }) { Text("Apagar", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun PlayerBar(p: PlayerUi, vm: MainVm) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Color(0xFF202028))
            .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(p.episode?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Slider(
            value = if (p.dur > 0) p.pos.toFloat() / p.dur else 0f,
            onValueChange = { vm.seekTo((it * p.dur).toLong()) },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${fmtTime(p.pos)} / ${fmtTime(p.dur)}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.skip(-15_000) }) { Icon(Icons.Default.Replay10, "-15s") }
            FilledIconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(52.dp)) {
                Icon(if (p.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { vm.skip(30_000) }) { Icon(Icons.Default.Forward30, "+30s") }
            TextButton(onClick = { vm.cycleSpeed() }) { Text("${p.speed}x", color = Red, fontWeight = FontWeight.Bold) }
        }
    }
}
