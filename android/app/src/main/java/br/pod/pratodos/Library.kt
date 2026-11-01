package br.pod.pratodos

import android.content.Context
import java.io.File

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

/** Armazenamento: celular e cartão SD. Tudo que já estiver nas pastas é reconhecido automaticamente. */
object Library {
    private val AUDIO = setOf("m4a", "mp3", "opus", "ogg", "webm", "aac", "wav", "flac")
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

    /** Lê as subpastas por categoria; arquivos soltos na raiz contam como Podcasts. */
    fun scan(ctx: Context): List<Episode> =
        volumes(ctx).flatMap { v ->
            (listOf(v.dir to Category.PODCASTS) + Category.values().map { catDir(v, it) to it }).flatMap { (dir, cat) ->
                dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in AUDIO && !it.name.startsWith(".") }
                    .map { f ->
                        val stem = f.nameWithoutExtension
                        val m = ID_RE.find(stem)
                        Episode(
                            id = m?.groupValues?.get(1) ?: stem,
                            title = if (m != null) stem.removeSuffix(m.value).trim() else stem,
                            file = f,
                            size = f.length(),
                            volume = v.label,
                            thumb = m?.let { "https://i.ytimg.com/vi/${it.groupValues[1]}/mqdefault.jpg" },
                            category = cat,
                        )
                    }
            }
        }.sortedByDescending { it.file.lastModified() }

    fun move(ctx: Context, ep: Episode, to: Category): Boolean {
        val v = volumes(ctx).firstOrNull { ep.file.absolutePath.startsWith(it.dir.absolutePath) } ?: return false
        val dst = File(catDir(v, to), ep.file.name)
        return ep.file == dst || ep.file.renameTo(dst)
    }

    fun category(ctx: Context) = runCatching { Category.valueOf(prefs(ctx).getString("cat", "")!!) }.getOrDefault(Category.PODCASTS)
    fun setCategory(ctx: Context, c: Category) = prefs(ctx).edit().putString("cat", c.name).apply()

    fun savedPos(ctx: Context, id: String) = prefs(ctx).getLong("pos:$id", 0L)
    fun savePos(ctx: Context, id: String, ms: Long) = prefs(ctx).edit().putLong("pos:$id", ms).apply()
}

