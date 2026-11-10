package com.kreyenhagen.homewall.util

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [target] via a temp file in the same folder, synced to disk and then renamed over the
 * target, so a crash or power loss leaves either the old file or the new one, never half of one.
 */
fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        tmp.delete()
    }
}

fun writeTextAtomically(target: File, text: String) = writeAtomically(target) { it.write(text.toByteArray(Charsets.UTF_8)) }
