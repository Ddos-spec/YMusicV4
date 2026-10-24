package io.ymusic.desktop

import io.ymusic.ui.FileRenaming
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renames local `file://` media in place. */
object DesktopFileRenaming : FileRenaming {

    override suspend fun nameOf(uri: String): String? = withContext(Dispatchers.IO) {
        File(Uri.parse(uri).filePath()).takeIf { it.exists() }?.name
    }

    override suspend fun rename(uri: String, newName: String): String = withContext(Dispatchers.IO) {
        val source = File(Uri.parse(uri).filePath())
        val target = File(source.parentFile, newName)
        // A case-only rename on a case-insensitive volume finds the source itself "already there".
        check(!target.exists() || java.nio.file.Files.isSameFile(source.toPath(), target.toPath())) { "a file named $newName already exists" }
        check(source.renameTo(target)) { "could not rename ${source.name}" }
        fileUri(target.absolutePath).toString()
    }
}
