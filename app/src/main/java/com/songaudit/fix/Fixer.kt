package com.songaudit.fix

import android.content.Context
import android.os.Process
import com.songaudit.audio.Deep
import com.songaudit.audio.Probe
import com.songaudit.library.Album
import com.songaudit.library.Db
import com.songaudit.library.Doctor
import com.songaudit.library.Duplicates
import com.songaudit.library.Problem
import com.songaudit.library.Quarantine
import com.songaudit.library.Track
import com.songaudit.scan.Phase
import com.songaudit.scan.ScanState
import com.songaudit.scan.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** One album's repairs, worked out and said in words before anything is written. */
class AlbumFix(
    val album: Album,
    val tags: TagPlan?,
    val cover: CoverFix?,
    /** Cover lines first, then tag lines. */
    val lines: List<String>,
    /** What was asked for and cannot be done. */
    val unknown: List<String>,
    /** The tracks that will be rewritten. */
    val tracks: List<Track>,
    /** The album has no cover: a picture can be chosen for it. */
    val coverWanted: Boolean,
)

sealed interface CoverFix {
    class Embed(val art: Art, val saveCopy: Boolean) : CoverFix
    class Shrink(val saveCopy: Boolean) : CoverFix
}

/** Work the service runs in place of a scan. */
sealed interface Job {
    class Retag(val fixes: List<AlbumFix>) : Job
    class Shrink(val tracks: List<Track>) : Job
}

/** The one job waiting to be picked up by the service. */
object Jobs {
    @Volatile
    private var next: Job? = null

    fun post(job: Job) {
        next = job
    }

    fun take(): Job? = next.also { next = null }
}

object Fixes {

    val FIXABLE = setOf(Problem.NO_COVER, Problem.HEAVY_COVER, Problem.SPLIT, Problem.MISSING_TAGS)

    fun heavy(t: Track) = t.pictureBytes > Doctor.HEAVY_BYTES || maxOf(t.pictureWidth, t.pictureHeight) > Doctor.HEAVY_PIXELS

    /**
     * What fixing [problems] in [album] would do. Touches the disk only to look
     * for a cover; [picked] is an image the person chose, which wins.
     */
    fun plan(album: Album, problems: Set<Problem>, library: List<Album>, picked: ByteArray? = null): AlbumFix {
        val tags = Planner.tags(album, problems).takeUnless { it.isEmpty && it.unknown.isEmpty() }
        val lines = ArrayList<String>()
        val unknown = ArrayList<String>()
        val touched = LinkedHashSet<Long>()
        tags?.edits?.keys?.let(touched::addAll)

        val folderHasImage = CoverSearch.folderImage(File(album.folder)) != null
        var cover: CoverFix? = null
        if (Problem.NO_COVER in problems) {
            val art = picked?.let { Art.Picked(it) } ?: CoverSearch.find(album, library)
            if (art == null) {
                unknown += "No picture anywhere nearby: choose one"
            } else {
                val inFolder = art is Art.ImageFile && art.file.parent == album.folder
                cover = CoverFix.Embed(art, saveCopy = !inFolder && !folderHasImage)
                val bare = album.tracks.filter { it.pictures == 0 }
                touched += bare.map { it.id }
                lines += "Cover: ${art.label} → embedded in ${count(bare.size)}" +
                    if (!inFolder && !folderHasImage) ", and saved as cover.jpg" else ""
            }
        } else if (Problem.HEAVY_COVER in problems) {
            val heavy = album.tracks.filter(::heavy)
            if (heavy.isNotEmpty()) {
                cover = CoverFix.Shrink(saveCopy = !folderHasImage)
                touched += heavy.map { it.id }
                val before = heavy.sumOf { it.pictureBytes.toLong() }
                lines += "Cover shrunk to ${Images.COVER_PX} px in ${count(heavy.size)} · ${Doctor.mb(before)} of pictures now" +
                    if (!folderHasImage) ". The full-size one is kept once, as cover.jpg" else ""
            }
        }
        tags?.let {
            lines += it.lines
            unknown += it.unknown
        }
        val tracks = album.tracks.filter { it.id in touched }
        val (writable, other) = tracks.partition { Rewrite.writable(it.path) }
        if (other.isNotEmpty()) {
            val formats = other.map { it.format }.distinct().joinToString(", ")
            unknown += "$formats: ${count(other.size)} left as they are, only FLAC and MP3 are written for now"
        }
        return AlbumFix(album, tags, cover, lines, unknown, writable, Problem.NO_COVER in problems)
    }

