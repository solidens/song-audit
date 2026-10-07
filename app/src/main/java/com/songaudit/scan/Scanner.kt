package com.songaudit.scan

import android.content.Context
import android.os.Process
import com.songaudit.analysis.Verdict
import com.songaudit.audio.Deep
import com.songaudit.audio.DeepResult
import com.songaudit.audio.Probe
import com.songaudit.library.Db
import com.songaudit.library.Duplicates
import com.songaudit.library.Settings
import com.songaudit.library.Storage
import com.songaudit.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * The whole audit, in the order that gives useful answers soonest:
 *
 *  1. find   -- walk the chosen volumes for audio files and cover images
 *  2. read   -- parse headers of new and changed files (seconds per thousand)
 *  3. hash   -- sample-hash files that share an exact size, for identical copies
 *  4. match  -- pair duplicates from what is known so far
 *  5. listen -- decode every unchecked file in full (hours, resumable)
 *  6. match  -- again, now with fingerprints
 *
 * Everything lands in the database as it is found, so stopping at any point
 * loses only the file in hand, and the next scan carries on from there.
 */
class Scanner(private val context: Context, private val charging: () -> Boolean) {

    private val db = Db.get(context)
    private val settings = Settings(context)

    suspend fun run() {
        val state = ScanState
        state.start()
        try {
            val files = find()
            read(files)
            hash()
            match()
            settings.lastScan = System.currentTimeMillis()
            listen()
            match()
            state.finish(null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            state.finish(null)
            throw e
        } catch (e: Exception) {
            state.finish(e.message ?: e.javaClass.simpleName)
        }
    }

    // -- 1. Find ------------------------------------------------------------

    private class Found(val file: File, val size: Long, val modified: Long)

    /** The volume roots this scan walks; anything outside them is left as the last scan saw it. */
    private var roots: List<String> = emptyList()

    private suspend fun find(): List<Found> {
        ScanState.phase(Phase.FINDING)
        val skipped = settings.skippedRoots
        val out = ArrayList<Found>()
        val covers = HashMap<String, String?>()
        val volumes = Storage.volumes(context).filter { it.path !in skipped }
        roots = volumes.map { it.path }
        for (volume in volumes) walk(volume.root, volume.root, out, covers)
        db.setFolders(covers)
        return out
    }

    private suspend fun walk(dir: File, root: File, out: MutableList<Found>, covers: MutableMap<String, String?>) {
        coroutineContext.ensureActive()
        val entries = dir.listFiles() ?: return
        var cover: String? = null
        var hasAudio = false
        for (f in entries) {
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                // The system's own app folders hold nothing anyone would call their music.
                if (dir == root && name == "Android") continue
                walk(f, root, out, covers)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in Probe.EXTENSIONS) {
                out += Found(f, f.length(), f.lastModified())
                hasAudio = true
                if (out.size % 200 == 0) ScanState.found(out.size)
            } else if (ext in IMAGES && (cover == null || COVER_NAMES.any { name.startsWith(it, ignoreCase = true) })) {
                cover = name
            }
        }
        if (hasAudio) covers[dir.path] = cover
        ScanState.found(out.size)
    }

    // -- 2. Read ------------------------------------------------------------

    private suspend fun read(files: List<Found>) {
        val known = db.stamps()
        val present = HashSet<String>(files.size * 2)
        val changed = files.filter { f ->
            present += f.file.path
            val s = known[f.file.path]
            s == null || s.size != f.size || s.modified != f.modified
        }
        // Gone means gone from a volume that was walked: a card that is out keeps its results.
        db.delete(known.filterKeys { path -> path !in present && roots.any { path.startsWith("$it/") } }.values.map { it.id })

        ScanState.phase(Phase.READING, total = changed.size)
        val done = AtomicInteger()
        parallel(changed, threads = 2) { f ->
            val (probed, error) = try {
                (Probe.probe(f.file) ?: AndroidDecode.probe(f.file)) to null
            } catch (e: IOException) {
                null to (e.message ?: "unreadable")
            } catch (e: RuntimeException) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            // A header this app parses itself and cannot read is damage; Android's retriever failing is not proof.
            val ours = f.file.extension.lowercase() in OWN_PARSERS
            db.putProbe(f.file.path, f.file.parent ?: "/", f.size, f.modified, probed, error, damaged = probed == null && ours)
            ScanState.read(done.incrementAndGet(), f.file.name)
        }
    }

    // -- 3. Hash ------------------------------------------------------------

    private suspend fun hash() {
        val tracks = db.tracks()
        val hashed = db.hashes()
        val todo = tracks.groupBy { it.size }.values.filter { it.size > 1 }.flatten().filter { it.id !in hashed }
        if (todo.isEmpty()) return
        ScanState.phase(Phase.HASHING, total = todo.size)
        val done = AtomicInteger()
        parallel(todo, threads = 2) { t ->
            sampleHash(File(t.path))?.let { db.setHash(t.id, it) }
            ScanState.read(done.incrementAndGet(), t.name)
        }
    }

