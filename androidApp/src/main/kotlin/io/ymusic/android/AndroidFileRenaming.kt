package io.ymusic.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.ymusic.ui.FileRenaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renames SAF documents (rides the folder's persisted tree grant) and plain `file://` files. */
class AndroidFileRenaming(private val resolver: ContentResolver) : FileRenaming {

    override suspend fun nameOf(uri: String): String? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) return@withContext parsed.path?.let(::File)?.takeIf { it.exists() }?.name
        resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override suspend fun rename(uri: String, newName: String): String = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) {
            val source = File(checkNotNull(parsed.path) { "file uri without a path: $uri" })
            val target = File(source.parentFile, newName)
            check(!target.exists()) { "a file named $newName already exists" }
            check(source.renameTo(target)) { "could not rename ${source.name}" }
            return@withContext Uri.fromFile(target).toString()
        }
        val renamed = DocumentsContract.renameDocument(resolver, parsed, newName)
        checkNotNull(renamed) { "Android refused to rename this document" }.toString()
    }
}
