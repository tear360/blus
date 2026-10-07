package fr.tear36.blus.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Android will not let an app replace itself silently, so the user always confirms
 * the last step — that is the standard sideload flow and the only one available
 * without elevated privileges.
 */
class ApkInstaller : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL) return
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: return
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "APK introuvable: $path")
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(install)
        } catch (e: Exception) {
            Log.e(TAG, "installation impossible", e)
        }
    }

    companion object {
        private const val TAG = "Blus/ApkInstall"
        const val ACTION_INSTALL = "fr.tear36.blus.INSTALL_APK"
        const val EXTRA_APK_PATH = "apk_path"

        fun intent(context: Context, file: File): Intent =
            Intent(context, ApkInstaller::class.java).apply {
                action = ACTION_INSTALL
                putExtra(EXTRA_APK_PATH, file.absolutePath)
            }

        /**
         * Since Android 8 the user must grant "install unknown apps" per application.
         * Returns an intent to that settings screen when the grant is missing.
         */
        fun requestUnknownSourcesPermission(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            val allowed = context.packageManager.canRequestPackageInstalls()
            if (allowed) return null
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val isSupportedPlatform: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}