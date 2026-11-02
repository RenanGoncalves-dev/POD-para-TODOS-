package br.pod.pratodos

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

data class Volume(val label: String, val dir: File, val removable: Boolean)

enum class Category(val label: String, val folder: String) {
    PODCASTS("Podcasts", "Podcasts"),
    MUSICAS("Músicas", "Musicas"),
    DJSETS("DJ Sets", "DJSets"),
}

data class Episode(
    val id: String,
    val title: String,
    val file: File,
    val size: Long,
    val volume: String,
    val thumb: String?,
    val category: Category = Category.PODCASTS,
    val savedPosMs: Long = 0L,
)

data class Playlist(
    val id: String,
    val name: String,
    val category: Category,
    val items: List<String>,
)

/** Armazenamento: celular e cartão SD. Tudo que já estiver nas pastas é reconhecido automaticamente. */
object Library {
    val AUDIO = setOf("m4a", "mp3", "opus", "ogg", "webm", "aac", "wav", "flac")
    private val THUMBS = listOf("jpg", "jpeg", "webp", "png")
    private val ID_RE = Regex("""\[([\w-]{11})]$""")
    private val UNSAFE_RE = Regex("""[<>:"/\\|?*\x00-\x1f]+""")

    fun safeFileName(name: String, maxLen: Int = 100): String =
        UNSAFE_RE.replace(name, " ").replace(Regex("""\s+"""), " ").trim().trim('.').take(maxLen).ifBlank { "episodio" }

    fun shortHash(input: String, len: Int = 7): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(len)

    fun volumes(ctx: Context): List<Volume> =
        ctx.getExternalFilesDirs(null).filterNotNull().mapIndexed { i, f ->
            val dir = File(f, "POD").apply { mkdirs() }
            if (i == 0) Volume("Memória do celular", dir, false) else Volume("Cartão de memória", dir, true)
        }.ifEmpty { listOf(Volume("Memória do celular", File(ctx.filesDir, "POD").apply { mkdirs() }, false)) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pod", Context.MODE_PRIVATE)

    /** Volume escolhido; por padrão usa o cartão se existir. */
    fun selected(ctx: Context): Volume {
        val vols = volumes(ctx)
        val saved = prefs(ctx).getString("volume", null)
        return vols.firstOrNull { it.dir.absolutePath == saved } ?: vols.last()
    }

    fun select(ctx: Context, v: Volume) = prefs(ctx).edit().putString("volume", v.dir.absolutePath).apply()

    fun catDir(v: Volume, c: Category) = File(v.dir, c.folder).apply { mkdirs() }

    fun companionThumbs(audioFile: File): List<File> {
        val stem = audioFile.nameWithoutExtension
        val parent = audioFile.parentFile ?: return emptyList()
        return THUMBS.map { File(parent, "$stem.$it") }.filter { it.isFile }
    }

    /** Extrai título e capa embutida (ID3 APIC / MP4 covr) de arquivos .mp3/.m4a usando MediaMetadataRetriever. */
    fun extractEmbeddedMetadata(audioFile: File): String? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(audioFile.absolutePath)
            val pic = r.embeddedPicture
            if (pic != null && pic.isNotEmpty()) {
                val jpg = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.jpg")
                if (!jpg.isFile) jpg.writeBytes(pic)
            }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun resolveThumb(audioFile: File, ytId: String?): String? {
        companionThumbs(audioFile).firstOrNull()?.let { return it.toURI().toString() }
        // Tenta extrair capa ID3 embutida no próprio arquivo (.mp3 de podcasts como Jovem Nerd)
        extractEmbeddedMetadata(audioFile)
        companionThumbs(audioFile).firstOrNull()?.let { return it.toURI().toString() }

        val isRealYt = ytId != null && !ytId.startsWith("ext_") && !ytId.startsWith("loc_") && !ytId.startsWith("sp_")
        if (isRealYt) {
            val parent = audioFile.parentFile
            val target = File(parent, "${audioFile.nameWithoutExtension}.jpg")
            runCatching {
                URL("https://i.ytimg.com/vi/$ytId/mqdefault.jpg").openStream().use { inp ->
                    target.outputStream().use { out -> inp.copyTo(out) }
                }
                if (target.isFile && target.length() > 0) return target.toURI().toString()
            }
            return "https://i.ytimg.com/vi/$ytId/mqdefault.jpg"
        }
        return null
    }

    /** Importa um arquivo de áudio (.mp3, .m4a, etc.) escolhido no celular para a pasta da categoria. */
    fun importUri(ctx: Context, uri: Uri, category: Category, playlistId: String?): String? {
        val vol = selected(ctx)
        val dir = catDir(vol, category)
        val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "podcast.mp3"
        val ext = rawName.substringAfterLast('.', "mp3").lowercase().let { if (it in AUDIO) it else "mp3" }
        val eid = "loc_" + shortHash(rawName + System.currentTimeMillis())
        val tmp = File(dir, ".import_$eid.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { inp ->
            tmp.outputStream().use { out -> inp.copyTo(out) }
        } ?: return null

        val id3Title = extractEmbeddedMetadata(tmp)
        val baseTitle = id3Title ?: rawName.substringBeforeLast('.').replace('_', ' ')
        val finalFile = File(dir, "${safeFileName(baseTitle)} [$eid].$ext")
        File(dir, ".import_$eid.jpg").takeIf { it.isFile }?.renameTo(File(dir, "${finalFile.nameWithoutExtension}.jpg"))
        tmp.renameTo(finalFile)
        extractEmbeddedMetadata(finalFile)

        if (playlistId != null) {
            val pls = playlists(ctx).map { pl ->
                if (pl.id == playlistId) pl.copy(items = (pl.items + eid).distinct()) else pl
            }
            savePlaylists(ctx, pls)
        }
        return eid
    }

    /** Lê as subpastas por categoria; arquivos soltos na raiz contam como Podcasts. */
    fun scan(ctx: Context): List<Episode> =
        volumes(ctx).flatMap { v ->
            (listOf(v.dir to Category.PODCASTS) + Category.values().map { catDir(v, it) to it }).flatMap { (dir, cat) ->
                dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in AUDIO && !it.name.startsWith(".") }
                    .map { f ->
                        val stem = f.nameWithoutExtension
                        val m = ID_RE.find(stem)
                        val ytId = m?.groupValues?.get(1)
                        val id = ytId ?: stem
                        Episode(
                            id = id,
                            title = if (m != null) stem.removeSuffix(m.value).trim() else stem,
                            file = f,
                            size = f.length(),
                            volume = v.label,
                            thumb = resolveThumb(f, ytId),
                            category = cat,
                            savedPosMs = savedPos(ctx, id),
                        )
                    }
            }
        }.sortedByDescending { it.file.lastModified() }

