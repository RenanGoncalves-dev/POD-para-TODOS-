package br.pod.pratodos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

/** Baixa somente o ÁUDIO + a THUMBNAIL offline em primeiro plano (continua com a tela apagada). */
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

    private suspend fun run() {
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pod:dl").apply { acquire(4 * 60 * 60 * 1000L) }
        while (!DownloadManager.ready.value && DownloadManager.initError.value == null) delay(300)
        while (true) {
            val job = DownloadManager.jobs.value.firstOrNull { it.status == Status.QUEUED } ?: break
            startFg("Baixando áudio…")
            DownloadManager.update(job.id) { it.copy(status = Status.RUNNING, text = "Iniciando…") }
            val vol = Library.selected(this)
            val beforeIds = Library.scan(this).map { it.id }.toSet()
            runCatching {
                if (DownloadManager.initError.value != null) error("yt-dlp não iniciou: ${DownloadManager.initError.value}")
                val req = YoutubeDLRequest(job.url).apply {
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    addOption("--write-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                    addOption("-o", "${Library.catDir(vol, job.category).absolutePath}/%(title).100s [%(id)s].%(ext)s")
                    addOption("--windows-filenames") // nomes aceitos por cartão FAT/exFAT
                    addOption("--yes-playlist")
                    addOption("--no-overwrites")
                    addOption("--no-mtime")
                    addOption("--ignore-errors")
                }
                YoutubeDL.getInstance().execute(req, "dl-${job.id}") { p, _, line ->
                    val txt = line.lineSequence().lastOrNull { it.isNotBlank() }?.take(80) ?: ""
                    DownloadManager.update(job.id) { it.copy(progress = if (p > 0) p / 100f else it.progress, text = txt.ifBlank { it.text }) }
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
