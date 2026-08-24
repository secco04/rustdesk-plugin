package de.lobianco.saftssh.rustdesk

import android.app.Application
import de.lobianco.saftssh.rustdesk.data.logging.LogFileManager

/** Only job: start log capture as early as possible, before anything else in the plugin runs. */
class RustDeskPluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFileManager.init(this)
    }
}
