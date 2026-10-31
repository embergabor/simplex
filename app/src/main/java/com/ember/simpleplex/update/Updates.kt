package com.ember.simpleplex.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.ember.simpleplex.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * New versions come as GitHub releases of the public repo: tag "v0.2.<versionCode>", with the APK
 * (signed with the release key) attached. Simplex looks for a newer one and installs it through
 * Android's package installer, which asks the viewer to confirm (and, the first time, to allow
 * Simplex to install apps).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/embergabor/simplex/releases/latest"
    private const val TAG = "Simplex"

    data class Release(val version: String, val code: Int, val apkUrl: String)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** The version number in a release tag: "v0.2.57" → 57. */
    fun codeOf(tag: String): Int? = tag.substringAfterLast('.').toIntOrNull()

    /** The latest release when it's newer than this build, else null. Debug builds never update themselves. */
    suspend fun newer(): Release? = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) return@withContext null
        val json = runCatching {
            http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                JSONObject(r.body.string())
            }
        }.getOrElse { Log.i(TAG, "update check failed: ${it.message}"); return@withContext null }
        val tag = json.optString("tag_name")
        val code = codeOf(tag) ?: return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url") ?: return@withContext null
        Release(tag.removePrefix("v"), code, apk).takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    private var checkedAt = 0L
    private var cached: Release? = null

    /** [newer], asked of GitHub at most every 6 hours (Home asks on every visit). */
    suspend fun newerCached(): Release? {
        val now = System.currentTimeMillis()
        if (now - checkedAt > 6 * 3600_000L) {
            cached = newer()
            checkedAt = now
        }
        return cached
    }

    /** Downloads the release and hands it to Android's installer, which shows its own confirmation. */
    suspend fun install(context: Context, release: Release) = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("simplex.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResult::class.java), flags)
            session.commit(callback.intentSender)
        }
        file.delete()
    }
}

/** Shows Android's install confirmation when the installer asks for it; logs the outcome. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> Log.i("Simplex", "update install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
