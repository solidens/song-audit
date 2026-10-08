package com.songaudit.fix

/**
 * What to change in one file's tags. Field names are Vorbis comment names
 * whatever the container; a null value removes the field.
 */
class TagEdit(
    val fields: Map<String, String?> = emptyMap(),
    /** Given the pictures the file has now, the ones it should have; null leaves them alone. */
    val pictures: ((List<Pic>) -> List<Pic>)? = null,
) {
    val isEmpty: Boolean get() = fields.isEmpty() && pictures == null

    companion object {
        const val TITLE = "TITLE"
        const val ARTIST = "ARTIST"
        const val ALBUM = "ALBUM"
        const val ALBUM_ARTIST = "ALBUMARTIST"
        const val TRACK = "TRACKNUMBER"
        const val TRACK_TOTAL = "TRACKTOTAL"
        const val COMPILATION = "COMPILATION"

        /** Other spellings of a field that readers also take, removed when the field is written. */
        val ALIASES: Map<String, List<String>> = mapOf(
            ALBUM_ARTIST to listOf("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST"),
            TRACK_TOTAL to listOf("TRACKTOTAL", "TOTALTRACKS"),
        )

        fun spellings(field: String): List<String> = ALIASES[field] ?: listOf(field)
    }
}

/** An embedded picture with its bytes, as read from or written to a file. */
class Pic(
    /** ID3/FLAC picture type: 3 is the front cover. */
    val type: Int,
    val mime: String,
    val description: String,
    val width: Int,
    val height: Int,
    val data: ByteArray,
) {
    companion object {
        const val FRONT = 3
    }
}

/** A container this app can rewrite the tags of: everything before the audio, rebuilt as a whole. */
interface Head {
    /** Bytes from the start of the file to the first byte of audio. */
    val length: Long
    val pictures: List<Pic>

    /**
     * The new head with [edit] applied: exactly [fit] bytes long when it fits
     * there (the file is then rewritten in place), otherwise as long as it
     * needs plus some room for next time.
     */
    fun build(edit: TagEdit, fit: Long): ByteArray
}

class WriteException(message: String) : java.io.IOException(message)
