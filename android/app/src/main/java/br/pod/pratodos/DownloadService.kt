package br.pod.pratodos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

enum class Status { QUEUED, RUNNING, DONE, ERROR }

data class DlJob(
    val id: Long,
    val url: String,
    val category: Category = Category.PODCASTS,
    val playlistId: String? = null,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val text: String = "Na fila",
)

object DownloadManager {
    val jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val ready = MutableStateFlow(false)
    val initError = MutableStateFlow<String?>(null)
    val version = MutableStateFlow(0) // incrementa a cada download concluído (atualiza a lista)
    private var seq = 0L

    fun enqueue(ctx: Context, url: String, category: Category, playlistId: String? = null) {
        val j = DlJob(++seq, url.trim(), category, playlistId)
        jobs.update { it + j }
        val i = Intent(ctx, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun update(id: Long, f: (DlJob) -> DlJob) = jobs.update { l -> l.map { if (it.id == id) f(it) else it } }
    fun clearFinished() = jobs.update { l -> l.filter { it.status == Status.QUEUED || it.status == Status.RUNNING } }
}

/** Baixa ÁUDIO + CAPA (YouTube, links diretos de podcasts como Jovem Nerd ?file=...mp3, etc.) em primeiro plano. */
class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var worker: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg("Preparando downloads…")
        if (worker?.isActive != true) worker = scope.launch { run() }
        return START_NOT_STICKY
    }

    private fun startFg(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "dl").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("POD").setContentText(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
    }

    /** Desembrulha links redirecionadores como https://jovemnerd.com.br/api/download?file=https://...mp3 */
    private fun unwrapUrl(raw: String): String {
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return raw
        return runCatching {
            val u = Uri.parse(raw)
            for (k in listOf("file", "url", "audio", "mp3", "src", "download", "media")) {
                val v = u.getQueryParameter(k)
                if (v != null && (v.startsWith("http://") || v.startsWith("https://"))) return v
            }
            raw
        }.getOrDefault(raw)
    }

    private fun isDirectAudioUrl(url: String): Boolean {
        val path = runCatching { URLDecoder.decode(Uri.parse(url).path ?: "", "UTF-8") }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in Library.AUDIO
    }

    private fun downloadDirectHttp(job: DlJob, audioUrl: String, dir: File) {
        val eid = "ext_" + Library.shortHash(audioUrl)
        val rawPath = runCatching { URLDecoder.decode(Uri.parse(audioUrl).path ?: "", "UTF-8") }.getOrDefault("")
        val fname = rawPath.substringAfterLast('/').ifBlank { "podcast.mp3" }
        val ext = fname.substringAfterLast('.', "mp3").lowercase().let { if (it in Library.AUDIO) it else "mp3" }
        val guessTitle = fname.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Podcast" }

        val tmpFile = File(dir, ".dl_$eid.$ext")
        val conn = (URL(audioUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0 Mobile Safari/537.36")
        }
        conn.connect()
        if (conn.responseCode >= 400) error("HTTP ${conn.responseCode} ao baixar áudio")
        val total = conn.contentLengthLong
        var downloaded = 0L
        conn.inputStream.use { inp ->
            tmpFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    downloaded += n
                    val p = if (total > 0) downloaded.toFloat() / total else 0f
                    val mbDone = downloaded / 1_048_576
                    val mbTotal = if (total > 0) "/ ${total / 1_048_576} MB" else "MB"
                    DownloadManager.update(job.id) { it.copy(progress = p, text = "Baixando $guessTitle ($mbDone $mbTotal)") }
                }
            }
        }
        val id3Title = Library.extractEmbeddedMetadata(tmpFile) ?: guessTitle
        val finalFile = File(dir, "${Library.safeFileName(id3Title)} [$eid].$ext")
        File(dir, ".dl_$eid.jpg").takeIf { it.isFile }?.renameTo(File(dir, "${finalFile.nameWithoutExtension}.jpg"))
        tmpFile.renameTo(finalFile)
        Library.extractEmbeddedMetadata(finalFile)
    }

    private suspend fun run() {
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pod:dl").apply { acquire(4 * 60 * 60 * 1000L) }
        while (true) {
            val job = DownloadManager.jobs.value.firstOrNull { it.status == Status.QUEUED } ?: break
            startFg("Baixando áudio…")
            DownloadManager.update(job.id) { it.copy(status = Status.RUNNING, text = "Iniciando…") }
            val vol = Library.selected(this)
            val catDir = Library.catDir(vol, job.category)
            val beforeIds = Library.scan(this).map { it.id }.toSet()

            val unwrapped = unwrapUrl(job.url)
            runCatching {
                if (unwrapped.startsWith("http") && isDirectAudioUrl(unwrapped)) {
                    // Link direto de podcast (.mp3, .m4a, ex: Jovem Nerd) — baixa direto sem depender do yt-dlp!
                    downloadDirectHttp(job, unwrapped, catDir)
                } else {
                    while (!DownloadManager.ready.value && DownloadManager.initError.value == null) delay(300)
                    if (DownloadManager.initError.value != null) error("yt-dlp não iniciou: ${DownloadManager.initError.value}")
                    val targetUrl = if (unwrapped.startsWith("http://") || unwrapped.startsWith("https://")) unwrapped else "ytsearch1:$unwrapped"
                    val req = YoutubeDLRequest(targetUrl).apply {
                        addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                        addOption("--write-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                        addOption("-o", "${catDir.absolutePath}/%(title).100s [%(id)s].%(ext)s")
                        addOption("--windows-filenames")
                        addOption("--yes-playlist")
                        addOption("--no-overwrites")
                        addOption("--no-mtime")
                        addOption("--ignore-errors")
                    }
                    YoutubeDL.getInstance().execute(req, "dl-${job.id}") { p, _, line ->
                        val txt = line.lineSequence().lastOrNull { it.isNotBlank() }?.take(80) ?: ""
                        DownloadManager.update(job.id) { it.copy(progress = if (p > 0) p / 100f else it.progress, text = txt.ifBlank { it.text }) }
                    }
                }
            }.onSuccess {
                if (job.playlistId != null) {
                    val newIds = Library.scan(this).map { it.id }.filter { it !in beforeIds }
                    if (newIds.isNotEmpty()) {
                        val pls = Library.playlists(this).map { pl ->
                            if (pl.id == job.playlistId) pl.copy(items = (pl.items + newIds).distinct()) else pl
                        }
                        Library.savePlaylists(this, pls)
                    }
                }
                DownloadManager.update(job.id) { it.copy(status = Status.DONE, progress = 1f, text = "Concluído (áudio + capa salvos)") }
            }.onFailure { e ->
                DownloadManager.update(job.id) { it.copy(status = Status.ERROR, text = (e.message ?: "erro").lineSequence().lastOrNull { l -> l.isNotBlank() }?.take(160) ?: "erro") }
            }
            DownloadManager.version.update { it + 1 }
        }
        wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
