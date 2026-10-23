package com.readcomic.update

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Updates of the app from its releases on GitHub, without leaving it: finds a newer build,
 * downloads its APK (checked against the size and SHA-256 that GitHub reports) and hands it to
 * Android's installer, which asks the reader to confirm and puts it over this one. An APK
 * signed by another key is refused by Android itself.
 *
 * The main app follows the releases "build-N", the preview ("Readcomic β") its pre-releases
 * "preview-N".
 */
class AppUpdater private constructor(context: Context) {
    private val app = context.applicationContext

    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class Available(val offer: UpdateOffer) : State

        data class Downloading(val offer: UpdateOffer, val progress: Float) : State

        /** Downloaded and checked; [launch]: hand it to the installer as soon as the screen is up. */
        data class Ready(val offer: UpdateOffer, val file: File, val launch: Boolean) : State

        data class Failed(val offer: UpdateOffer?, val reason: String) : State
    }

    /** What came of a check asked for by the reader. */
    enum class CheckResult { UP_TO_DATE, AVAILABLE, FAILED }

    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(app.cacheDir, "updates")
    private var download: Job? = null

    /** This is the preview build, "Readcomic β". */
    val preview: Boolean = app.packageName.endsWith(".preview")

    /** The build installed now (its version code). */
    val currentBuild: Long = runCatching { PackageInfoCompat.getLongVersionCode(packageInfo()) }.getOrDefault(0L)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the app looks for updates by itself (once a day, when it starts). */
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        // Downloads of builds installed by now (or older) are no longer needed.
        scope.launch {
            dir.listFiles()?.forEach { file ->
                val build = file.name.removePrefix(FILE_PREFIX).substringBefore('.').toLongOrNull()
                if (file.name.endsWith(".part") || build == null || build <= currentBuild) file.delete()
            }
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit { putBoolean(KEY_ENABLED, on) }
    }

    /** Looks for an update if it is time to: checking is on and the last check was long enough ago. */
    fun checkIfDue() {
        if (!_enabled.value || _state.value != State.Idle) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_EVERY_MILLIS) return
        scope.launch { check(manual = false) }
    }

    /**
     * Asks GitHub for a newer build. A check by the reader ([manual]) shows one even if they
     * put it off before, and says when something went wrong; one of its own stays quiet.
     */
    suspend fun check(manual: Boolean): CheckResult {
        val before = _state.value
        // Already on its way: nothing to look for.
        if (before is State.Downloading || before is State.Ready || before == State.Checking) return CheckResult.AVAILABLE
        _state.value = State.Checking
        val offer = try {
            withContext(Dispatchers.IO) { Releases.newest(fetchReleases(), preview, currentBuild) }
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (_: Exception) {
            _state.value = if (before is State.Available) before else State.Idle
            return CheckResult.FAILED
        }
        prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
        _state.value = when {
            offer == null -> State.Idle
            !manual && offer.build <= prefs.getInt(KEY_PUT_OFF, 0) -> State.Idle
            else -> State.Available(offer)
        }
        return if (offer == null) CheckResult.UP_TO_DATE else CheckResult.AVAILABLE
    }

    /** Not now: this build is not offered again by itself (a newer one is). */
    fun putOff() {
        val state = _state.value
        val offer = offerOf(state)
        if (offer != null) prefs.edit { putInt(KEY_PUT_OFF, offer.build) }
        download?.cancel()
        // Not kept for later: a newer build may well come first.
        if (state is State.Ready) scope.launch { state.file.delete() }
        _state.value = State.Idle
    }

    /** Downloads the offered build and checks it; then it is [State.Ready] to install. */
    fun download() {
        val offer = offerOf(_state.value) ?: return
        if (_state.value is State.Downloading) return
        download?.cancel()
        _state.value = State.Downloading(offer, 0f)
        download = scope.launch {
            val file = File(dir, "$FILE_PREFIX${offer.build}.apk")
            try {
                if (!isComplete(file, offer)) fetchApk(offer, file)
                checkPackage(file, offer)
                _state.value = State.Ready(offer, file, launch = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                _state.value = State.Failed(offer, e.message ?: "Не получилось скачать обновление")
            }
        }
    }

    /** Stops a download: the update stays offered. */
    fun cancel() {
        val offer = offerOf(_state.value)
        download?.cancel()
        _state.value = offer?.let { State.Available(it) } ?: State.Idle
    }

    /** The installer has been shown the downloaded build once; after that, only when asked. */
    fun launched() {
        val ready = _state.value as? State.Ready ?: return
        _state.value = ready.copy(launch = false)
    }

    /** Whether Android already lets this app install updates (else it asks the reader once). */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /**
     * Hands the downloaded build to Android's installer, from [context] (an activity on screen):
     * it asks to confirm, installs it over this app, and offers to open it again.
     */
    fun install(context: Context) {
        val ready = _state.value as? State.Ready ?: return
        if (!ready.file.exists()) {
            _state.value = State.Available(ready.offer)
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.file)
        @Suppress("DEPRECATION")
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, APK_TYPE)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, app.packageName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        _state.value = ready.copy(launch = false)
        try {
            context.startActivity(install)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(view)
            } catch (_: ActivityNotFoundException) {
                _state.value = State.Failed(ready.offer, "На телефоне не нашлось установщика приложений")
            }
        }
    }

    private fun offerOf(state: State): UpdateOffer? = when (state) {
        is State.Available -> state.offer
        is State.Downloading -> state.offer
        is State.Ready -> state.offer
        is State.Failed -> state.offer
        else -> null
    }

    private fun userAgent() = "Readcomic/$currentBuild (Android ${Build.VERSION.RELEASE})"

    private fun fetchReleases(): List<Release> {
        val connection = URI(RELEASES_URL).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", userAgent())
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub: ${connection.responseCode}")
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val assets = o.optJSONArray("assets") ?: JSONArray()
                Release(
                    tag = o.optString("tag_name"),
                    prerelease = o.optBoolean("prerelease"),
                    draft = o.optBoolean("draft"),
                    body = o.text("body"),
                    assets = (0 until assets.length()).map { k ->
                        val a = assets.getJSONObject(k)
                        ReleaseAsset(
                            name = a.optString("name"),
                            url = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            digest = a.text("digest"),
                        )
                    },
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun fetchApk(offer: UpdateOffer, file: File) {
        if (!offer.url.startsWith("https://")) throw IOException("Ненадёжная ссылка на обновление")
        dir.mkdirs()
        val part = File(file.path + ".part")
        val connection = URI(offer.url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent())
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Не получилось скачать обновление (${connection.responseCode})")
            val total = offer.size.takeIf { it > 0 } ?: connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != shown) {
                                shown = percent
                                _state.value = State.Downloading(offer, percent / 100f)
                            }
                        }
                    }
                }
            }
            if (offer.size > 0 && done != offer.size) throw IOException("Обновление скачалось не целиком")
            val sha = offer.sha256
            if (sha != null && hex(digest.digest()) != sha) throw IOException("Файл обновления повреждён")
            if (!part.renameTo(file)) throw IOException("Не получилось сохранить обновление")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    /** Downloaded before, whole and unchanged. */
    private fun isComplete(file: File, offer: UpdateOffer): Boolean {
        if (!file.exists() || (offer.size > 0 && file.length() != offer.size)) return false
        val sha = offer.sha256 ?: return offer.size > 0
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest()) == sha
    }

    /** The file is this app, the build it says it is. */
    private fun checkPackage(file: File, offer: UpdateOffer) {
        val pm = app.packageManager
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, 0)
        }
        if (info == null || info.packageName != app.packageName || PackageInfoCompat.getLongVersionCode(info) != offer.build.toLong()) {
            throw IOException("Скачанный файл — не та сборка Readcomic")
        }
    }

    private fun packageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0)
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/vladislav12384/Readcomic/releases?per_page=30"
        private const val APK_TYPE = "application/vnd.android.package-archive"
        private const val FILE_PREFIX = "Readcomic-"
        private const val TIMEOUT_MILLIS = 20_000
        private const val CHECK_EVERY_MILLIS = 20L * 60 * 60 * 1000
        private const val KEY_ENABLED = "check"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_PUT_OFF = "put_off"

        // Holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppUpdater? = null

        fun get(context: Context): AppUpdater =
            instance ?: synchronized(this) { instance ?: AppUpdater(context).also { instance = it } }

        /** A string field, null when it is missing, empty or JSON null (which optString gives as "null"). */
        private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
