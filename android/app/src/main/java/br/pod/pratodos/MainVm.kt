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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUi(
    val episode: Episode? = null,
    val playing: Boolean = false,
    val pos: Long = 0,
    val dur: Long = 0,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val sleepMinutesLeft: Int = 0,
)

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val episodes = MutableStateFlow<List<Episode>>(emptyList())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val selectedPlaylistId = MutableStateFlow<String?>(null)
    val volumes = MutableStateFlow(Library.volumes(app))
    val selected = MutableStateFlow(Library.selected(app))
    val category = MutableStateFlow(Library.category(app))
    val lightTheme = MutableStateFlow(Library.lightTheme(app))
    val player = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = player

    private var controller: MediaController? = null
    private var sleepUntilMs: Long = 0L

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
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val mediaId = mediaItem?.mediaId ?: return
                    episodes.value.firstOrNull { it.id == mediaId }?.let { ep ->
                        player.value = player.value.copy(episode = ep)
                    }
                    sync()
                }
            })
            while (true) { // atualiza barra de progresso, sleep timer e guarda posição
                sync()
                val ep = player.value.episode
                if (ep != null && player.value.playing) Library.savePos(ctx, ep.id, player.value.pos)
                if (sleepUntilMs > 0L && System.currentTimeMillis() >= sleepUntilMs) {
                    sleepUntilMs = 0L
                    controller?.pause()
                    sync()
                }
                delay(500)
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        val remMins = if (sleepUntilMs > 0L) ((sleepUntilMs - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(0) else 0
        player.value = player.value.copy(
            playing = c.isPlaying,
            pos = c.currentPosition,
            dur = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
            shuffle = c.shuffleModeEnabled,
            sleepMinutesLeft = remMins,
        )
    }

    fun toggleTheme() {
        val next = !lightTheme.value
        Library.setLightTheme(ctx, next)
        lightTheme.value = next
    }

    fun refresh() {
        viewModelScope.launch {
            volumes.value = Library.volumes(ctx)
            selected.value = Library.selected(ctx)
            val (eps, pls) = withContext(Dispatchers.IO) { Library.scan(ctx) to Library.playlists(ctx) }
            episodes.value = eps
            playlists.value = pls
        }
    }

    fun select(v: Volume) { Library.select(ctx, v); refresh() }

    fun download(url: String) = DownloadManager.enqueue(ctx, url, category.value, selectedPlaylistId.value)

    fun setCategory(c: Category) {
        Library.setCategory(ctx, c)
        category.value = c
        selectedPlaylistId.value = null
    }

    fun selectPlaylist(id: String?) { selectedPlaylistId.value = id }

    fun createPlaylist(name: String, initialEpisodeId: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val pl = Playlist(
            id = System.currentTimeMillis().toString(),
            name = trimmed,
            category = category.value,
            items = if (initialEpisodeId != null) listOf(initialEpisodeId) else emptyList(),
        )
        val next = playlists.value + pl
        Library.savePlaylists(ctx, next)
        playlists.value = next
        selectedPlaylistId.value = pl.id
    }

    fun deletePlaylist(pl: Playlist) {
        val next = playlists.value.filterNot { it.id == pl.id }
        Library.savePlaylists(ctx, next)
        playlists.value = next
        if (selectedPlaylistId.value == pl.id) selectedPlaylistId.value = null
    }

    fun toggleInPlaylist(pl: Playlist, ep: Episode) {
        val next = playlists.value.map {
            if (it.id != pl.id) it
            else if (ep.id in it.items) it.copy(items = it.items - ep.id)
            else it.copy(items = it.items + ep.id)
        }
        Library.savePlaylists(ctx, next)
        playlists.value = next
    }

    fun move(ep: Episode, to: Category) {
        if (player.value.episode?.id == ep.id) { controller?.stop(); controller?.clearMediaItems(); player.value = PlayerUi() }
        Library.move(ctx, ep, to); refresh()
    }

    private fun toMediaItem(ep: Episode) = MediaItem.Builder()
        .setMediaId(ep.id)
        .setUri(Uri.fromFile(ep.file))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(ep.title).setArtworkUri(ep.thumb?.let(Uri::parse)).build()
        ).build()

    fun play(ep: Episode, queue: List<Episode> = listOf(ep)) {
        val c = controller ?: return
        if (player.value.episode?.id == ep.id && queue.size <= 1) { togglePlay(); return }
        val list = if (ep in queue) queue else listOf(ep)
        val idx = list.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
        c.setMediaItems(list.map(::toMediaItem), idx, Library.savedPos(ctx, ep.id))
        c.prepare()
        c.play()
        player.value = player.value.copy(episode = ep)
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun prevTrack() { controller?.seekToPreviousMediaItem(); sync() }
    fun nextTrack() { controller?.seekToNextMediaItem(); sync() }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }; sync() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); sync() }
    fun skip(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) }; sync() }
    fun cycleSpeed() {
        val steps = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val cur = controller?.playbackParameters?.speed ?: 1f
        val next = steps[(steps.indexOfFirst { it == cur } + 1).mod(steps.size)]
        controller?.setPlaybackSpeed(next); sync()
    }
    fun cycleSleepTimer() {
        val steps = listOf(0, 15, 30, 45, 60)
        val cur = player.value.sleepMinutesLeft
        val next = when {
            cur <= 0 -> 15
            cur <= 15 -> 30
            cur <= 30 -> 45
            cur <= 45 -> 60
            else -> 0
        }
        sleepUntilMs = if (next > 0) System.currentTimeMillis() + next * 60_000L else 0L
        sync()
    }

    fun delete(ep: Episode) {
        if (player.value.episode?.id == ep.id) { controller?.stop(); controller?.clearMediaItems(); player.value = PlayerUi() }
        Library.deleteFileAndThumbs(ep)
        val nextPls = playlists.value.map { it.copy(items = it.items - ep.id) }
        Library.savePlaylists(ctx, nextPls)
        refresh()
    }

    override fun onCleared() { controller?.release() }
}