    /** MD5 of the first, middle and last 64 KB. With equal sizes, that is identity for audio files. */
    private fun sampleHash(file: File): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val md = MessageDigest.getInstance("MD5")
            val chunk = ByteArray(65536)
            val len = raf.length()
            for (at in longArrayOf(0, len / 2 - chunk.size / 2, len - chunk.size)) {
                raf.seek(at.coerceAtLeast(0))
                val n = raf.read(chunk)
                if (n > 0) md.update(chunk, 0, n)
            }
            Db.hex(md.digest())
        }
    } catch (e: IOException) {
        null
    }

    // -- 4 & 6. Match -------------------------------------------------------

    private suspend fun match() {
        ScanState.phase(Phase.MATCHING)
        coroutineContext.ensureActive()
        val tracks = db.tracks()
        db.setEdges(Duplicates.edges(tracks, db.hashes(), db.fingerprints()))
        ScanState.changed()
    }

    // -- 5. Listen ----------------------------------------------------------

    private suspend fun listen() {
        val todo = db.tracks().filter { it.deepVersion < DEEP_VERSION && it.probeError == null }
            .sortedBy { it.path }
        if (todo.isEmpty()) return
        ScanState.phase(Phase.LISTENING, total = todo.size, bytes = todo.sumOf { it.size })
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
        parallel(todo, threads) { t ->
            while (!charging() && settings.onlyWhileCharging) {
                ScanState.waiting(true)
                delay(5_000)
            }
            ScanState.waiting(false)
            ScanState.listening(t.name)
            val result = listenTo(t)
            db.putDeep(t.id, DEEP_VERSION, row(t, result))
            ScanState.listened(done.incrementAndGet(), bytes.addAndGet(t.size))
        }
    }

    private suspend fun listenTo(t: Track): DeepResult {
        val scope = CoroutineScope(coroutineContext)
        val cancelled = { !scope.isActive }
        val file = File(t.path)
        return when (t.format) {
            "FLAC" -> Deep.flac(file, cancelled)
            "WAV", "AIFF" -> Deep.pcm(file, cancelled)
            "MP3", "AAC", "ALAC", "OGG", "OPUS" -> AndroidDecode.deep(file, cancelled)
            else -> DeepResult(null, 0, 0, 0, -1, NOT_DECODED)
        }
    }

    private fun row(t: Track, r: DeepResult): Db.DeepRow {
        val a = r.analysis
        // DSD and friends are not decoded on Android: no verdict either way, and not damage.
        val skipped = r.error == NOT_DECODED
        val issues = if (skipped) emptySet() else Verdict.issues(
            lossless = t.lossless,
            sampleRate = t.sampleRate,
            bits = t.bits,
            bitrate = t.bitrate,
            damaged = r.damaged,
            cutoffHz = a?.cutoffHz ?: 0,
            ultrasonicDropDb = a?.ultrasonicDropDb ?: 0f,
            effectiveBits = a?.effectiveBits ?: 0,
        )
        return Db.DeepRow(
            deepError = r.error,
            frameErrors = r.frameErrors,
            md5Match = r.md5Match,
            truncated = r.truncated,
            decodedMd5 = a?.md5?.let(Db::hex)?.takeIf { t.format != "FLAC" || r.md5Match != 1 },
            effectiveBits = a?.effectiveBits ?: 0,
            dr = a?.dr ?: Float.NaN,
            peakDb = a?.peakDb ?: Float.NaN,
            cutoffHz = a?.cutoffHz ?: 0,
            cliffDb = a?.cliffDb ?: 0f,
            ultrasonicDb = a?.ultrasonicDropDb ?: 0f,
            issues = com.songaudit.analysis.Issue.mask(issues),
            spectrum = a?.spectrum,
            fingerprint = a?.fingerprint?.takeIf { it.isNotEmpty() },
        )
    }

    /** Runs [work] over [items] on [threads] background-priority workers. */
    private suspend fun <T> parallel(items: List<T>, threads: Int, work: suspend (T) -> Unit) = coroutineScope {
        val next = AtomicInteger()
        (0 until threads).map {
            async(Dispatchers.IO) {
                // Below the music player, which must never stutter because of us.
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                while (true) {
                    ensureActive()
                    val i = next.getAndIncrement()
                    if (i >= items.size) break
                    work(items[i])
                }
            }
        }.awaitAll()
    }

    companion object {
        /** Bump when the analysis changes enough that old results should be redone. */
        const val DEEP_VERSION = 1

        const val NOT_DECODED = "not decoded on Android"

        private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        private val COVER_NAMES = listOf("cover", "folder", "front", "album")
        private val OWN_PARSERS = setOf("flac", "mp3", "wav", "aif", "aiff", "aifc", "dsf", "dff", "ape", "wv")
    }
}
