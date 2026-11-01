package br.pod.pratodos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

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
)

data class Playlist(
    val id: String,
    val name: String,
    val category: Category,
    val items: List<String>,
)

/** Armazenamento: celular e cartão SD. Tudo que já estiver nas pastas é reconhecido automaticamente. */
object Library {
    private val AUDIO = setOf("m4a", "mp3", "opus", "ogg", "webm", "aac", "wav", "flac")
    private val THUMBS = listOf("jpg", "jpeg", "webp", "png")
    private val ID_RE = Regex("""\[([\w-]{11})]$""")

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

    private fun resolveThumb(audioFile: File, ytId: String?): String? {
        companionThumbs(audioFile).firstOrNull()?.let { return it.toURI().toString() }
        if (ytId != null) {
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

    /** Lê as subpastas por categoria; arquivos soltos na raiz contam como Podcasts. */
    fun scan(ctx: Context): List<Episode> =
        volumes(ctx).flatMap { v ->
            (listOf(v.dir to Category.PODCASTS) + Category.values().map { catDir(v, it) to it }).flatMap { (dir, cat) ->
                dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in AUDIO && !it.name.startsWith(".") }
                    .map { f ->
                        val stem = f.nameWithoutExtension
                        val m = ID_RE.find(stem)
                        val ytId = m?.groupValues?.get(1)
                        Episode(
                            id = ytId ?: stem,
                            title = if (m != null) stem.removeSuffix(m.value).trim() else stem,
                            file = f,
                            size = f.length(),
                            volume = v.label,
                            thumb = resolveThumb(f, ytId),
                            category = cat,
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

    fun deleteFileAndThumbs(ep: Episode) {
        companionThumbs(ep.file).forEach { it.delete() }
        ep.file.delete()
    }

    // ---- Playlists (salvas em playlists.json no volume selecionado + prefs) ----
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
        runCatching { plFile(ctx).writeText(str) }
    }

    fun category(ctx: Context) = runCatching { Category.valueOf(prefs(ctx).getString("cat", "")!!) }.getOrDefault(Category.PODCASTS)
    fun setCategory(ctx: Context, c: Category) = prefs(ctx).edit().putString("cat", c.name).apply()

    fun savedPos(ctx: Context, id: String) = prefs(ctx).getLong("pos:$id", 0L)
    fun savePos(ctx: Context, id: String, ms: Long) = prefs(ctx).edit().putLong("pos:$id", ms).apply()
}
