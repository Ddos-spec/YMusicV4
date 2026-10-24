package io.ymusic.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import io.ymusic.ui.FileRenaming
import kmpcommon.WorkerThread
import java.io.File

/** Renames SAF documents (rides the folder's persisted tree grant) and plain `file://` files. */
@WorkerThread
class AndroidFileRenaming(private val resolver: ContentResolver) : FileRenaming {

    override fun nameOf(uri: String): String? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) return parsed.path?.let(::File)?.takeIf { it.exists() }?.name
        return resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override fun rename(uri: String, newName: String): String {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) {
            val source = File(checkNotNull(parsed.path) { "file uri without a path: $uri" })
            val target = File(source.parentFile, newName)
            // Shared storage is case-insensitive: a case-only rename finds the source itself "already there".
            check(!target.exists() || isSameFile(source, target)) { "a file named $newName already exists" }
            check(source.renameTo(target)) { "could not rename ${source.name}" }
            return Uri.fromFile(target).toString()
        }
        val renamed = DocumentsContract.renameDocument(resolver, parsed, newName)
        return checkNotNull(renamed) { "Android refused to rename this document" }.toString()
    }

    // java.nio.file.Files.isSameFile needs API 26.
    private fun isSameFile(first: File, second: File): Boolean {
        val firstStat = Os.stat(first.path)
        val secondStat = Os.stat(second.path)
        return firstStat.st_dev == secondStat.st_dev && firstStat.st_ino == secondStat.st_ino
    }
}
