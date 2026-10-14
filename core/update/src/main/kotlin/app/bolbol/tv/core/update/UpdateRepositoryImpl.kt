package app.bolbol.tv.core.update

import android.content.Context
import app.bolbol.tv.core.model.AppUpdate
import app.bolbol.tv.core.model.UpdateFailure
import app.bolbol.tv.core.model.UpdateRepository
import app.bolbol.tv.core.model.UpdateState
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-app update (docs/update.md): reads `update.json`, compares versionCode, downloads the APK with progress,
 * verifies its SHA-256 and hands it to Android's PackageInstaller. State is a [StateFlow]; all I/O runs on
 * [io]. No credentials are involved and nothing is logged.
 *
 * Pass a **plain** OkHttpClient (no mirror-failover interceptor: the manifest and APK live on github.com, not
 * on a Bolboljan mirror). Only redirects, timeouts and the call itself are configured here.
 */
class UpdateRepositoryImpl internal constructor(
    client: OkHttpClient,
    private val currentVersionCode: Int,
    private val currentVersionName: String,
    private val manifestUrlProvider: () -> String,
    private val store: UpdateStore,
    private val installer: ApkInstaller,
    private val downloadDir: File,
    private val freeSpace: (File) -> Long,
    private val clock: () -> Long,
    installResults: Flow<InstallResult>,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : UpdateRepository {

    /**
     * @param context any context (the application context is kept)
     * @param okHttpClient plain client, see the class comment
     * @param currentVersionCode installed `BuildConfig.VERSION_CODE`
     * @param manifestUrlProvider current manifest URL; `UpdateSettings.manifestUrl()` honours the developer override
     * @param settings persistence for the 24 h throttle and the cached manifest
     */
    constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        currentVersionCode: Int,
        manifestUrlProvider: () -> String,
        settings: UpdateStore = UpdateSettings(context),
    ) : this(
        client = okHttpClient,
        currentVersionCode = currentVersionCode,
        currentVersionName = installedVersionName(context),
        manifestUrlProvider = manifestUrlProvider,
        store = settings,
        installer = PackageApkInstaller(context),
        downloadDir = File(context.applicationContext.cacheDir, DOWNLOAD_DIR),
        freeSpace = { it.usableSpace },
        clock = System::currentTimeMillis,
        installResults = InstallEvents.results,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        io = Dispatchers.IO,
    )

    private val manifestClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val downloadClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** One check or download at a time. */
    private val busy = Mutex()

    init {
        scope.launch { installResults.collect(::onInstallResult) }
    }

    override suspend fun check(force: Boolean) {
        if (_state.value.isBusy() || !busy.tryLock()) return
        try {
            val before = _state.value
            val now = clock()
            val last = store.lastCheckMillis
            val fresh = last in 1..now && now - last < CHECK_INTERVAL_MS
            if (!force && fresh) {
                // Answer from the last manifest instead of the network.
                val cached = store.cachedManifest?.let { evaluate(it) }
                if (cached != null) {
                    _state.value = cached
                    return
                }
            }
            _state.value = UpdateState.Checking
            val text = try {
                fetchManifest()
            } catch (e: CancellationException) {
                _state.value = before
                throw e
            } catch (e: ManifestHttpError) {
                fail(before, force, if (e.code in 500..599) UpdateFailure.OFFLINE else UpdateFailure.BAD_MANIFEST)
                return
            } catch (e: IOException) {
                fail(before, force, UpdateFailure.OFFLINE)
                return
            }
            val result = evaluate(text)
            if (result == null) {
                fail(before, force, UpdateFailure.BAD_MANIFEST)
                return
            }
            store.cachedManifest = text
            store.lastCheckMillis = now
            _state.value = result
            if (result is UpdateState.UpToDate) withContext(io) { cleanDownloads(keep = null) }
        } finally {
            busy.unlock()
        }
    }

    override suspend fun downloadAndInstall() {
        val update = when (val s = _state.value) {
            is UpdateState.Available -> s.update
            is UpdateState.NeedsInstallPermission -> s.update
            is UpdateState.Failed -> s.update ?: return
            else -> return
        }
        if (!busy.tryLock()) return
        try {
            if (!installer.canInstall()) {
                _state.value = UpdateState.NeedsInstallPermission(update)
                return
            }
            val file = withContext(io) { prepareAndDownload(update) } ?: return
            _state.value = UpdateState.Installing(update)
            try {
                withContext(io) { installer.install(file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, update)
            }
        } finally {
            busy.unlock()
        }
    }

    override fun openInstallPermissionSettings() = installer.openPermissionSettings()

    // ---- internals ----------------------------------------------------------------------------------

    private fun UpdateState.isBusy() = this is UpdateState.Downloading || this is UpdateState.Installing

    /** A failed *forced* check is shown; a failed background check leaves the earlier state alone. */
    private fun fail(before: UpdateState, force: Boolean, failure: UpdateFailure) {
        _state.value = if (force) UpdateState.Failed(failure, null) else before
    }

    private fun evaluate(manifestText: String): UpdateState? {
        val published = UpdateManifest.parse(manifestText) ?: return null
        return if (isNewer(published, currentVersionCode)) {
            UpdateState.Available(published)
        } else {
            UpdateState.UpToDate(currentVersionName)
        }
    }

    private suspend fun fetchManifest(): String = withContext(io) {
        val request = Request.Builder()
            .url(manifestUrlProvider())
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/json")
            .build()
        manifestClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ManifestHttpError(response.code)
            val body = response.body
            if (body.contentLength() > MAX_MANIFEST_BYTES) throw ManifestHttpError(response.code)
            val bytes = body.source().use { it.readByteArray() }
            if (bytes.size > MAX_MANIFEST_BYTES) throw ManifestHttpError(response.code)
            bytes.toString(Charsets.UTF_8)
        }
    }

    /** Returns the verified APK, or null after setting a Failed state. */
    private suspend fun prepareAndDownload(update: AppUpdate): File? {
        downloadDir.mkdirs()
        cleanDownloads(keep = null)
        if (update.sizeBytes > 0 && freeSpace(downloadDir) < spaceNeeded(update.sizeBytes)) {
            _state.value = UpdateState.Failed(UpdateFailure.NO_SPACE, update)
            return null
        }
        val target = File(downloadDir, "mehrnaz-v${update.versionCode}.apk")
        val part = File(downloadDir, target.name + ".part")
        _state.value = UpdateState.Downloading(update, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            download(update, part, digest)
        } catch (e: CancellationException) {
            part.delete()
            _state.value = UpdateState.Available(update)
            throw e
        } catch (e: IOException) {
            part.delete()
            val noSpace = e.message.orEmpty().let { "ENOSPC" in it || "No space left" in it }
            _state.value = UpdateState.Failed(
                if (noSpace) UpdateFailure.NO_SPACE else UpdateFailure.DOWNLOAD_FAILED,
                update,
            )
            return null
        }
        if (!digest.digest().toHex().equals(update.sha256, ignoreCase = true)) {
            part.delete()
            _state.value = UpdateState.Failed(UpdateFailure.CHECKSUM_MISMATCH, update)
            return null
        }
        if (!part.renameTo(target)) {
            part.delete()
            _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD_FAILED, update)
            return null
        }
        return target
    }

    private suspend fun download(update: AppUpdate, part: File, digest: MessageDigest) {
        val request = Request.Builder().url(update.apkUrl).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            var done = 0L
            var lastPercent = 0
            val buffer = ByteArray(64 * 1024)
            val ctx = currentCoroutineContext()
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = UpdateState.Downloading(update, percent)
                            }
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    private fun cleanDownloads(keep: File?) {
        downloadDir.listFiles()?.forEach { if (it != keep) it.delete() }
    }

    private fun onInstallResult(result: InstallResult) {
        val installing = _state.value as? UpdateState.Installing ?: return
        when (result) {
            InstallResult.Success -> Unit // the process is replaced by the new version
            InstallResult.Aborted -> _state.value = UpdateState.Available(installing.update)
            is InstallResult.Failed -> {
                cleanDownloads(keep = null)
                _state.value = UpdateState.Failed(UpdateFailure.INSTALL_FAILED, installing.update)
            }
        }
    }

    private class ManifestHttpError(val code: Int) : IOException("manifest HTTP $code")

    internal companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val DOWNLOAD_DIR = "updates"
        private const val SPACE_MARGIN = 32L * 1024 * 1024

        /** The download plus PackageInstaller's own copy of it, plus a margin. */
        fun spaceNeeded(sizeBytes: Long): Long = sizeBytes * 2 + SPACE_MARGIN

        private fun installedVersionName(context: Context): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
    }
}
