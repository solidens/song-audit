package com.songaudit.fix

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Putting a new head on a file whose audio stays exactly as it is.
 *
 * Same length: written over the old one in place, which takes milliseconds.
 * Any other length: a hidden copy is written next to the file -- new head,
 * then the audio bytes copied across -- checked, and renamed over it, so the
 * file is never half-written under its own name.
 */
object Rewrite {

    /** What the file was before: its old head, and how long the audio after it is, for putting it back. */
    class Undo(val head: ByteArray, val audioLength: Long)

    fun head(file: File): Head = when (file.extension.lowercase()) {
        "flac" -> FlacHead.read(file)
        "mp3" -> Id3Head.read(file)
        else -> throw WriteException("${file.extension.uppercase()} tags are not written yet")
    }

    fun writable(path: String): Boolean = path.substringAfterLast('.').lowercase() in WRITABLE

    val WRITABLE = setOf("flac", "mp3")

    /** How to put [file] back as it is now, should a fix need undoing. Taken before the fix. */
    fun snapshot(file: File): Undo {
        val head = head(file)
        val old = ByteArray(head.length.toInt())
        RandomAccessFile(file, "r").use { it.readFully(old) }
        return Undo(old, file.length() - head.length)
    }

    /** Applies [edit] to [file]. */
    fun apply(file: File, edit: TagEdit) {
        val head = head(file)
        replace(file, head.length, head.build(edit, head.length))
    }

    /** Puts an old head back, provided the audio behind the current one is still the same length. */
    fun undo(file: File, undo: Undo) {
        val head = head(file)
        if (file.length() - head.length != undo.audioLength) throw WriteException("the file has changed since")
        replace(file, head.length, undo.head)
    }

    fun replace(file: File, oldLength: Long, head: ByteArray) {
        val total = file.length()
        if (head.size.toLong() == oldLength) {
            RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write(head)
                it.fd.sync()
            }
            return
        }
        val temp = temp(file)
        try {
            FileOutputStream(temp).use { out ->
                out.write(head)
                FileInputStream(file).use { input ->
                    input.channel.position(oldLength)
                    input.copyTo(out, 1 shl 16)
                }
                out.fd.sync()
            }
            if (temp.length() != head.size + total - oldLength) throw WriteException("copy came out the wrong size")
            commit(temp, file)
        } finally {
            temp.delete()
        }
    }

    /** A hidden file next to [file]: the scan skips names that start with a dot. */
    fun temp(file: File) = File(file.parentFile, ".${file.name}.songaudit")

    /** Renames [temp] over [file]. Should the rename refuse to replace, the original is moved aside first. */
    fun commit(temp: File, file: File) {
        if (temp.renameTo(file)) return
        val aside = File(file.parentFile, ".${file.name}.songaudit-old")
        if (!file.renameTo(aside)) throw WriteException("could not replace ${file.name}")
        if (!temp.renameTo(file)) {
            aside.renameTo(file)
            throw WriteException("could not replace ${file.name}")
        }
        aside.delete()
    }

    // -- Undo files --------------------------------------------------------------

    private const val MAGIC = 0x53414844 // "SAHD"

    fun save(undo: Undo, to: File) {
        to.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(to).buffered()).use {
            it.writeInt(MAGIC)
            it.writeLong(undo.audioLength)
            it.writeInt(undo.head.size)
            it.write(undo.head)
        }
    }

    fun load(from: File): Undo = DataInputStream(FileInputStream(from).buffered()).use {
        if (it.readInt() != MAGIC) throw IOException("not an undo file")
        val audio = it.readLong()
        val head = ByteArray(it.readInt())
        it.readFully(head)
        Undo(head, audio)
    }
}