    private fun count(n: Int) = if (n == 1) "1 track" else "$n tracks"
}

/**
 * Carries out fixes on the files themselves. Before any file is touched, what
 * it was goes into the quarantine -- its old tags, or the whole original --
 * so every fix can be put back from there.
 */
class Fixer(private val context: Context, private val images: Images = AndroidImages()) {

    private val db = Db.get(context)
    private val quarantine = Quarantine(context)

    suspend fun run(job: Job) {
        try {
            withContext(Dispatchers.IO) {
                when (job) {
                    is Job.Retag -> retag(job.fixes)
                    is Job.Shrink -> shrink(job.tracks)
                }
            }
            ScanState.finish(null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            ScanState.say("Stopped. What was done stays done, and can be put back from quarantine.")
            ScanState.finish(null)
            throw e
        } catch (e: Exception) {
            ScanState.finish(e.message ?: e.javaClass.simpleName)
        }
    }

    // -- Tags and covers -----------------------------------------------------------

    private suspend fun retag(fixes: List<AlbumFix>) {
        val total = fixes.sumOf { it.tracks.size }
        ScanState.begin(Phase.FIXING, total)
        val batch = System.currentTimeMillis()
        var done = 0
        val failed = ArrayList<String>()
        for (fix in fixes) {
            val label = "${fix.album.artist} — ${fix.album.title}"
            val transform: ((List<Pic>) -> List<Pic>)? = try {
                pictures(fix) { added -> quarantine.added(batch, added.path, "Added by the fix · $label · ${added.name}") }
            } catch (e: IOException) {
                failed += "${fix.album.title}: ${e.message}"
                null
            }
            if (fix.cover != null && transform == null) {
                done += fix.tracks.size
                continue
            }
            for (t in fix.tracks) {
                coroutineContext.ensureActive()
                ScanState.read(done, t.name)
                val file = File(t.path)
                try {
                    if (file.length() != t.size || file.lastModified() != t.modified) throw WriteException("changed since the scan")
                    val edit = TagEdit(fix.tags?.edits?.get(t.id).orEmpty(), transform)
                    val undo = Rewrite.snapshot(file)
                    if (!quarantine.backup(batch, t.path, undo, "Tags before the fix · $label · ${file.name}")) {
                        throw WriteException("no room for the undo copy")
                    }
                    try {
                        Rewrite.apply(file, edit)
                        val probed = Probe.probe(file) ?: throw WriteException("unreadable after writing")
                        db.putHeader(t.id, file.length(), file.lastModified(), probed)
                    } catch (e: Exception) {
                        // Straight back as it was; should even that fail, the undo copy waits in quarantine.
                        try {
                            Rewrite.undo(file, undo)
                        } catch (_: Exception) {
                        }
                        throw e
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += "${file.name}: ${e.message ?: e.javaClass.simpleName}"
                }
                done++
            }
            ScanState.changed()
        }
        quarantine.notifyMediaStore(fixes.flatMap { f -> f.tracks.map { it.path } })
        ScanState.say(
            buildString {
                append("Fixed ${done - failed.size} of $total files")
                if (failed.isNotEmpty()) append(" · ${failed.size} not: ${failed.first()}")
            },
        )
    }

    /** How a fix changes each track's pictures; prepares the picture first, and the folder's cover.jpg. */
    private fun pictures(fix: AlbumFix, added: (File) -> Unit): ((List<Pic>) -> List<Pic>)? {
        val folder = File(fix.album.folder)
        return when (val c = fix.cover) {
            null -> null
            is CoverFix.Embed -> {
                val bytes = when (val art = c.art) {
                    is Art.ImageFile -> art.file.readBytes()
                    is Art.Picked -> art.bytes
                    is Art.Embedded -> Rewrite.head(File(art.path)).pictures.let { p -> p.firstOrNull { it.type == Pic.FRONT } ?: p.firstOrNull() }?.data
                        ?: throw WriteException("the picture is no longer there")
                }
                val pic = images.cover(bytes) ?: throw WriteException("the picture could not be read")
                if (c.saveCopy) saveCover(folder, bytes)?.let(added)
                val embed: (List<Pic>) -> List<Pic> = { existing -> listOf(pic) + existing }
                embed
            }
            is CoverFix.Shrink -> {
                if (c.saveCopy) {
                    // The full-size picture, kept once beside the tracks instead of in every one of them.
                    fix.tracks.firstOrNull()?.let { t ->
                        val pics = Rewrite.head(File(t.path)).pictures
                        (pics.firstOrNull { it.type == Pic.FRONT } ?: pics.maxByOrNull { it.data.size })
                            ?.let { saveCover(folder, it.data) }?.let(added)
                    }
                }
                val done = HashMap<Int, Pic>()
                val shrink: (List<Pic>) -> List<Pic> = { existing ->
                    existing.map { p ->
                        val (w, h) = images.size(p.data)
                        if (p.data.size <= Doctor.HEAVY_BYTES && maxOf(w, h) <= Doctor.HEAVY_PIXELS) {
                            p
                        } else {
                            done.getOrPut(p.data.contentHashCode()) {
                                images.fit(p.data, Images.COVER_PX)?.let { small ->
                                    val (sw, sh) = images.size(small)
                                    Pic(p.type, "image/jpeg", p.description, sw, sh, small)
                                } ?: p
                            }.let { Pic(p.type, it.mime, p.description, it.width, it.height, it.data) }
                        }
                    }
                }
                shrink
            }
        }
    }

    /** Writes the folder's cover file if it has none; returns it when it did. */
    private fun saveCover(folder: File, bytes: ByteArray): File? {
        if (CoverSearch.folderImage(folder) != null) return null
        val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val file = File(folder, if (png) "cover.png" else "cover.jpg")
        if (file.exists()) return null
        file.writeBytes(bytes)
        return file
    }

    // -- Shrinking -----------------------------------------------------------------

    private suspend fun shrink(tracks: List<Track>) {
        val todo = tracks.filter { Shrink.target(it) != null }
        ScanState.begin(Phase.SHRINKING, todo.size)
        val batch = System.currentTimeMillis()
        val done = AtomicInteger()
        val ok = AtomicInteger()
        val freed = AtomicLong()
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val next = AtomicInteger()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
        coroutineScope {
            (0 until threads).map {
                async(Dispatchers.IO) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    while (true) {
                        ensureActive()
                        val i = next.getAndIncrement()
                        if (i >= todo.size) break
                        val t = todo[i]
                        ScanState.listening(t.name)
                        try {
                            freed.addAndGet(shrinkOne(t, batch) { !isActive })
                            ok.incrementAndGet()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed += "${t.name}: ${e.message ?: e.javaClass.simpleName}"
                        }
                        ScanState.read(done.incrementAndGet(), t.name)
                        if (done.get() % 5 == 0) ScanState.changed()
                    }
                }
            }.awaitAll()
        }
        // The new files have new fingerprints and sizes: pair duplicates again.
        db.setEdges(Duplicates.edges(db.tracks(), db.hashes(), db.fingerprints()))
        ScanState.say(
            buildString {
                append("Shrunk ${ok.get()} of ${todo.size} · ${Doctor.mb(freed.get())} back once the quarantine is emptied")
                if (failed.isNotEmpty()) append(" · ${failed.size} not: ${failed.first()}")
            },
        )
    }

    /** Returns the bytes saved. */
    private fun shrinkOne(t: Track, batch: Long, cancelled: () -> Boolean): Long {
        val target = Shrink.target(t) ?: return 0
        val file = File(t.path)
        if (file.length() != t.size || file.lastModified() != t.modified) throw WriteException("changed since the scan")
        val temp = Rewrite.temp(file)
        try {
            Shrink.write(file, temp, target, cancelled)
            // Not trusted until it decodes clean and matches its own signature.
            val check = Deep.flac(temp, cancelled)
            if (check.error != null || check.damaged || check.md5Match != 1) throw WriteException("the new file did not check out")
            val size = temp.length()
            val album = "${t.albumArtist ?: t.artist ?: "?"} — ${t.album ?: File(t.folder).name}"
            if (!quarantine.replace(batch, t.path, temp, t.size, "Before shrinking to $target · $album · ${file.name}")) {
                throw WriteException("could not put the new file in place")
            }
            val probed = Probe.probe(file)
            db.putProbe(t.path, t.folder, file.length(), file.lastModified(), probed, null, damaged = false)
            db.idOf(t.path)?.let { id ->
                db.track(id)?.let { fresh -> db.putDeep(id, Scanner.DEEP_VERSION, Scanner.row(fresh, check)) }
            }
            return t.size - size
        } finally {
            temp.delete()
        }
    }
}
