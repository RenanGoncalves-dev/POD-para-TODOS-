package br.pod.pratodos

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

object AudioEditor {
    private const val TAG = "AudioEditor"

    data class EditOptions(
        val trimStartSec: Float = 0f,
        val trimEndSec: Float? = null,
        val adInterval: Pair<Float, Float>? = null, // Início e fim do trecho a remover no meio
        val replaceOriginal: Boolean = true,
    )

    /**
     * Converte strings como "24:30", "01:15:00", "1470" ou "12.5" em segundos.
     */
    fun parseTimeToSeconds(input: String): Float? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.matches(Regex("""^\d+(\.\d+)?$"""))) return s.toFloatOrNull()
        val parts = s.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toFloatOrNull() ?: return null
                val sec = parts[1].toFloatOrNull() ?: return null
                m * 60f + sec
            }
            3 -> {
                val h = parts[0].toFloatOrNull() ?: return null
                val m = parts[1].toFloatOrNull() ?: return null
                val sec = parts[2].toFloatOrNull() ?: return null
                h * 3600f + m * 60f + sec
            }
            else -> null
        }
    }

    fun formatSecondsToTime(sec: Float): String {
        val totalSec = sec.toInt().coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun getFFmpegProcess(ctx: Context, args: List<String>): ProcessBuilder {
        val binDir = File(ctx.applicationInfo.nativeLibraryDir)
        val ffmpegLibDir = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val ffmpegBin = File(binDir, "libffmpeg.so")

        if (ffmpegBin.exists() && !ffmpegBin.canExecute()) {
            ffmpegBin.setExecutable(true)
        }

        val cmd = listOf(ffmpegBin.absolutePath) + args
        val pb = ProcessBuilder(cmd)
        val env = pb.environment()
        val ldLib = "${binDir.absolutePath}:${ffmpegLibDir.absolutePath}"
        env["LD_LIBRARY_PATH"] = ldLib
        env["PATH"] = "${System.getenv("PATH") ?: ""}:${binDir.absolutePath}"
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        return pb
    }

    private fun runFFmpeg(ctx: Context, args: List<String>): Pair<Int, String> {
        val pb = getFFmpegProcess(ctx, args)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                out.appendLine(line)
                line = reader.readLine()
            }
        }
        val code = p.waitFor()
        return code to out.toString()
    }

    /**
     * Executa a edição (corte de início, fim ou remoção de intervalo de propaganda).
     */
    fun editAudio(
        ctx: Context,
        episode: Episode,
        options: EditOptions,
        onProgress: (String) -> Unit = {},
    ): Result<File> {
        return runCatching {
            val srcFile = episode.file
            if (!srcFile.isFile || srcFile.length() == 0L) {
                error("Arquivo de áudio não encontrado: ${srcFile.name}")
            }

            val parentDir = srcFile.parentFile ?: error("Pasta do arquivo inacessível")
            val ext = srcFile.extension.lowercase().ifBlank { "m4a" }
            val tmpOut = File(parentDir, ".editing_${System.currentTimeMillis()}.$ext")

            onProgress("Iniciando corte de áudio...")

            val startSec = options.trimStartSec.coerceAtLeast(0f)
            val endSec = options.trimEndSec?.takeIf { it > startSec }
            val ad = options.adInterval

            val success = if (ad != null && ad.first < ad.second) {
                // Modo remoção de propaganda no meio do áudio:
                // Parte 1: do início (ou startSec) até o início do anúncio
                // Parte 2: do fim do anúncio até o final (ou endSec)
                val seg1Start = startSec
                val seg1End = ad.first
                val seg2Start = ad.second

                onProgress("Removendo propaganda e juntando partes...")
                val filter = if (endSec != null) {
                    val f = String.format(
                        Locale.US,
                        "[0:a]atrim=start=%.2f:end=%.2f,asetpts=PTS-STARTPTS[a1];[0:a]atrim=start=%.2f:end=%.2f,asetpts=PTS-STARTPTS[a2];[a1][a2]concat=n=2:v=0:a=1[out]",
                        seg1Start, seg1End, seg2Start, endSec
                    )
                    f
                } else {
                    val f = String.format(
                        Locale.US,
                        "[0:a]atrim=start=%.2f:end=%.2f,asetpts=PTS-STARTPTS[a1];[0:a]atrim=start=%.2f,asetpts=PTS-STARTPTS[a2];[a1][a2]concat=n=2:v=0:a=1[out]",
                        seg1Start, seg1End, seg2Start
                    )
                    f
                }

                val args = listOf(
                    "-y",
                    "-i", srcFile.absolutePath,
                    "-filter_complex", filter,
                    "-map", "[out]",
                    tmpOut.absolutePath
                )
                val (code, outLog) = runFFmpeg(ctx, args)
                if (code != 0) {
                    Log.w(TAG, "FFmpeg filter failed (code $code): $outLog")
                    false
                } else {
                    tmpOut.isFile && tmpOut.length() > 0L
                }
            } else {
                // Modo Corte de Início / Fim (ex: pular introdução de 20 min de emails):
                // 1ª Tentativa: Stream Copy ultrarrápido (-c copy)
                onProgress("Cortando áudio (cópia ultrarrápida)...")
                val copyArgs = mutableListOf("-y")
                if (startSec > 0f) {
                    copyArgs.addAll(listOf("-ss", String.format(Locale.US, "%.2f", startSec)))
                }
                if (endSec != null) {
                    copyArgs.addAll(listOf("-to", String.format(Locale.US, "%.2f", endSec)))
                }
                copyArgs.addAll(listOf("-i", srcFile.absolutePath, "-c", "copy", tmpOut.absolutePath))

                var (code, outLog) = runFFmpeg(ctx, copyArgs)
                var ok = code == 0 && tmpOut.isFile && tmpOut.length() > 0L

                if (!ok) {
                    // Fallback se -c copy falhar para o codec/container: recodifica o fluxo de áudio
                    onProgress("Ajustando formato de áudio...")
                    tmpOut.delete()
                    val reencodeArgs = mutableListOf("-y")
                    if (startSec > 0f) {
                        reencodeArgs.addAll(listOf("-ss", String.format(Locale.US, "%.2f", startSec)))
                    }
                    if (endSec != null) {
                        reencodeArgs.addAll(listOf("-to", String.format(Locale.US, "%.2f", endSec)))
                    }
                    reencodeArgs.addAll(listOf("-i", srcFile.absolutePath, tmpOut.absolutePath))
                    val res2 = runFFmpeg(ctx, reencodeArgs)
                    code = res2.first
                    outLog = res2.second
                    ok = code == 0 && tmpOut.isFile && tmpOut.length() > 0L
                }

                if (!ok) {
                    Log.e(TAG, "FFmpeg trim failed: $outLog")
                }
                ok
            }

            if (!success || !tmpOut.isFile || tmpOut.length() == 0L) {
                tmpOut.delete()
                error("Falha ao processar áudio com FFmpeg")
            }

            val finalFile: File
            if (options.replaceOriginal) {
                // Substitui o original
                onProgress("Finalizando e atualizando arquivo original...")
                val backup = File(parentDir, ".bak_${System.currentTimeMillis()}_${srcFile.name}")
                if (srcFile.renameTo(backup)) {
                    if (tmpOut.renameTo(srcFile)) {
                        backup.delete()
                        finalFile = srcFile
                    } else {
                        // Tenta copiar se renameTo falhar
                        tmpOut.copyTo(srcFile, overwrite = true)
                        tmpOut.delete()
                        backup.delete()
                        finalFile = srcFile
                    }
                } else {
                    tmpOut.copyTo(srcFile, overwrite = true)
                    tmpOut.delete()
                    finalFile = srcFile
                }
                // Reseta a posição salva para que comece a tocar do novo início
                Library.savePos(ctx, episode.id, 0L)
            } else {
                // Salva como nova versão "(Editado)"
                onProgress("Salvando nova versão editada...")
                val cleanStem = "${srcFile.nameWithoutExtension} (Editado)"
                val dest = File(parentDir, "$cleanStem.$ext")
                if (dest.exists()) dest.delete()
                if (!tmpOut.renameTo(dest)) {
                    tmpOut.copyTo(dest, overwrite = true)
                    tmpOut.delete()
                }
                // Copia a capa associada se existir
                val oldThumbs = Library.companionThumbs(srcFile)
                oldThumbs.firstOrNull()?.let { oldThumb ->
                    val newThumb = File(parentDir, "$cleanStem.${oldThumb.extension}")
                    runCatching { oldThumb.copyTo(newThumb, overwrite = true) }
                }
                finalFile = dest
            }

            // Atualiza tags de metadados embutidas
            Library.extractEmbeddedMetadata(finalFile)
            finalFile
        }
    }
}
