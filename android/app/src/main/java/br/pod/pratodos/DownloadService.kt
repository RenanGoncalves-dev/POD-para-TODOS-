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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

private data class SpotifyTrack(val title: String, val artist: String, val thumbUrl: String?)
private data class SpotifyCollection(val kind: String, val name: String?, val coverUrl: String?, val tracks: List<SpotifyTrack>)

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

    private fun isSpotifyUrl(url: String): Boolean {
        val low = url.lowercase()
        return "open.spotify.com/" in low || "spotify.link/" in low || "spoti.fi/" in low || low.startsWith("spotify:")
    }

    private fun httpGetText(url: String): Pair<String, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        }
        conn.connect()
        val finalUrl = conn.url.toString()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return finalUrl to body
    }

    private fun parseSpotifyEmbed(rawUrl: String): SpotifyCollection? {
        var resolved = rawUrl.trim()
        if ("spotify.link/" in resolved.lowercase() || "spoti.fi/" in resolved.lowercase()) {
            runCatching { resolved = httpGetText(resolved).first }
        }
        val m = Regex("""(?:open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)(playlist|album|track|artist|episode|show)[/:]([A-Za-z0-9]+)""")
            .find(resolved) ?: return null
        val kind = m.groupValues[1]
        val spId = m.groupValues[2]
        val embedUrl = "https://open.spotify.com/embed/$kind/$spId"

        var colName: String? = null
        var coverUrl: String? = null
        val tracks = mutableListOf<SpotifyTrack>()

        runCatching {
            val html = httpGetText(embedUrl).second
            val nextJson = Regex("""<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
            if (nextJson != null) {
                val entity = JSONObject(nextJson)
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity")
                if (entity != null) {
                    colName = (entity.optString("name").ifBlank { entity.optString("title") })
                        .replace('\u00a0', ' ').trim().takeIf { it.isNotBlank() }
                    val sources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
                        ?: entity.optJSONObject("visualIdentity")?.optJSONArray("image")
                    if (sources != null && sources.length() > 0) {
                        coverUrl = sources.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                    }
                    val trackList = entity.optJSONArray("trackList")
                    if (trackList != null && trackList.length() > 0) {
                        for (i in 0 until trackList.length()) {
                            val t = trackList.optJSONObject(i) ?: continue
                            val tTitle = t.optString("title").replace('\u00a0', ' ').trim()
                            val tArtist = t.optString("subtitle").replace('\u00a0', ' ').trim()
                            if (tTitle.isNotBlank()) tracks.add(SpotifyTrack(tTitle, tArtist, coverUrl))
                        }
                    } else if (colName != null) {
                        var tArtist = entity.optString("subtitle").replace('\u00a0', ' ').trim()
                        if (tArtist.isBlank()) {
                            val arr = entity.optJSONArray("artists")
                            if (arr != null) {
                                tArtist = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                                    .filter { it.isNotBlank() }.joinToString(", ")
                            }
                        }
                        tracks.add(SpotifyTrack(colName!!, tArtist, coverUrl))
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            runCatching {
                val oembed = "https://open.spotify.com/oembed?url=https://open.spotify.com/$kind/$spId"
                val obj = JSONObject(httpGetText(oembed).second)
                val oTitle = obj.optString("title").trim()
                val oThumb = obj.optString("thumbnail_url").takeIf { it.isNotBlank() }
                if (oTitle.isNotBlank()) {
                    colName = colName ?: oTitle
                    coverUrl = coverUrl ?: oThumb
                    tracks.add(SpotifyTrack(oTitle, "Spotify", coverUrl))
                }
            }
        }
        return SpotifyCollection(kind, colName, coverUrl, tracks)
    }

    private suspend fun downloadSpotify(job: DlJob, rawUrl: String, catDir: File) {
        DownloadManager.update(job.id) { it.copy(text = "Lendo faixas do Spotify…") }
        val sp = parseSpotifyEmbed(rawUrl) ?: error("Link do Spotify inválido")
        if (sp.tracks.isEmpty()) error("Não foi possível ler as músicas desta playlist do Spotify (verifique se é pública)")

        // Se o usuário já escolheu uma playlist (ex: onde tem músicas do YouTube), adiciona nela!
        // Se não escolheu nenhuma e for playlist/álbum do Spotify, cria/usa uma com o nome da playlist do Spotify.
        val targetPlId = job.playlistId ?: if (sp.kind in listOf("playlist", "album", "artist") && !sp.name.isNullOrBlank()) {
            Library.getOrCreatePlaylist(this, "🟢 ${sp.name}", job.category)
        } else null

        while (!DownloadManager.ready.value && DownloadManager.initError.value == null) delay(300)
        if (DownloadManager.initError.value != null) error("yt-dlp não iniciou: ${DownloadManager.initError.value}")

        sp.tracks.forEachIndexed { idx, t ->
            val displayTitle = if (t.artist.isNotBlank()) "${t.artist} - ${t.title}" else t.title
            val vid = "sp_" + Library.shortHash(displayTitle, 8) // 11 caracteres compatível com [id]
            val safeStem = "${Library.safeFileName(displayTitle, 90)} [$vid]"
            val baseProgress = idx.toFloat() / sp.tracks.size.coerceAtLeast(1)
            DownloadManager.update(job.id) {
                it.copy(progress = baseProgress, text = "Spotify (${idx + 1}/${sp.tracks.size}): $displayTitle")
            }
            val req = YoutubeDLRequest("ytsearch1:$displayTitle audio").apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--write-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("-o", "${catDir.absolutePath}/$safeStem.%(ext)s")
                addOption("--windows-filenames")
                addOption("--no-playlist")
                addOption("--no-overwrites")
                addOption("--no-mtime")
                addOption("--ignore-errors")
            }
            runCatching {
                YoutubeDL.getInstance().execute(req, "dl-${job.id}-$idx") { p, _, _ ->
                    val stepP = if (p > 0) (idx + p / 100f) / sp.tracks.size else baseProgress
                    DownloadManager.update(job.id) {
                        it.copy(progress = stepP, text = "Spotify (${idx + 1}/${sp.tracks.size}): $displayTitle")
                    }
                }
            }
            val thumbTarget = File(catDir, "$safeStem.jpg")
            val thumbUrl = t.thumbUrl ?: sp.coverUrl
            if (!thumbTarget.isFile && !thumbUrl.isNullOrBlank()) {
                runCatching {
                    URL(thumbUrl).openStream().use { inp -> thumbTarget.outputStream().use { out -> inp.copyTo(out) } }
                }
            }
            if (targetPlId != null) {
                Library.addItemsToPlaylist(this, targetPlId, listOf(vid))
            }
            DownloadManager.version.update { it + 1 }
        }
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
                if (isSpotifyUrl(unwrapped)) {
                    downloadSpotify(job, unwrapped, catDir)
                } else if (unwrapped.startsWith("http") && isDirectAudioUrl(unwrapped)) {
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