    fun move(ctx: Context, ep: Episode, to: Category): Boolean {
        val v = volumes(ctx).firstOrNull { ep.file.absolutePath.startsWith(it.dir.absolutePath) } ?: return false
        val dstDir = catDir(v, to)
        val dst = File(dstDir, ep.file.name)
        companionThumbs(ep.file).forEach { t -> t.renameTo(File(dstDir, t.name)) }
        return ep.file == dst || ep.file.renameTo(dst)
    }

    /** Transfere um episódio (áudio + capa) entre Memória do celular e Cartão SD de forma segura (copia + verifica tamanho + remove origem). */
    fun transferToVolume(ctx: Context, ep: Episode, targetVol: Volume): Boolean {
        if (ep.file.absolutePath.startsWith(targetVol.dir.absolutePath)) return true
        if (!ep.file.isFile) return false
        val dstDir = catDir(targetVol, ep.category)
        val dstAudio = File(dstDir, ep.file.name)
        val tmpAudio = File(dstDir, ".trans_${ep.file.name}")
        return try {
            val oldThumbs = companionThumbs(ep.file)
            oldThumbs.forEach { t ->
                val dstThumb = File(dstDir, t.name)
                if (t.absolutePath != dstThumb.absolutePath) {
                    t.copyTo(dstThumb, overwrite = true)
                }
            }
            val origLen = ep.file.length()
            ep.file.copyTo(tmpAudio, overwrite = true)
            if (!tmpAudio.isFile || tmpAudio.length() != origLen) {
                tmpAudio.delete()
                return false
            }
            if (dstAudio.exists()) dstAudio.delete()
            if (!tmpAudio.renameTo(dstAudio)) {
                tmpAudio.copyTo(dstAudio, overwrite = true)
                tmpAudio.delete()
            }
            if (dstAudio.isFile && dstAudio.length() == origLen) {
                oldThumbs.forEach { it.delete() }
                ep.file.delete()
                savePlaylists(ctx, playlists(ctx)) // sincroniza playlists.json nos volumes
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            tmpAudio.delete()
            false
        }
    }

    /** Transfere em lote os áudios (todos ou apenas de uma categoria como Podcasts) de um volume para outro. */
    fun transferAllBetweenVolumes(
        ctx: Context,
        fromVol: Volume,
        toVol: Volume,
        onlyCategory: Category? = null,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): Int {
        if (fromVol.dir.absolutePath == toVol.dir.absolutePath) return 0
        val targets = scan(ctx).filter { ep ->
            ep.file.absolutePath.startsWith(fromVol.dir.absolutePath) &&
                (onlyCategory == null || ep.category == onlyCategory)
        }
        var moved = 0
        targets.forEachIndexed { idx, ep ->
            onProgress(idx + 1, targets.size, ep.title)
            if (transferToVolume(ctx, ep, toVol)) moved++
        }
        return moved
    }

    fun deleteFileAndThumbs(ep: Episode) {
        companionThumbs(ep.file).forEach { it.delete() }
        ep.file.delete()
    }

    // ---- Playlists (salvas em playlists.json em todos os volumes + prefs) ----
    private fun plFile(ctx: Context) = File(selected(ctx).dir, "playlists.json")

    fun playlists(ctx: Context): List<Playlist> {
        val raw = runCatching {
            val f = plFile(ctx)
            if (f.isFile) f.readText() else prefs(ctx).getString("playlists", "[]") ?: "[]"
        }.getOrDefault("[]")
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val itemsArr = o.optJSONArray("items") ?: JSONArray()
                Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    category = runCatching { Category.valueOf(o.optString("category", "PODCASTS")) }.getOrDefault(Category.PODCASTS),
                    items = (0 until itemsArr.length()).map { j -> itemsArr.getString(j) },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun savePlaylists(ctx: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("category", p.category.name)
                put("items", JSONArray(p.items))
            })
        }
        val str = arr.toString()
        prefs(ctx).edit().putString("playlists", str).apply()
        volumes(ctx).forEach { v -> runCatching { File(v.dir, "playlists.json").writeText(str) } }
    }

    fun getOrCreatePlaylist(ctx: Context, name: String, category: Category): String {
        val clean = name.trim().ifBlank { "Playlist Spotify" }
        val current = playlists(ctx)
        current.firstOrNull { it.category == category && it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
        val created = Playlist(
            id = System.currentTimeMillis().toString(),
            name = clean,
            category = category,
            items = emptyList(),
        )
        savePlaylists(ctx, current + created)
        return created.id
    }

    fun addItemsToPlaylist(ctx: Context, playlistId: String, episodeIds: List<String>) {
        if (episodeIds.isEmpty()) return
        val next = playlists(ctx).map { pl ->
            if (pl.id == playlistId) pl.copy(items = (pl.items + episodeIds).distinct()) else pl
        }
        savePlaylists(ctx, next)
    }

    fun mergePlaylists(ctx: Context, sourceId: String, targetId: String, deleteSource: Boolean = false) {
        if (sourceId == targetId) return
        val current = playlists(ctx)
        val src = current.firstOrNull { it.id == sourceId } ?: return
        val merged = current.map { pl ->
            if (pl.id == targetId) pl.copy(items = (pl.items + src.items).distinct()) else pl
        }.let { list ->
            if (deleteSource) list.filterNot { it.id == sourceId } else list
        }
        savePlaylists(ctx, merged)
    }

    fun category(ctx: Context) = runCatching { Category.valueOf(prefs(ctx).getString("cat", "")!!) }.getOrDefault(Category.PODCASTS)
    fun setCategory(ctx: Context, c: Category) = prefs(ctx).edit().putString("cat", c.name).apply()

    fun lightTheme(ctx: Context) = prefs(ctx).getBoolean("light_theme", false)
    fun setLightTheme(ctx: Context, light: Boolean) = prefs(ctx).edit().putBoolean("light_theme", light).apply()

    fun savedPos(ctx: Context, id: String) = prefs(ctx).getLong("pos:$id", 0L)
    fun savePos(ctx: Context, id: String, ms: Long) = prefs(ctx).edit().putLong("pos:$id", ms).apply()

    // ---- Anti-Bot & Cookies do YouTube ----
    fun cookieFile(ctx: Context): File? {
        val selectedDir = selected(ctx).dir
        val cand = File(selectedDir, "cookies.txt")
        if (cand.isFile && cand.length() > 0) return cand
        for (v in volumes(ctx)) {
            val f = File(v.dir, "cookies.txt")
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    fun hasCookies(ctx: Context): Boolean = cookieFile(ctx) != null

    fun saveCookies(ctx: Context, text: String): Boolean = runCatching {
        val f = File(selected(ctx).dir, "cookies.txt")
        f.writeText(text)
        true
    }.getOrDefault(false)

    fun deleteCookies(ctx: Context): Boolean = runCatching {
        var any = false
        for (v in volumes(ctx)) {
            val f = File(v.dir, "cookies.txt")
            if (f.isFile) {
                f.delete()
                any = true
            }
        }
        any
    }.getOrDefault(false)
}
