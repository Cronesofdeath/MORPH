package com.morph.app.engine

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Keep the descriptor open until the reader has finished opening and seeking the source. */
internal suspend fun <T : Any> ContentResolver.withLocalInput(uri: Uri, read: suspend (File) -> T): T? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return uri.path?.let(::File)?.takeIf(File::isFile)?.let { read(it) }
    }
    val descriptor = try {
        openFileDescriptor(uri, "r")
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }
    descriptor?.use { fd ->
        val file = File("/proc/self/fd/${fd.fd}")
        // Pipes and virtual documents still work through the staged-stream fallback.
        if (fd.statSize >= 0 && file.isFile && file.canRead() &&
            runCatching { Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR) }.isSuccess
        ) {
            return read(file)
        }
    }
    return null
}
