package com.wboard.ime.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Manual-only, SHA-256 verified in-app updater reading `version.json` from GitHub.
 * Never runs automatically in the background and never runs inside the IME service.
 */
object Updater {

    const val RELEASE_MANIFEST_URL =
        "https://github.com/dwicao/WBoard/releases/latest/download/version.json"
    const val FALLBACK_MANIFEST_URL =
        "https://raw.githubusercontent.com/dwicao/WBoard/main/version.json"
    private const val ALLOWED_APK_PREFIX =
        "https://github.com/dwicao/WBoard/releases/download/"

    fun isSafeApkUrl(url: String): Boolean =
        url.startsWith(ALLOWED_APK_PREFIX, ignoreCase = false) && url.endsWith(".apk")

    data class Info(
        val version: String,
        val versionCode: Long,
        val apkUrl: String,
        val sizeBytes: Long,
        val sha256: String,
        val releasedAt: String,
        val notes: List<String>
    ) {
        fun isNewerThan(currentCode: Long): Boolean = versionCode > currentCode
    }

    private val executor by lazy { Executors.newSingleThreadExecutor() }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun parseManifest(rawJson: String): Info {
        val json = JSONObject(rawJson)
        val notes = json.optJSONArray("notes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        return Info(
            version = json.getString("version"),
            versionCode = json.getLong("versionCode"),
            apkUrl = json.getString("apkUrl"),
            sizeBytes = json.optLong("sizeBytes", 0L),
            sha256 = json.optString("sha256", ""),
            releasedAt = json.optString("releasedAt", ""),
            notes = notes
        )
    }

    fun checkAsync(onResult: (Result<Info>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val raw = try {
                    getString(RELEASE_MANIFEST_URL)
                } catch (_: Throwable) {
                    getString(FALLBACK_MANIFEST_URL)
                }
                parseManifest(raw)
            }
            mainHandler.post { onResult(result) }
        }
    }

    fun downloadAsync(
        context: Context,
        info: Info,
        onProgress: (done: Long, total: Long) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        val appCtx = context.applicationContext
        executor.execute {
            val result = runCatching {
                require(isSafeApkUrl(info.apkUrl)) { "Untrusted APK download URL" }
                val dir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "WBoard-${info.version}.apk")
                if (isValidCachedApk(target, info)) {
                    mainHandler.post { onProgress(target.length(), target.length()) }
                    return@runCatching target
                }
                val part = File(dir, "${target.name}.part").apply { delete() }
                var total = info.sizeBytes
                stream(info.apkUrl) { input, contentLength ->
                    if (contentLength > 0) total = contentLength
                    var done = 0L
                    var lastReport = 0L
                    part.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 120 || done == total) {
                                lastReport = now
                                val snapDone = done
                                val snapTotal = total
                                mainHandler.post { onProgress(snapDone, snapTotal) }
                            }
                        }
                    }
                }
                if (total > 0 && part.length() != total) {
                    part.delete()
                    throw IllegalStateException("Download incomplete (${part.length()} / $total bytes)")
                }
                if (info.sha256.isNotBlank() && !sha256Matches(part, info.sha256)) {
                    part.delete()
                    throw IllegalStateException("SHA-256 verification failed")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    throw IllegalStateException("Failed to finalize downloaded APK")
                }
                target
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        } else {
            true
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun isValidCachedApk(file: File, info: Info): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (info.sizeBytes > 0 && file.length() != info.sizeBytes) return false
        if (info.sha256.isNotBlank() && !sha256Matches(file, info.sha256)) return false
        return true
    }

    private fun sha256Matches(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex.trim(), ignoreCase = true)
    }

    private fun getString(url: String): String =
        stream(url) { input, _ -> input.readBytes().toString(Charsets.UTF_8) }

    private fun <T> stream(url: String, read: (InputStream, Long) -> T): T {
        var address = url
        repeat(6) {
            val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "WBoard-updater")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) throw IllegalStateException("Redirect with empty Location")
                address = if (next.startsWith("http")) next else URL(URL(address), next).toString()
            } else {
                try {
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code")
                    }
                    val len = conn.contentLengthLong
                    return conn.inputStream.use { input -> read(input, len) }
                } finally {
                    conn.disconnect()
                }
            }
        }
        throw IllegalStateException("Too many redirects")
    }
}
