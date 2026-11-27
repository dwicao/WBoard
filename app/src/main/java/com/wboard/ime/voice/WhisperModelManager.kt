package com.wboard.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Manages on-device quantized Whisper GGML models stored strictly in private app filesDir.
 * Models are downloaded once on explicit user action, verified by byte size and SHA-256,
 * and afterwards run 100% offline with zero network calls.
 */
object WhisperModelManager {

    enum class ModelVariant(
        val id: String,
        val fileName: String,
        val displayName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String
    ) {
        TINY_Q5_1(
            id = "tiny_q5_1",
            fileName = "ggml-tiny-q5_1.bin",
            displayName = "Whisper Tiny Q5_1 (~31 MB · Cepat)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            sizeBytes = 32_152_673L,
            sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
        ),
        BASE_Q5_1(
            id = "base_q5_1",
            fileName = "ggml-base-q5_1.bin",
            displayName = "Whisper Base Q5_1 (~57 MB · Lebih Akurat)",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            sizeBytes = 59_707_625L,
            sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        );

        companion object {
            fun fromId(id: String?): ModelVariant =
                entries.firstOrNull { it.id == id } ?: TINY_Q5_1
        }
    }

    private val executor by lazy { Executors.newSingleThreadExecutor() }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun modelsDir(context: Context): File =
        File(context.applicationContext.filesDir, "whisper-models").apply { mkdirs() }

    fun modelFile(context: Context, variant: ModelVariant): File =
        File(modelsDir(context), variant.fileName)

    fun isModelInstalled(context: Context, variant: ModelVariant): Boolean {
        val f = modelFile(context, variant)
        return f.isFile && f.length() == variant.sizeBytes
    }

    fun activeModelFile(context: Context, preferredId: String?): Pair<ModelVariant, File>? {
        val preferred = ModelVariant.fromId(preferredId)
        if (isModelInstalled(context, preferred)) {
            return preferred to modelFile(context, preferred)
        }
        for (variant in ModelVariant.entries) {
            if (isModelInstalled(context, variant)) {
                return variant to modelFile(context, variant)
            }
        }
        return null
    }

    fun deleteAllModels(context: Context) {
        modelsDir(context).listFiles()?.forEach { it.delete() }
    }

    fun downloadModelAsync(
        context: Context,
        variant: ModelVariant,
        cancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        val appCtx = context.applicationContext
        executor.execute {
            val result = runCatching {
                val target = modelFile(appCtx, variant)
                if (isModelInstalled(appCtx, variant)) {
                    mainHandler.post { onProgress(variant.sizeBytes, variant.sizeBytes) }
                    return@runCatching target
                }
                val part = File(target.parentFile, "${target.name}.part").apply { delete() }
                try {
                    stream(variant.url) { input, contentLength ->
                        val total = if (contentLength > 0) contentLength else variant.sizeBytes
                        var done = 0L
                        var lastReport = 0L
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw IllegalStateException("Cancelled")
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                done += read
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 120 || done == total) {
                                    lastReport = now
                                    val snapDone = done
                                    mainHandler.post { onProgress(snapDone, total) }
                                }
                            }
                        }
                    }
                    if (part.length() != variant.sizeBytes) {
                        throw IllegalStateException(
                            "Incomplete download (${part.length()} / ${variant.sizeBytes} bytes)"
                        )
                    }
                    if (!sha256Matches(part, variant.sha256)) {
                        throw IllegalStateException("Model SHA-256 verification failed")
                    }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        throw IllegalStateException("Failed to save Whisper model")
                    }
                    target
                } catch (t: Throwable) {
                    part.delete()
                    throw t
                }
            }
            mainHandler.post { onComplete(result) }
        }
    }

    private fun sha256Matches(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    private fun <T> stream(url: String, read: (java.io.InputStream, Long) -> T): T {
        var address = url
        repeat(8) {
            val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "WBoard-model-downloader")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) throw IllegalStateException("Empty redirect Location")
                address = if (next.startsWith("http")) next else URL(URL(address), next).toString()
            } else {
                try {
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code")
                    }
                    return conn.inputStream.use { input -> read(input, conn.contentLengthLong) }
                } finally {
                    conn.disconnect()
                }
            }
        }
        throw IllegalStateException("Too many redirects")
    }
}
