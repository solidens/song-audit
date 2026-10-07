package com.songaudit.library

import com.songaudit.analysis.FingerprintIndex
import com.songaudit.analysis.Issue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How sure the audit is that two files are the same thing, strongest first. */
enum class Kind(val label: String) {
    IDENTICAL("Identical files"),
    SAME_AUDIO("Same audio"),
    SAME_RECORDING("Same recording"),
    SAME_TITLE("Same title and length"),
}

/** Two tracks that are the same, and how the audit knows. */
class Edge(val a: Long, val b: Long, val kind: Kind)

/** One copy in a duplicate group: a single track, or a whole album folder. */
class Copy(val folder: String, val tracks: List<Track>) {
    val size: Long = tracks.sumOf { it.size }
    val album: Album = Album(folder, tracks)
    val path: String get() = if (tracks.size == 1) tracks[0].path else folder
}

class DupGroup(
    val kind: Kind,
    /** Whole albums duplicated, rather than single tracks. */
    val albums: Boolean,
    /** Copies, best first: [keep] is the default choice, the rest go. */
    val copies: List<Copy>,
    /** Why the first copy wins, in a phrase. */
    val reason: String,
    /**
     * The same recording on a different release -- an album track and its
     * compilation appearance. Worth knowing; not worth deleting by default.
     */
    val otherRelease: Boolean,
) {
    val keep: Copy get() = copies.first()
    val reclaimable: Long get() = if (otherRelease) 0 else copies.drop(1).sumOf { it.size }
    val id: String get() = copies.joinToString("|") { it.path }
}

object Duplicates {

    // -- Finding the pairs ------------------------------------------------

    /**
     * Every pair of tracks that are the same, from the cheapest evidence up.
     * [hashes] covers only files that share their exact size with another.
     */
    fun edges(tracks: List<Track>, hashes: Map<Long, String>, prints: Map<Long, IntArray>): List<Edge> {
        val out = ArrayList<Edge>()
        val seen = HashSet<Long>()
        fun add(a: Long, b: Long, kind: Kind) {
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (seen.add(lo * 1_000_003 + hi)) out += Edge(lo, hi, kind)
        }
        fun chain(ids: List<Long>, kind: Kind) {
            for (i in 1 until ids.size) add(ids[0], ids[i], kind)
        }

        tracks.filter { it.id in hashes }.groupBy { hashes[it.id] to it.size }.values
            .filter { it.size > 1 }.forEach { chain(it.map(Track::id), Kind.IDENTICAL) }

        tracks.mapNotNull { t -> (t.audioMd5 ?: t.decodedMd5)?.let { it to t } }
            .groupBy({ it.first }, { it.second }).values
            .filter { it.size > 1 }.forEach { chain(it.map(Track::id), Kind.SAME_AUDIO) }

        val byId = tracks.associateBy { it.id }
        val printed = tracks.filter { (prints[it.id]?.size ?: 0) > 0 }
        val index = FingerprintIndex(printed.map { prints.getValue(it.id) })
        for (m in index.matches { a, b -> closeLength(printed[a], printed[b]) }) {
            add(printed[m.a].id, printed[m.b].id, Kind.SAME_RECORDING)
        }

        // Before the deep pass, titles are the only evidence. Once both tracks
        // have fingerprints, the fingerprints decide and titles are ignored.
        tracks.filter { it.title != null && it.artist != null }
            .groupBy { norm(it.artist!!) + "\u0000" + norm(it.title!!) }.values
            .filter { it.size in 2..12 }
            .forEach { group ->
                for (i in group.indices) for (j in i + 1 until group.size) {
                    val a = group[i]
                    val b = group[j]
                    val bothPrinted = (prints[a.id]?.size ?: 0) > 0 && (prints[b.id]?.size ?: 0) > 0
                    if (!bothPrinted && abs(a.durationMs - b.durationMs) <= 2000) add(a.id, b.id, Kind.SAME_TITLE)
                }
            }
        return out.filter { it.a in byId && it.b in byId }
    }

    /** A radio edit is the same recording but not the same track. */
    private fun closeLength(a: Track, b: Track): Boolean {
        val d = abs(a.durationMs - b.durationMs)
        return d <= 3000 || d <= maxOf(a.durationMs, b.durationMs) / 50
    }

