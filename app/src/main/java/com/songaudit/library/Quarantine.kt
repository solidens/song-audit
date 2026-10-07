package com.songaudit.library

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/**
 * Nothing the audit suggests is deleted outright. Copies are moved into a
 * hidden folder at the root of the same volume -- a rename, so it is instant
 * and frees nothing yet -- and can be put back until the quarantine is emptied.
 *
 * The folder starts with a dot and carries a .nomedia file, so neither
 * Android's media library nor a player's own scanner should list what is in it.
 */
class Quarantine(private val context: Context) {

    private val db = Db.get(context)

    class Outcome(val moved: Int, val bytes: Long, val failed: List<String>)

    fun move(copies: List<Copy>, library: List<Track>): Outcome {
        val batch = System.currentTimeMillis()
        val inFolder = library.groupingBy { it.folder }.eachCount()
        val folders = library.map { it.folder }.toSet()
        val failed = ArrayList<String>()
        val scanned = ArrayList<String>()
        var moved = 0
        var bytes = 0L
        for (copy in copies) {
            // A whole album folder goes as a folder, with its cover, cue and log;
            // a folder with anything else of the library in it goes file by file.
            val whole = copy.tracks.size > 1 && copy.tracks.size == inFolder[copy.folder] &&
                folders.none { it.startsWith(copy.folder + "/") }
            val label = copy.album.let { "${it.artist} — ${it.title}" }
            val sources = if (whole) listOf(copy.folder) else copy.tracks.map { it.path }
            for (path in sources) {
                val volume = Storage.volumeOf(context, path)
                if (volume == null) {
                    failed += path
                    continue
                }
                val root = File(volume.root, DIR).also { ensureRoot(it) }
                val target = File(root, "$batch" + path.removePrefix(volume.path))
                target.parentFile?.mkdirs()
                val size = if (whole) copy.size else copy.tracks.first { it.path == path }.size
                if (File(path).renameTo(target)) {
                    db.quarantine(batch, path, target.path, size, if (whole) label else "$label · ${File(path).name}")
                    moved += if (whole) copy.tracks.size else 1
                    bytes += size
                } else {
                    failed += path
                }
            }
            scanned += copy.tracks.map { it.path }
        }
        notifyMediaStore(scanned)
        return Outcome(moved, bytes, failed)
    }

    fun restore(entries: List<Db.Moved>): List<String> {
        val failed = ArrayList<String>()
        val scanned = ArrayList<String>()
        for (e in entries) {
            val original = File(e.original)
            if (original.exists()) {
                failed += e.original
                continue
            }
            original.parentFile?.mkdirs()
            if (File(e.moved).renameTo(original)) {
                db.restored(e)
                scanned += e.original
            } else {
                failed += e.original
            }
        }
        notifyMediaStore(scanned)
        return failed
    }

    /** The only place the app deletes anything, and only what it moved here itself. */
    fun empty(entries: List<Db.Moved>) {
        for (e in entries) File(e.moved).deleteRecursively()
        db.emptied(entries)
        for (volume in Storage.volumes(context)) {
            File(volume.root, DIR).listFiles()?.forEach { batch ->
                if (batch.isDirectory && batch.walkBottomUp().none { it.isFile }) batch.deleteRecursively()
            }
        }
    }

    private fun ensureRoot(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
    }

    private fun notifyMediaStore(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    companion object {
        const val DIR = ".song-audit-quarantine"
    }
}
