package br.pod.pratodos

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializa o yt-dlp embutido (python + ffmpeg) garantindo a versão 2026.10.26 imediatamente
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val bundledVer = "2026.10.26"
                val prefs = getSharedPreferences("pod_ytdlp", MODE_PRIVATE)
                val ytdlpDir = File(noBackupFilesDir, "youtubedl-android/yt-dlp")
                val ytdlpBin = File(ytdlpDir, "yt-dlp")
                if (prefs.getString("bundled_ver", null) != bundledVer || !ytdlpBin.exists() || ytdlpBin.length() < 1_000_000L) {
                    ytdlpDir.mkdirs()
                    resources.openRawResource(R.raw.ytdlp).use { inp ->
                        ytdlpBin.outputStream().use { out -> inp.copyTo(out) }
                    }
                    prefs.edit().putString("bundled_ver", bundledVer).apply()
                }
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                DownloadManager.ready.value = true
                // mantém o yt-dlp em dia: o YouTube muda toda semana (se falhar, segue com a versão embutida)
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(this@App, YoutubeDL.UpdateChannel.STABLE) }
            }.onFailure { DownloadManager.initError.value = it.message ?: "erro ao iniciar" }
        }
    }
}
