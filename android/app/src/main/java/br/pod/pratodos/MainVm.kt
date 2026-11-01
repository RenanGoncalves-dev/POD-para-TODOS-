package br.pod.pratodos

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUi(
    val episode: Episode? = null,
    val playing: Boolean = false,
    val pos: Long = 0,
    val dur: Long = 0,
    val speed: Float = 1f,
)

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val episodes = MutableStateFlow<List<Episode>>(emptyList())
    val volumes = MutableStateFlow(Library.volumes(app))
    val selected = MutableStateFlow(Library.selected(app))
    val category = MutableStateFlow(Library.category(app))
    val player = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = player

    private var controller: MediaController? = null

    init {
        refresh()
        viewModelScope.launch {
            DownloadManager.version.collect { refresh() }
        }
        viewModelScope.launch {
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            controller = MediaController.Builder(ctx, token).buildAsync().await()
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
            })
            while (true) { // atualiza barra de progresso e guarda posição
                sync()
                val ep = player.value.episode
                if (ep != null && player.value.playing) Library.savePos(ctx, ep.id, player.value.pos)
                delay(500)
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        player.value = player.value.copy(
            playing = c.isPlaying,
            pos = c.currentPosition,
            dur = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
        )
    }

    fun refresh() {
        viewModelScope.launch {
            volumes.value = Library.volumes(ctx)
            selected.value = Library.selected(ctx)
            episodes.value = withContext(Dispatchers.IO) { Library.scan(ctx) }
        }
    }

    fun select(v: Volume) { Library.select(ctx, v); refresh() }

    fun download(url: String) = DownloadManager.enqueue(ctx, url, category.value)
    fun setCategory(c: Category) { Library.setCategory(ctx, c); category.value = c }
    fun move(ep: Episode, to: Category) {
        if (player.value.episode?.id == ep.id) { controller?.stop(); controller?.clearMediaItems(); player.value = PlayerUi() }
        Library.move(ctx, ep, to); refresh()
    }

    fun play(ep: Episode) {
        val c = controller ?: return
        if (player.value.episode?.id == ep.id) { togglePlay(); return }
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(ep.file))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(ep.title).setArtworkUri(ep.thumb?.let(Uri::parse)).build()
            ).build()
        c.setMediaItem(item, Library.savedPos(ctx, ep.id))
        c.prepare()
        c.play()
        player.value = player.value.copy(episode = ep)
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long) { controller?.seekTo(ms); sync() }
    fun skip(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) }; sync() }
    fun cycleSpeed() {
        val steps = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val cur = controller?.playbackParameters?.speed ?: 1f
        val next = steps[(steps.indexOfFirst { it == cur } + 1).mod(steps.size)]
        controller?.setPlaybackSpeed(next); sync()
    }

    fun delete(ep: Episode) {
        if (player.value.episode?.id == ep.id) { controller?.stop(); controller?.clearMediaItems(); player.value = PlayerUi() }
        ep.file.delete()
        refresh()
    }

    override fun onCleared() { controller?.release() }
}

