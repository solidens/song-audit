package com.songaudit.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.songaudit.audio.Probed
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plain SQLite. One table of tracks holds both what the headers said and what
 * listening found; the spectrum and fingerprint blobs ride along but are only
 * read when a screen or the matcher asks for them.
 */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, "audit.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT UNIQUE NOT NULL,
                folder TEXT NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                format TEXT, lossless INTEGER, sample_rate INTEGER, bits INTEGER, channels INTEGER,
                duration INTEGER, bitrate INTEGER,
                title TEXT, artist TEXT, album TEXT, album_artist TEXT,
                track INTEGER, track_total INTEGER, disc INTEGER, disc_total INTEGER,
                date TEXT, genre TEXT, compilation INTEGER,
                pictures INTEGER, picture_bytes INTEGER, picture_w INTEGER, picture_h INTEGER,
                audio_md5 TEXT, probe_error TEXT,
                deep_version INTEGER NOT NULL DEFAULT 0, deep_error TEXT,
                frame_errors INTEGER, md5_match INTEGER, truncated INTEGER, decoded_md5 TEXT,
                effective_bits INTEGER, dr REAL, peak REAL, cutoff INTEGER, cliff REAL, ultrasonic REAL,
                issues INTEGER NOT NULL DEFAULT 0,
                spectrum BLOB, fingerprint BLOB,
                hash TEXT,
                quarantined INTEGER NOT NULL DEFAULT 0,
                accepted INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL("CREATE INDEX tracks_folder ON tracks(folder)")
        db.execSQL("CREATE TABLE folders (path TEXT PRIMARY KEY, cover TEXT)")
        db.execSQL("CREATE TABLE edges (a INTEGER NOT NULL, b INTEGER NOT NULL, kind INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE quarantine (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch INTEGER NOT NULL,
                original TEXT NOT NULL,
                moved TEXT NOT NULL,
                size INTEGER NOT NULL,
                label TEXT,
                kind INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 0.2: findings the person keeps, and quarantine entries that are not plain moves.
            db.execSQL("ALTER TABLE tracks ADD COLUMN accepted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE quarantine ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val db: SQLiteDatabase get() = writableDatabase

    // -- Scan bookkeeping -------------------------------------------------

    class Stamp(val id: Long, val size: Long, val modified: Long)

    fun stamps(): Map<String, Stamp> {
        val out = HashMap<String, Stamp>()
        db.rawQuery("SELECT id, path, size, modified FROM tracks WHERE quarantined = 0", null).use { c ->
            while (c.moveToNext()) out[c.getString(1)] = Stamp(c.getLong(0), c.getLong(2), c.getLong(3))
        }
        return out
    }

    /** A new or changed file: everything known about its old self goes. */
    fun putProbe(path: String, folder: String, size: Long, modified: Long, p: Probed?, error: String?, damaged: Boolean) {
        val v = ContentValues().apply {
            put("path", path)
            put("folder", folder)
            put("size", size)
            put("modified", modified)
            put("probe_error", error)
            put("issues", if (damaged) com.songaudit.analysis.Issue.DAMAGED.bit else 0)
            if (p != null) {
                put("format", p.format)
                put("lossless", if (p.lossless) 1 else 0)
                put("sample_rate", p.sampleRate)
                put("bits", p.bits)
                put("channels", p.channels)
                put("duration", p.durationMs)
                put("bitrate", p.bitrate)
                val t = p.tags
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("album_artist", t.albumArtist)
                put("track", t.track)
                put("track_total", t.trackTotal)
                put("disc", t.disc)
                put("disc_total", t.discTotal)
                put("date", t.date)
                put("genre", t.genre)
                put("compilation", if (t.compilation) 1 else 0)
                val biggest = p.pictures.maxByOrNull { it.bytes }
                put("pictures", p.pictures.size)
                put("picture_bytes", biggest?.bytes ?: 0)
                put("picture_w", biggest?.width ?: 0)
                put("picture_h", biggest?.height ?: 0)
                put("audio_md5", p.audioMd5?.let(::hex))
            } else {
                put("format", path.substringAfterLast('.').uppercase())
            }
            put("deep_version", 0)
        }
        db.insertWithOnConflict("tracks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * New tags on a file whose audio is unchanged: what the header says is
     * replaced, what listening found stays.
     */
    fun putHeader(id: Long, size: Long, modified: Long, p: Probed) {
        val t = p.tags
        val biggest = p.pictures.maxByOrNull { it.bytes }
        val v = ContentValues().apply {
            put("size", size)
            put("modified", modified)
            put("title", t.title)
            put("artist", t.artist)
            put("album", t.album)
            put("album_artist", t.albumArtist)
            put("track", t.track)
            put("track_total", t.trackTotal)
            put("disc", t.disc)
            put("disc_total", t.discTotal)
            put("date", t.date)
            put("genre", t.genre)
            put("compilation", if (t.compilation) 1 else 0)
            put("pictures", p.pictures.size)
            put("picture_bytes", biggest?.bytes ?: 0)
            put("picture_w", biggest?.width ?: 0)
            put("picture_h", biggest?.height ?: 0)
            putNull("hash")
        }
        db.update("tracks", v, "id = ?", arrayOf(id.toString()))
    }

    fun idOf(path: String): Long? =
        db.rawQuery("SELECT id FROM tracks WHERE path = ?", arrayOf(path)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Marks findings as kept, or with [keep] false, opens them again. */
    fun accept(ids: Collection<Long>, mask: Int, keep: Boolean) = db.inTransaction {
        for (chunk in ids.chunked(500)) {
            val list = chunk.joinToString(",")
            if (keep) execSQL("UPDATE tracks SET accepted = accepted | $mask WHERE id IN ($list)")
            else execSQL("UPDATE tracks SET accepted = accepted & ~$mask WHERE id IN ($list)")
        }
    }

    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.inTransaction {
            for (chunk in ids.chunked(500)) {
                val list = chunk.joinToString(",")
                execSQL("DELETE FROM tracks WHERE id IN ($list)")
                execSQL("DELETE FROM edges WHERE a IN ($list) OR b IN ($list)")
            }
        }
    }

    fun setFolders(covers: Map<String, String?>) = db.inTransaction {
        execSQL("DELETE FROM folders")
        val v = ContentValues()
        for ((path, cover) in covers) {
            v.clear()
            v.put("path", path)
            v.put("cover", cover)
            insert("folders", null, v)
        }
    }

    fun coverFolders(): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT path FROM folders WHERE cover IS NOT NULL", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun setHash(id: Long, hash: String) {
        db.execSQL("UPDATE tracks SET hash = ? WHERE id = ?", arrayOf(hash, id))
    }

    fun hashes(): Map<Long, String> {
        val out = HashMap<Long, String>()
        db.rawQuery("SELECT id, hash FROM tracks WHERE hash IS NOT NULL AND quarantined = 0", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
        }
        return out
    }

    // -- Deep pass ---------------------------------------------------------

    class DeepRow(
        val deepError: String?,
        val frameErrors: Int,
        val md5Match: Int,
        val truncated: Boolean,
        val decodedMd5: String?,
        val effectiveBits: Int,
        val dr: Float,
        val peakDb: Float,
        val cutoffHz: Int,
        val cliffDb: Float,
        val ultrasonicDb: Float,
        val issues: Int,
        val spectrum: ByteArray?,
        val fingerprint: IntArray?,
    )

    fun putDeep(id: Long, version: Int, r: DeepRow) {
        val v = ContentValues().apply {
            put("deep_version", version)
            put("deep_error", r.deepError)
            put("frame_errors", r.frameErrors)
            put("md5_match", r.md5Match)
            put("truncated", if (r.truncated) 1 else 0)
            put("decoded_md5", r.decodedMd5)
            put("effective_bits", r.effectiveBits)
            put("dr", r.dr.takeUnless { it.isNaN() }?.toDouble())
            put("peak", r.peakDb.takeUnless { it.isNaN() || it.isInfinite() }?.toDouble())
            put("cutoff", r.cutoffHz)
            put("cliff", r.cliffDb.toDouble())
            put("ultrasonic", r.ultrasonicDb.toDouble())
            put("issues", r.issues)
            put("spectrum", r.spectrum)
            put("fingerprint", r.fingerprint?.let(::pack))
        }
        db.update("tracks", v, "id = ?", arrayOf(id.toString()))
    }

    fun spectrum(id: Long): ByteArray? =
        db.rawQuery("SELECT spectrum FROM tracks WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
        }

    fun fingerprints(): Map<Long, IntArray> {
        val out = HashMap<Long, IntArray>()
        db.rawQuery("SELECT id, fingerprint FROM tracks WHERE fingerprint IS NOT NULL AND quarantined = 0", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = unpack(c.getBlob(1))
        }
        return out
    }

    // -- Reading the library ----------------------------------------------

    fun tracks(quarantined: Boolean = false): List<Track> {
        val out = ArrayList<Track>()
        db.rawQuery("SELECT $COLUMNS FROM tracks WHERE quarantined = ?", arrayOf(if (quarantined) "1" else "0")).use { c ->
            while (c.moveToNext()) out += track(c)
        }
        return out
    }

    fun track(id: Long): Track? =
        db.rawQuery("SELECT $COLUMNS FROM tracks WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) track(c) else null
        }

    private fun track(c: Cursor): Track {
        fun s(i: Int) = if (c.isNull(i)) null else c.getString(i)
        fun f(i: Int) = if (c.isNull(i)) Float.NaN else c.getFloat(i)
        return Track(
            id = c.getLong(0), path = c.getString(1), folder = c.getString(2), size = c.getLong(3),
            modified = c.getLong(4), format = s(5) ?: "?", lossless = c.getInt(6) == 1, sampleRate = c.getInt(7),
            bits = c.getInt(8), channels = c.getInt(9), durationMs = c.getLong(10), bitrate = c.getInt(11),
            title = s(12), artist = s(13), album = s(14), albumArtist = s(15), track = c.getInt(16),
            trackTotal = c.getInt(17), disc = c.getInt(18), discTotal = c.getInt(19), date = s(20), genre = s(21),
            compilation = c.getInt(22) == 1, pictures = c.getInt(23), pictureBytes = c.getInt(24),
            pictureWidth = c.getInt(25), pictureHeight = c.getInt(26), audioMd5 = s(27), probeError = s(28),
            deepVersion = c.getInt(29), deepError = s(30), frameErrors = c.getInt(31),
            md5Match = if (c.isNull(32)) -1 else c.getInt(32), truncated = c.getInt(33) == 1, decodedMd5 = s(34),
            effectiveBits = c.getInt(35), dr = f(36), peakDb = f(37), cutoffHz = c.getInt(38), cliffDb = f(39),
            ultrasonicDb = f(40), issues = c.getInt(41), accepted = c.getInt(42),
        )
    }

    // -- Duplicates -------------------------------------------------------

    fun setEdges(edges: List<Edge>) = db.inTransaction {
        execSQL("DELETE FROM edges")
        val stmt = compileStatement("INSERT INTO edges (a, b, kind) VALUES (?, ?, ?)")
        for (e in edges) {
            stmt.bindLong(1, e.a)
            stmt.bindLong(2, e.b)
            stmt.bindLong(3, e.kind.ordinal.toLong())
            stmt.executeInsert()
        }
    }

    fun edges(): List<Edge> {
        val out = ArrayList<Edge>()
        db.rawQuery("SELECT a, b, kind FROM edges", null).use { c ->
            while (c.moveToNext()) out += Edge(c.getLong(0), c.getLong(1), Kind.entries[c.getInt(2)])
        }
        return out
    }

    // -- Quarantine -------------------------------------------------------

    class Moved(
        val id: Long,
        val batch: Long,
        val original: String,
        val moved: String,
        val size: Long,
        val label: String?,
        val kind: Int,
    ) {
        companion object {
            /** The file itself, moved aside. */
            const val SET_ASIDE = 0

            /** A file's tags as they were before a fix; [moved] is the undo file. */
            const val TAGS = 1

            /** The original of a file that was replaced by a smaller one at the same path. */
            const val REPLACED = 2

            /** A file a fix created, cover.jpg say: putting back the fix removes it, emptying keeps it. */
            const val ADDED = 3
        }
    }

    fun quarantine(batch: Long, original: String, moved: String, size: Long, label: String, kind: Int = Moved.SET_ASIDE) = db.inTransaction {
        insert("quarantine", null, entry(batch, original, moved, size, label, kind))
        relocate(this, original, moved, quarantined = true)
    }

    /** An undo file for a tag fix, or a file a fix added: the track itself stays where it is. */
    fun backup(batch: Long, original: String, undo: String, size: Long, label: String, kind: Int = Moved.TAGS) {
        db.insert("quarantine", null, entry(batch, original, undo, size, label, kind))
    }

    private fun entry(batch: Long, original: String, moved: String, size: Long, label: String, kind: Int) = ContentValues().apply {
        put("batch", batch)
        put("original", original)
        put("moved", moved)
        put("size", size)
        put("label", label)
        put("kind", kind)
    }

    fun restored(entry: Moved) = db.inTransaction {
        delete("quarantine", "id = ?", arrayOf(entry.id.toString()))
        if (entry.kind == Moved.TAGS || entry.kind == Moved.ADDED) return@inTransaction
        if (entry.kind == Moved.REPLACED) {
            // The smaller file that stood in its place is gone.
            val ids = ArrayList<Long>()
            rawQuery("SELECT id FROM tracks WHERE path = ?", arrayOf(entry.original)).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            for (id in ids) {
                execSQL("DELETE FROM tracks WHERE id = $id")
                execSQL("DELETE FROM edges WHERE a = $id OR b = $id")
            }
        }
        relocate(this, entry.moved, entry.original, quarantined = false)
    }

    fun emptied(entries: List<Moved>) = db.inTransaction {
        for (e in entries) {
            delete("quarantine", "id = ?", arrayOf(e.id.toString()))
            val prefix = e.moved + "/"
            delete("tracks", "path = ? OR substr(path, 1, ?) = ?", arrayOf(e.moved, prefix.length.toString(), prefix))
        }
        execSQL("DELETE FROM edges WHERE a NOT IN (SELECT id FROM tracks) OR b NOT IN (SELECT id FROM tracks)")
    }

    fun moved(): List<Moved> {
        val out = ArrayList<Moved>()
        db.rawQuery("SELECT id, batch, original, moved, size, label, kind FROM quarantine ORDER BY batch DESC, id", null).use { c ->
            while (c.moveToNext()) out += Moved(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5), c.getInt(6))
        }
        return out
    }

    /** Points every row at or under [from] to the same place under [to]. */
    private fun relocate(db: SQLiteDatabase, from: String, to: String, quarantined: Boolean) {
        val flag = if (quarantined) 1 else 0
        db.execSQL(
            "UPDATE tracks SET path = ?, folder = ?, quarantined = ? WHERE path = ?",
            arrayOf(to, to.substringBeforeLast('/'), flag, from),
        )
        // Prefix compared exactly: LIKE would fold case and treat _ as a wildcard.
        val prefix = "$from/"
        db.execSQL(
            "UPDATE tracks SET path = ? || substr(path, ?), folder = ? || substr(folder, ?), quarantined = ? " +
                "WHERE substr(path, 1, ?) = ?",
            arrayOf(to, from.length + 1, to, from.length + 1, flag, prefix.length, prefix),
        )
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        private const val COLUMNS = "id, path, folder, size, modified, format, lossless, sample_rate, bits, channels, " +
            "duration, bitrate, title, artist, album, album_artist, track, track_total, disc, disc_total, date, genre, " +
            "compilation, pictures, picture_bytes, picture_w, picture_h, audio_md5, probe_error, deep_version, " +
            "deep_error, frame_errors, md5_match, truncated, decoded_md5, effective_bits, dr, peak, cutoff, cliff, " +
            "ultrasonic, issues, accepted"

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { instance = it }
        }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        fun pack(a: IntArray): ByteArray =
            ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(a) }.array()

        fun unpack(b: ByteArray): IntArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            return IntArray(buf.remaining()).also { buf.get(it) }
        }
    }
}
