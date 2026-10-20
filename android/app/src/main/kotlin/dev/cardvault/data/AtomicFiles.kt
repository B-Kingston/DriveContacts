package dev.cardvault.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Atomic file writes: write a temporary file, then rename it. Works on the JVM and on Android. */
object AtomicFiles {
    /** Replaces [file] with [bytes]. Readers see the old content or the new content, never a mix. */
    fun write(file: File, bytes: ByteArray) {
        val parent = file.absoluteFile.parentFile
        parent.mkdirs()
        val temp = File(parent, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** The content of [file], or null when it does not exist. */
    fun read(file: File): ByteArray? = if (file.isFile) file.readBytes() else null

    fun delete(file: File) {
        file.delete()
    }
}
