package com.songaudit.fix

import com.songaudit.library.Album
import com.songaudit.library.Doctor
import java.io.File

/** Where a picture for an album can come from, without going online. */
sealed interface Art {
    /** Said in the plan: "folder.jpg one folder up". */
    val label: String

    class ImageFile(val file: File, override val label: String) : Art
    /** The picture embedded in a track of another copy of the album. */
    class Embedded(val path: String, override val label: String) : Art
    /** An image the person chose. */
    class Picked(val bytes: ByteArray) : Art {
        override val label = "the image you chose"
    }
}

/**
 * Looks for an album's missing cover where people and rippers leave them:
 * a scans folder inside it, the folder above a CD1/CD2 split, or another copy
 * of the same album elsewhere on the player.
 */
object CoverSearch {

    val IMAGES = setOf("jpg", "jpeg", "png")
    private val ART_FOLDERS = listOf("scan", "artwork", "art", "cover", "image", "booklet", "pics", "picture")
    private val NAMES = listOf("front", "cover", "folder", "album")

    fun find(album: Album, library: List<Album>): Art? {
        val dir = File(album.folder)
        folderImage(dir)?.let { return Art.ImageFile(it, "“${it.name}” in the folder") }

        dir.listFiles()?.filter { it.isDirectory && ART_FOLDERS.any { a -> it.name.lowercase().contains(a) } }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { sub -> named(sub, onlyIfAlone = true)?.let { return Art.ImageFile(it, "“${it.name}” in ${sub.name}") } }

        val parent = dir.parentFile
        if (parent != null && (DISC.matches(dir.name.trim()) || siblings(album, library).isNotEmpty())) {
            named(parent, onlyIfAlone = true)?.let { return Art.ImageFile(it, "“${it.name}” one folder up") }
        }

        // Another copy of the album: the same title and artist anywhere else.
        val copies = library.filter {
            it.folder != album.folder && it.title.equals(album.title, true) && it.artist.equals(album.artist, true)
        }
        for (copy in copies) {
            folderImage(File(copy.folder))?.let { return Art.ImageFile(it, "from the copy in ${shortPath(copy.folder)}") }
            copy.tracks.firstOrNull { it.pictures > 0 && Rewrite.writable(it.path) }?.let {
                return Art.Embedded(it.path, "from the copy in ${shortPath(copy.folder)}")
            }
        }
        // The other discs of a split album, whose art is the same art.
        for (sib in siblings(album, library)) {
            folderImage(File(sib.folder))?.let { return Art.ImageFile(it, "from ${File(sib.folder).name}") }
            sib.tracks.firstOrNull { it.pictures > 0 && Rewrite.writable(it.path) }?.let {
                return Art.Embedded(it.path, "from ${File(sib.folder).name}")
            }
        }
        return null
    }

    /** The image a folder already has for its album, if any: a cover-ish name first, or the only image. */
    fun folderImage(dir: File): File? = named(dir, onlyIfAlone = true)

    private fun named(dir: File, onlyIfAlone: Boolean): File? {
        val images = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in IMAGES && !it.name.startsWith(".") }
            ?: return null
        for (n in NAMES) images.firstOrNull { it.name.lowercase().startsWith(n) }?.let { return it }
        return if (onlyIfAlone) images.singleOrNull() else images.firstOrNull()
    }

    /** Folders next to this one holding the same album: CD1 and CD2. */
    private fun siblings(album: Album, library: List<Album>): List<Album> {
        val parent = album.folder.substringBeforeLast('/')
        return library.filter {
            it.folder != album.folder && it.folder.substringBeforeLast('/') == parent && it.title.equals(album.title, true)
        }
    }

    private fun shortPath(folder: String) = folder.split('/').takeLast(2).joinToString("/")

    private val DISC = Regex("""^(?:cd|disc|disk|part)\s*\d+$""", RegexOption.IGNORE_CASE)

    /** An embedded picture past the doctor's limits. */
    fun heavy(p: Pic): Boolean = p.data.size > Doctor.HEAVY_BYTES || maxOf(p.width, p.height) > Doctor.HEAVY_PIXELS
}

/** Decoding and re-encoding pictures is Android's job; the rest of the fixing is plain Kotlin. */
interface Images {
    /** Width and height, from the image's own header. */
    fun size(data: ByteArray): Pair<Int, Int>

    /** The picture no more than [max] pixels on its longer side, as a JPEG; null if it cannot be decoded. */
    fun fit(data: ByteArray, max: Int): ByteArray?

    companion object {
        /** Big enough for any player's screen, small enough to decode in a blink. */
        const val COVER_PX = 1000

        /** Already this small and a JPEG: embedded as it is. */
        const val SMALL_BYTES = 400_000
    }
}

/** A cover ready to embed: shrunk if it needs to be, as is if it does not. */
fun Images.cover(data: ByteArray, type: Int = Pic.FRONT, description: String = ""): Pic? {
    val (w, h) = size(data)
    if (w <= 0 || h <= 0) return null
    val jpeg = data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
    if (jpeg && maxOf(w, h) <= Images.COVER_PX && data.size <= Images.SMALL_BYTES) {
        return Pic(type, "image/jpeg", description, w, h, data)
    }
    val small = fit(data, Images.COVER_PX) ?: return null
    val (sw, sh) = size(small)
    return Pic(type, "image/jpeg", description, sw, sh, small)
}
