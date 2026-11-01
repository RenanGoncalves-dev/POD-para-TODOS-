package br.pod.pratodos

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializa o yt-dlp embutido (python + ffmpeg) em segundo plano
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                DownloadManager.ready.value = true
                // mantém o yt-dlp em dia: o YouTube muda toda semana
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(this@App, YoutubeDL.UpdateChannel.STABLE) }
            }.onFailure { DownloadManager.initError.value = it.message ?: "erro ao iniciar" }
        }
    }
}

