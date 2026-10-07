package fr.tear36.blus

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

class BlusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // osmdroid needs a writable cache + a user agent to stay within the OSM tile policy.
        Configuration.getInstance().apply {
            load(this@BlusApp, PreferenceManager.getDefaultSharedPreferences(this@BlusApp))
            userAgentValue = "fr.tear36.blus/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
        }
    }

    companion object {
        fun prefs(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }
}
