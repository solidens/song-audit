package com.songaudit.library

import android.content.Context
import android.media.MediaScannerConnection
import com.songaudit.fix.Rewrite
import java.io.File

/**
 * Nothing the audit suggests is deleted outright. Copies are moved into a
 * hidden folder at the root of the same volume -- a rename, so it is instant
 * and frees nothing yet -- and can be put back until the quarantine is emptied.
 *
 * Fixes leave something here too: a file's old tags before they were
 * rewritten, a small file each, and the original of a file that was replaced
 * by a smaller one. Putting either back undoes the fix.
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
                val target = place(batch, path)
                if (target == null) {
                    failed += path
                    continue
                }
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

    /** Keeps a file's old tags, for putting back. */
    fun backup(batch: Long, path: String, undo: Rewrite.Undo, label: String): Boolean {
        val target = place(batch, "$path.tags") ?: return false
        Rewrite.save(undo, target)
        db.backup(batch, path, target.path, target.length(), label)
        return true
    }

    /** Notes a file a fix created, so putting the fix back removes it. */
    fun added(batch: Long, path: String, label: String) {
        db.backup(batch, path, path, 0, label, Db.Moved.ADDED)
    }

    /**
     * Moves [path] here and puts [fresh] -- a finished, checked file in the same
     * folder -- in its place. If the new file cannot take the name, the old
     * one goes back.
     */
    fun replace(batch: Long, path: String, fresh: File, size: Long, label: String): Boolean {
        val target = place(batch, path) ?: return false
        val original = File(path)
        if (!original.renameTo(target)) return false
        if (!fresh.renameTo(original)) {
            target.renameTo(original)
            return false
        }
        db.quarantine(batch, path, target.path, size, label, Db.Moved.REPLACED)
        notifyMediaStore(listOf(path))
        return true
    }

    /** Where [path] goes inside the quarantine of its own volume, under [batch]. */
    private fun place(batch: Long, path: String): File? {
        val volume = Storage.volumeOf(context, path) ?: return null
        val root = File(volume.root, DIR).also { ensureRoot(it) }
        return File(root, "$batch" + path.removePrefix(volume.path)).also { it.parentFile?.mkdirs() }
    }

    fun restore(entries: List<Db.Moved>): List<String> {
        val failed = ArrayList<String>()
        val scanned = ArrayList<String>()
        for (e in entries) {
            val original = File(e.original)
            val ok = when (e.kind) {
                Db.Moved.TAGS -> untag(e)
                Db.Moved.ADDED -> original.delete() || !original.exists()
                Db.Moved.REPLACED -> {
                    // Our own smaller file makes way for the original.
                    original.parentFile?.mkdirs()
                    val aside = File(original.parentFile, ".${original.name}.songaudit-new")
                    val cleared = !original.exists() || original.renameTo(aside)
                    if (cleared && File(e.moved).renameTo(original)) {
                        aside.delete()
                        true
                    } else {
                        if (aside.exists()) aside.renameTo(original)
                        false
                    }
                }
                else -> {
                    if (original.exists()) {
                        false
                    } else {
                        original.parentFile?.mkdirs()
                        File(e.moved).renameTo(original)
                    }
                }
            }
            if (ok) {
                db.restored(e)
                scanned += e.original
            } else {
                failed += e.original
            }
        }
        notifyMediaStore(scanned)
        return failed
    }

    /** Writes a file's old tags back over the new ones. */
    private fun untag(e: Db.Moved): Boolean = try {
        val file = File(e.original)
        Rewrite.undo(file, Rewrite.load(File(e.moved)))
        db.idOf(e.original)?.let { id ->
            com.songaudit.audio.Probe.probe(file)?.let { db.putHeader(id, file.length(), file.lastModified(), it) }
        }
        File(e.moved).delete()
        true
    } catch (x: java.io.IOException) {
        false
    }

    /** The only place the app deletes anything of the person's, and only what it moved here itself. */
    fun empty(entries: List<Db.Moved>) {
        // What a fix added stays: emptying only gives up the way back.
        for (e in entries) if (e.kind != Db.Moved.ADDED) File(e.moved).deleteRecursively()
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

    fun notifyMediaStore(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    companion object {
        const val DIR = ".song-audit-quarantine"
    }
}
