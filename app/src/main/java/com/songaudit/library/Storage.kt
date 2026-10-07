package com.songaudit.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A place music can live: the internal storage or a card. */
class Volume(val root: File, val label: String, val removable: Boolean) {
    val path: String get() = root.path
}

object Storage {

    fun volumes(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val out = ArrayList<Volume>()
        if (Build.VERSION.SDK_INT >= 30) {
            for (v in sm.storageVolumes) {
                val dir = v.directory ?: continue
                if (v.state != Environment.MEDIA_MOUNTED) continue
                out += Volume(dir, if (v.isPrimary) "Internal" else v.getDescription(context), v.isRemovable)
            }
        } else {
            // Before 11 a volume's root is only reachable through the app's own folder on it.
            for (dir in context.getExternalFilesDirs(null)) {
                dir ?: continue
                val root = File(dir.path.substringBefore("/Android/data/"))
                val primary = Environment.isExternalStorageRemovable(dir).not() && out.isEmpty()
                out += Volume(root, if (primary) "Internal" else "SD card", !primary)
            }
        }
        return out.distinctBy { it.path }
    }

    fun volumeOf(context: Context, path: String): Volume? =
        volumes(context).filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

/** The few choices the person makes, kept in shared preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Volume roots left out of the scan. Opt-out, so a newly inserted card is included. */
    var skippedRoots: Set<String>
        get() = prefs.getStringSet("skipped_roots", emptySet())!!
        set(value) = prefs.edit().putStringSet("skipped_roots", value).apply()

    /** Listen only on the charger, so a scan never drains a day's battery. */
    var onlyWhileCharging: Boolean
        get() = prefs.getBoolean("only_charging", false)
        set(value) = prefs.edit().putBoolean("only_charging", value).apply()

    var lastScan: Long
        get() = prefs.getLong("last_scan", 0)
        set(value) = prefs.edit().putLong("last_scan", value).apply()
}
