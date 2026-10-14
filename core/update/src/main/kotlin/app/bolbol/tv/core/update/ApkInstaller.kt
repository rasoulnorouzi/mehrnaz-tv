package app.bolbol.tv.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Outcome of a PackageInstaller session, delivered by [InstallResultReceiver]. */
internal sealed interface InstallResult {
    /** Installed; normally the process is replaced before anyone sees this. */
    data object Success : InstallResult

    /** The user pressed Back / Cancel on Android's install screen. */
    data object Aborted : InstallResult

    data class Failed(val message: String?) : InstallResult
}

/** Process-wide bridge from the manifest-declared receiver to the repository. */
internal object InstallEvents {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    val results: SharedFlow<InstallResult> = _results.asSharedFlow()

    fun emit(result: InstallResult) {
        _results.tryEmit(result)
    }
}

/** The Android-only half of an update, so the repository can be tested on the JVM. */
internal interface ApkInstaller {
    /** False until the user flips «install unknown apps» for this app. */
    fun canInstall(): Boolean

    /** Copies [apk] into a PackageInstaller session and commits it. Throws [IOException] on failure. */
    fun install(apk: File)

    fun openPermissionSettings()
}

internal class PackageApkInstaller(context: Context) : ApkInstaller {
    private val context = context.applicationContext

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("mehrnaz.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                session.commit(resultSender(sessionId))
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** Explicit broadcast to our receiver; mutable on API 31+ because the system fills in the status extras. */
    private fun resultSender(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
    ).intentSender

    override fun openPermissionSettings() {
        val pkg = Uri.parse("package:${context.packageName}")
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    internal companion object {
        const val ACTION_INSTALL_RESULT = "app.bolbol.tv.core.update.INSTALL_RESULT"
    }
}
