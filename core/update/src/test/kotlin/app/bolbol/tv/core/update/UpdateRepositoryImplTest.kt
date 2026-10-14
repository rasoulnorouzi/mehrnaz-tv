package app.bolbol.tv.core.update

import app.bolbol.tv.core.model.UpdateFailure
import app.bolbol.tv.core.model.UpdateState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateRepositoryImplTest {
    private class FakeStore : UpdateStore {
        override var lastCheckMillis = 0L
        override var cachedManifest: String? = null
        override var manifestUrlOverride = ""
    }

    private class FakeInstaller : ApkInstaller {
        var allowed = true
        var failWith: Exception? = null
        var installed: ByteArray? = null
        var installCalls = 0
        var settingsOpened = 0
        override fun canInstall() = allowed
        override fun install(apk: File) {
            installCalls++
            failWith?.let { throw it }
            installed = apk.readBytes()
        }
        override fun openPermissionSettings() { settingsOpened++ }
    }

    private val server = MockServer()
    private val store = FakeStore()
    private val installer = FakeInstaller()
    private val results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    private val scopeJob = Job()
    private lateinit var dir: File
    private var now = 1_000_000_000L
    private var free = Long.MAX_VALUE
    private lateinit var repo: UpdateRepositoryImpl

    private val apkBytes = ByteArray(300_000) { (it * 31).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    @Before fun setUp() {
        dir = File.createTempFile("upd", "dir").apply { delete(); mkdirs() }
        server.start()
        repo = newRepo(installedCode = 4)
    }

    @After fun tearDown() {
        scopeJob.cancel()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun newRepo(installedCode: Int, name: String = "1.4.0") = UpdateRepositoryImpl(
        client = OkHttpClient(),
        currentVersionCode = installedCode,
        currentVersionName = name,
        manifestUrlProvider = { server.url("/update.json") },
        store = store,
        installer = installer,
        downloadDir = File(dir, "updates"),
        freeSpace = { free },
        clock = { now },
        installResults = results,
        scope = CoroutineScope(Dispatchers.Unconfined + scopeJob),
        io = Dispatchers.Unconfined,
    )

    private fun manifestJson(code: Int = 5, bytes: ByteArray = apkBytes, sha: String = sha(bytes), url: String = server.url("/m.apk")) =
        """{"versionCode":$code,"versionName":"1.4.1","apkUrl":"$url","sizeBytes":${bytes.size},"sha256":"$sha","notesFa":"تازه"}"""

    private fun serveUpdate(json: String = manifestJson(), apk: ByteArray = apkBytes) {
        server.routes["/update.json"] = { MockResponse().setBody(json) }
        server.routes["/m.apk"] = { MockResponse().setBody(Buffer().write(apk)) }
    }

    // ---- check ---------------------------------------------------------------------------------------

    @Test fun newerVersionIsAvailable() = runBlocking {
        serveUpdate()
        repo.check(force = true)
        val s = repo.state.value as UpdateState.Available
        assertEquals(5, s.update.versionCode)
        assertEquals(now, store.lastCheckMillis)
    }

    @Test fun sameOrOlderVersionIsUpToDate() = runBlocking {
        serveUpdate(manifestJson(code = 4))
        repo.check(force = true)
        assertEquals(UpdateState.UpToDate("1.4.0"), repo.state.value)
    }

    @Test fun checkWithinADayUsesTheCacheAndSkipsTheNetwork() = runBlocking {
        serveUpdate()
        repo.check()
        assertEquals(1, server.hits("/update.json"))
        now += 23 * 3_600_000L
        val second = newRepo(installedCode = 4)
        second.check()
        assertEquals("no second request", 1, server.hits("/update.json"))
        assertTrue(second.state.value is UpdateState.Available)
        // the installed version caught up with the cached manifest
        val updated = newRepo(installedCode = 5, name = "1.4.1")
        updated.check()
        assertEquals(UpdateState.UpToDate("1.4.1"), updated.state.value)
        assertEquals(1, server.hits("/update.json"))
    }

    @Test fun forceBypassesTheThrottleAndOldChecksRefetch() = runBlocking {
        serveUpdate()
        repo.check()
        repo.check(force = true)
        assertEquals(2, server.hits("/update.json"))
        now += 25 * 3_600_000L
        repo.check()
        assertEquals(3, server.hits("/update.json"))
    }

    @Test fun aClockThatWentBackwardsStillChecks() = runBlocking {
        serveUpdate()
        repo.check()
        now -= 10 * 3_600_000L
        repo.check()
        assertEquals(2, server.hits("/update.json"))
    }

    @Test fun redirectsAreFollowed() = runBlocking {
        server.routes["/update.json"] = { MockResponse().setResponseCode(302).setHeader("Location", server.url("/real.json")) }
        server.routes["/real.json"] = { MockResponse().setBody(manifestJson()) }
        repo.check(force = true)
        assertTrue(repo.state.value is UpdateState.Available)
    }

    @Test fun httpErrorsAndGarbageAreClassified() = runBlocking {
        server.routes["/update.json"] = { MockResponse().setResponseCode(404) }
        repo.check(force = true)
        assertEquals(UpdateState.Failed(UpdateFailure.BAD_MANIFEST, null), repo.state.value)

        server.routes["/update.json"] = { MockResponse().setResponseCode(503) }
        repo.check(force = true)
        assertEquals(UpdateState.Failed(UpdateFailure.OFFLINE, null), repo.state.value)

        server.routes["/update.json"] = { MockResponse().setBody("<html>not json</html>") }
        repo.check(force = true)
        assertEquals(UpdateState.Failed(UpdateFailure.BAD_MANIFEST, null), repo.state.value)
        assertEquals("failed checks are not remembered", 0L, store.lastCheckMillis)
    }

    @Test fun unreachableServerIsOffline() = runBlocking {
        server.shutdown()
        repo.check(force = true)
        assertEquals(UpdateState.Failed(UpdateFailure.OFFLINE, null), repo.state.value)
    }

    @Test fun aFailedBackgroundCheckStaysSilent() = runBlocking {
        server.routes["/update.json"] = { MockResponse().setResponseCode(500) }
        repo.check()
        assertEquals(UpdateState.Idle, repo.state.value)
        assertEquals(0L, store.lastCheckMillis)
    }

    // ---- download + install ---------------------------------------------------------------------------

    @Test fun downloadVerifiesAndInstalls() = runBlocking {
        serveUpdate()
        repo.check(force = true)
        val seen = mutableListOf<UpdateState>()
        val job = launch(Dispatchers.Unconfined) { repo.state.collect { seen += it } }
        repo.downloadAndInstall()
        job.cancel()
        assertTrue(repo.state.value is UpdateState.Installing)
        assertArrayEquals(apkBytes, installer.installed)
        val percents = seen.filterIsInstance<UpdateState.Downloading>().map { it.percent }
        assertTrue("progress reported: $percents", percents.isNotEmpty())
        assertEquals("never goes backwards", percents.sorted(), percents)
        assertEquals(100, percents.last())
        assertTrue(File(dir, "updates").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun checksumMismatchIsRejectedAndDeleted() = runBlocking {
        serveUpdate(manifestJson(sha = "0".repeat(64)))
        repo.check(force = true)
        repo.downloadAndInstall()
        val s = repo.state.value as UpdateState.Failed
        assertEquals(UpdateFailure.CHECKSUM_MISMATCH, s.failure)
        assertEquals(0, installer.installCalls)
        assertEquals(0, File(dir, "updates").listFiles()!!.size)
    }

    @Test fun notEnoughSpaceStopsBeforeDownloading() = runBlocking {
        serveUpdate()
        repo.check(force = true)
        free = apkBytes.size.toLong() // needs about twice that plus a margin
        repo.downloadAndInstall()
        assertEquals(UpdateFailure.NO_SPACE, (repo.state.value as UpdateState.Failed).failure)
        assertEquals(0, server.hits("/m.apk"))
    }

    @Test fun missingApkIsADownloadFailure() = runBlocking {
        serveUpdate()
        server.routes["/m.apk"] = { MockResponse().setResponseCode(404) }
        repo.check(force = true)
        repo.downloadAndInstall()
        assertEquals(UpdateFailure.DOWNLOAD_FAILED, (repo.state.value as UpdateState.Failed).failure)
    }

    @Test fun truncatedApkFailsTheChecksum() = runBlocking {
        serveUpdate()
        server.routes["/m.apk"] = { MockResponse().setBody(Buffer().write(apkBytes.copyOf(1000))) }
        repo.check(force = true)
        repo.downloadAndInstall()
        assertEquals(UpdateFailure.CHECKSUM_MISMATCH, (repo.state.value as UpdateState.Failed).failure)
    }

    @Test fun withoutTheInstallPermissionNothingIsDownloaded() = runBlocking {
        serveUpdate()
        installer.allowed = false
        repo.check(force = true)
        repo.downloadAndInstall()
        assertTrue(repo.state.value is UpdateState.NeedsInstallPermission)
        assertEquals(0, server.hits("/m.apk"))
        repo.openInstallPermissionSettings()
        assertEquals(1, installer.settingsOpened)
        // the user flips the switch and presses the button again
        installer.allowed = true
        repo.downloadAndInstall()
        assertTrue(repo.state.value is UpdateState.Installing)
        assertEquals(1, installer.installCalls)
    }

    @Test fun installerFailureIsReported() = runBlocking {
        serveUpdate()
        installer.failWith = java.io.IOException("session failed")
        repo.check(force = true)
        repo.downloadAndInstall()
        assertEquals(UpdateFailure.INSTALL_FAILED, (repo.state.value as UpdateState.Failed).failure)
    }

    @Test fun retryAfterAFailureDownloadsAgain() = runBlocking {
        serveUpdate()
        server.routes["/m.apk"] = { MockResponse().setResponseCode(500) }
        repo.check(force = true)
        repo.downloadAndInstall()
        assertTrue(repo.state.value is UpdateState.Failed)
        server.routes["/m.apk"] = { MockResponse().setBody(Buffer().write(apkBytes)) }
        repo.downloadAndInstall()
        assertTrue(repo.state.value is UpdateState.Installing)
    }

    @Test fun downloadAndInstallIsIgnoredWhenNothingIsAvailable() = runBlocking {
        repo.downloadAndInstall()
        assertEquals(UpdateState.Idle, repo.state.value)
        assertEquals(0, installer.installCalls)
    }

    @Test fun installResultsUpdateTheState() = runBlocking {
        serveUpdate()
        repo.check(force = true)
        repo.downloadAndInstall()
        assertTrue(repo.state.value is UpdateState.Installing)
        results.emit(InstallResult.Aborted)
        assertTrue("cancelled: offer it again", repo.state.value is UpdateState.Available)

        repo.downloadAndInstall()
        results.emit(InstallResult.Failed("INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(UpdateFailure.INSTALL_FAILED, (repo.state.value as UpdateState.Failed).failure)
    }

    @Test fun staleApksAreDeleted() = runBlocking {
        val updates = File(dir, "updates").apply { mkdirs() }
        File(updates, "mehrnaz-v3.apk").writeText("old")
        File(updates, "mehrnaz-v4.apk.part").writeText("half")
        serveUpdate()
        repo.check(force = true)
        repo.downloadAndInstall()
        assertEquals(listOf("mehrnaz-v5.apk"), updates.listFiles()!!.map { it.name })
        // after the update the new version's first check clears the installed APK
        val updated = newRepo(installedCode = 5, name = "1.4.1")
        serveUpdate(manifestJson(code = 5))
        updated.check(force = true)
        assertTrue(updates.listFiles()!!.isEmpty())
        assertFalse(File(updates, "mehrnaz-v5.apk").exists())
    }

    /** A tiny path-routed MockWebServer that counts requests per path. */
    private class MockServer {
        private val server = MockWebServer()
        val routes = java.util.concurrent.ConcurrentHashMap<String, () -> MockResponse>()
        private val counts = mutableMapOf<String, Int>()

        fun start() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    synchronized(counts) { counts[path] = (counts[path] ?: 0) + 1 }
                    return routes[path]?.invoke() ?: MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }

        fun url(path: String) = server.url(path).toString()
        fun hits(path: String) = synchronized(counts) { counts[path] ?: 0 }
        fun shutdown() = runCatching { server.shutdown() }
    }
}
