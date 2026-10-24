package io.ymusic.desktop

import io.ymusic.ui.FileRenaming
import kmpcommon.WorkerThread
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmpcommon.uri.fileUri
import java.io.File

/** Renames local `file://` media in place. */
@WorkerThread
object DesktopFileRenaming : FileRenaming {

    override fun nameOf(uri: String): String? = File(Uri.parse(uri).filePath()).takeIf { it.exists() }?.name

    override fun rename(uri: String, newName: String): String {
        val source = File(Uri.parse(uri).filePath())
        val target = File(source.parentFile, newName)
        // A case-only rename on a case-insensitive volume finds the source itself "already there".
        check(!target.exists() || java.nio.file.Files.isSameFile(source.toPath(), target.toPath())) { "a file named $newName already exists" }
        check(source.renameTo(target)) { "could not rename ${source.name}" }
        return fileUri(target.absolutePath).toString()
    }
}