    fun norm(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // -- Grouping ---------------------------------------------------------

    fun group(tracks: List<Track>, edges: List<Edge>): List<DupGroup> {
        val byId = tracks.associateBy { it.id }
        val uf = UnionFind<Long>()
        val kindOf = HashMap<Long, Kind>()
        for (e in edges) {
            if (e.a !in byId || e.b !in byId) continue
            uf.union(e.a, e.b)
            for (id in listOf(e.a, e.b)) kindOf[id] = minOf(kindOf[id] ?: e.kind, e.kind, compareBy { it.ordinal })
        }
        val components = uf.groups().map { ids -> ids.map { byId.getValue(it) } }

        // Albums: two folders where most of the smaller one has a twin in the other.
        val folderSize = tracks.groupingBy { it.folder }.eachCount()
        val matched = HashMap<kotlin.Pair<String, String>, Int>()
        for (comp in components) {
            val perFolder = comp.groupingBy { it.folder }.eachCount()
            val folders = perFolder.keys.sorted()
            for (i in folders.indices) for (j in i + 1 until folders.size) {
                val key = folders[i] to folders[j]
                matched[key] = (matched[key] ?: 0) + minOf(perFolder.getValue(folders[i]), perFolder.getValue(folders[j]))
            }
        }
        val albumUf = UnionFind<String>()
        for ((pair, count) in matched) {
            val smaller = minOf(folderSize.getValue(pair.first), folderSize.getValue(pair.second))
            if (count >= 2 && count >= kotlin.math.ceil(smaller * 0.8).toInt()) albumUf.union(pair.first, pair.second)
        }
        val albumOf = HashMap<String, Int>()
        val out = ArrayList<DupGroup>()
        for ((n, folders) in albumUf.groups().withIndex()) {
            folders.forEach { albumOf[it] = n }
            val copies = folders.map { f -> Copy(f, tracks.filter { it.folder == f }.sortedWith(Album.trackOrder)) }
            val kind = copies.flatMap { it.tracks }.mapNotNull { kindOf[it.id] }.maxByOrNull { it.ordinal } ?: Kind.SAME_RECORDING
            out += ranked(kind, albums = true, copies, otherRelease = false)
        }

        for (comp in components) {
            fun kind(members: List<Track>) = members.mapNotNull { kindOf[it.id] }.maxByOrNull { it.ordinal } ?: Kind.SAME_TITLE

            // Two copies in one folder -- "01 Song.flac" and "01 Song (1).flac" -- are always worth tidying.
            for (members in comp.groupBy { it.folder }.values) {
                if (members.size > 1) out += ranked(kind(members), albums = false, members.map { Copy(it.folder, listOf(it)) }, otherRelease = false)
            }

            // Across albums, one copy stands for each album (or album group) it appears on.
            val units = comp.groupBy { t -> albumOf[t.folder]?.let { "group:$it" } ?: t.folder }
            if (units.size < 2) continue
            val reps = units.values.map { members ->
                if (members.size == 1) members[0] else ranked(kind(members), false, members.map { Copy(it.folder, listOf(it)) }, false).keep.tracks[0]
            }
            val albumsTagged = reps.map { it.album?.let(::norm) }
            val sameAlbum = albumsTagged.all { it != null && it == albumsTagged[0] }
            out += ranked(kind(reps), albums = false, reps.map { Copy(it.folder, listOf(it)) }, otherRelease = !sameAlbum)
        }
        return out.sortedWith(compareBy<DupGroup> { it.otherRelease }.thenByDescending { it.reclaimable })
    }

    // -- Choosing ---------------------------------------------------------

    /** What a copy is worth keeping for, in the order the criteria are applied. */
    private class Merit(val copy: Copy) {
        /** A stray second copy inside a folder must not count as a more complete album. */
        val tracks = copy.tracks.distinctBy { if (it.track > 0) "${it.disc}/${it.track}" else it.path }
        val damaged = tracks.count { it.has(Issue.DAMAGED) }
        val source = tracks.minOf { sourceClass(it) }
        val count = tracks.size
        val resolution = tracks.minOf { resolution(it) }
        val dr = Album(copy.folder, tracks).dr
        val tags = tracks.count { it.title != null && it.artist != null && it.album != null && it.track > 0 } +
            tracks.count { it.pictures > 0 }
        val size = tracks.sumOf { it.size }
        val path = pathScore(copy.path)
    }

    /** 0 lossy, 1 lossless made from lossy, 2 lossless that might be, 3 lossless. */
    private fun sourceClass(t: Track): Int = when {
        !t.lossless -> 0
        t.has(Issue.LOSSY_SOURCE) -> 1
        t.has(Issue.MAYBE_LOSSY) -> 2
        else -> 3
    }

    /** What the file really holds: an upsampled 24/96 holds 16/44.1, a re-encoded 320 holds 128. */
    private fun resolution(t: Track): Long = when {
        !t.lossless -> (if (t.has(Issue.REENCODED)) 128 else t.bitrate).toLong()
        else -> {
            // An upsampled file's extra bits are the resampler's arithmetic, not the recording's.
            val fromCd = t.has(Issue.UPSAMPLED)
            val rate = if (fromCd) minOf(t.sampleRate, 44100) else t.sampleRate
            val bits = if (fromCd || t.has(Issue.PADDED)) minOf(t.bits, 16) else t.bits.coerceAtLeast(1)
            rate.toLong() * bits
        }
    }

    private val CLUTTER = Regex("""(?i)(\(\d+\)|\bcopy\b|копия|/download|/telegram|/tmp/|/temp/)""")

    private fun pathScore(path: String): Int = -(CLUTTER.findAll(path).count() * 1000) - path.length

    /** Two DR readings closer than this are the same master measured twice. */
    private const val DR_STEP = 2f

    private fun compare(a: Merit, b: Merit): Int {
        a.damaged.compareTo(b.damaged).let { if (it != 0) return it }
        b.source.compareTo(a.source).let { if (it != 0) return it }
        b.count.compareTo(a.count).let { if (it != 0) return it }
        b.resolution.compareTo(a.resolution).let { if (it != 0) return it }
        if (!a.dr.isNaN() && !b.dr.isNaN() && abs(a.dr - b.dr) >= DR_STEP) return b.dr.compareTo(a.dr)
        b.tags.compareTo(a.tags).let { if (it != 0) return it }
        a.size.compareTo(b.size).let { if (it != 0) return it }
        return b.path.compareTo(a.path)
    }

    /**
     * Insertion sort: the DR tolerance makes the comparison non-transitive,
     * which a library sort is allowed to reject. Groups are a handful of copies.
     */
    private fun rank(merits: List<Merit>): List<Merit> {
        val out = ArrayList<Merit>()
        for (m in merits) {
            var at = out.size
            while (at > 0 && compare(m, out[at - 1]) < 0) at--
            out.add(at, m)
        }
        return out
    }

    private fun ranked(kind: Kind, albums: Boolean, copies: List<Copy>, otherRelease: Boolean): DupGroup {
        val merits = rank(copies.map(::Merit))
        return DupGroup(kind, albums, merits.map { it.copy }, reason(merits[0], merits[1], kind), otherRelease)
    }

    private fun reason(best: Merit, next: Merit, kind: Kind): String = when {
        best.damaged != next.damaged -> "Undamaged; the other copy has errors"
        best.source != next.source -> if (next.source == 0) "Lossless" else "Real lossless; the other came from a lossy file"
        best.count != next.count -> "More complete: ${best.count} tracks against ${next.count}"
        best.resolution != next.resolution -> "Holds more: ${best.copy.album.quality} against ${next.copy.album.quality}"
        !best.dr.isNaN() && !next.dr.isNaN() && abs(best.dr - next.dr) >= DR_STEP ->
            "More dynamic: DR${best.dr.roundToInt()} against DR${next.dr.roundToInt()}"
        best.tags != next.tags -> "Better tags and cover"
        best.size != next.size -> {
            val fake = next.tracks.any { it.has(Issue.UPSAMPLED) || it.has(Issue.PADDED) }
            val ratio = next.size.toDouble() / best.size
            when {
                fake && ratio >= 1.2 -> String.format(Locale.US, "Same sound, %.1f× smaller", ratio)
                else -> "Same sound, smaller"
            }
        }
        kind == Kind.IDENTICAL -> "Identical; keeping the tidier path"
        else -> "Tidier path"
    }
}

class UnionFind<T> {
    private val parent = HashMap<T, T>()

    fun find(x: T): T {
        var root = parent.getOrPut(x) { x }
        while (parent.getValue(root) != root) root = parent.getValue(root)
        var cur = x
        while (cur != root) {
            val next = parent.getValue(cur)
            parent[cur] = root
            cur = next
        }
        return root
    }

    fun union(a: T, b: T) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    fun groups(): List<List<T>> = parent.keys.groupBy { find(it) }.values.filter { it.size > 1 }
}
