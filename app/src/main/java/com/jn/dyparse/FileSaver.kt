package com.jn.dyparse

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

private const val PROGRESS_UPDATE_INTERVAL_MS = 120L
private const val MAX_SAVE_ATTEMPTS = 3
private const val SAVE_RETRY_DELAY_MS = 450L
private const val MAX_MEDIASTORE_NAME_ATTEMPTS = 50
private const val STREAM_BUFFER_SIZE = 256 * 1024
private const val MIN_PARALLEL_VIDEO_BYTES = 24L * 1024L * 1024L
private const val RANGE_SEGMENT_TARGET_BYTES = 16L * 1024L * 1024L
private const val MAX_PARALLEL_VIDEO_RANGES = 24
private const val MAX_RANGE_SEGMENT_ATTEMPTS = 3

private data class RangeProbe(
    val totalLength: Long,
    val segmentCount: Int
)

private data class ByteRange(
    val start: Long,
    val end: Long
) {
    val length: Long
        get() = end - start + 1
}

private fun interface RangeChunkWriter {
    @Throws(IOException::class)
    fun write(position: Long, buffer: ByteArray, byteCount: Int)
}

private class UnsupportedRangeDownloadException(message: String) : IOException(message)

private class PartialRangeDownloadException(
    val bytesWritten: Long,
    cause: IOException
) : IOException(cause)

suspend fun saveFile(
    context: Context,
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
    mimeType: String,
    timestamp: Long,
    index: Int = 0,
    fileName: String? = null,
    probe: SaveSizeProbe? = null,
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
): Boolean = withContext(Dispatchers.IO) {
    val uniqueSuffix = url.hashCode().toString().replace("-", "")
    val isVideo = mimeType.startsWith("video")
    val defaultFileName = if (isVideo) {
        "dy_${timestamp}_${uniqueSuffix}.mp4"
    } else {
        "dy_${timestamp}_${index + 1}_${uniqueSuffix}.jpg"
    }

    val customFileName = fileName?.takeIf { it.isNotBlank() }
    val finalFileName = when {
        isVideo -> ensureExtension(customFileName ?: defaultFileName, "mp4")
        customFileName != null -> ensureExtension(customFileName, "jpg")
        else -> defaultFileName
    }

    val subPath = if (isVideo) "dyparse/video" else "dyparse/image"
    var lastError: Exception? = null

    repeat(MAX_SAVE_ATTEMPTS) { attempt ->
        try {
            downloadAndSaveOnce(
                context = context,
                client = client,
                url = url,
                headers = headers,
                mimeType = mimeType,
                finalFileName = finalFileName,
                subPath = subPath,
                probe = probe,
                onProgress = onProgress
            )
            return@withContext true
        } catch (error: Exception) {
            lastError = error
            val retry = attempt < MAX_SAVE_ATTEMPTS - 1 && shouldRetrySave(error)
            Log.w(
                "SaveError",
                "save attempt ${attempt + 1}/$MAX_SAVE_ATTEMPTS failed for $url, retry=$retry",
                error
            )
            if (retry) {
                delay(SAVE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
    }

    Log.e("SaveError", "save file failed: $url", lastError)
    withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, "保存失败: ${lastError?.message}", Toast.LENGTH_SHORT).show()
    }
    false
}

private suspend fun downloadAndSaveOnce(
    context: Context,
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    mimeType: String,
    finalFileName: String,
    subPath: String,
    probe: SaveSizeProbe?,
    onProgress: suspend (Long, Long) -> Unit
) {
    val requestBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
            requestBuilder.header(name, value)
        }
    }
    val request = requestBuilder.build()
    val isVideo = mimeType.startsWith("video")

    if (isVideo && trySaveVideoWithParallelRange(
            context = context,
            client = client,
            request = request,
            finalFileName = finalFileName,
            mimeType = mimeType,
            subPath = subPath,
            probe = probe,
            onProgress = onProgress
        )
    ) {
        return
    }

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("download failed: ${response.code}")
        }

        val body = response.body ?: throw IOException("response body is empty")
        val contentLength = body.contentLength()
        if (contentLength > 0) {
            onProgress(0L, contentLength)
        }

        body.byteStream().use { inputStream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloadsMediaStore(
                    context = context,
                    inputStream = inputStream,
                    fileName = finalFileName,
                    mimeType = mimeType,
                    subPath = subPath,
                    totalLength = contentLength,
                    onProgress = onProgress
                )
            } else {
                saveToDownloadsDirectory(
                    inputStream = inputStream,
                    fileName = finalFileName,
                    subPath = subPath,
                    totalLength = contentLength,
                    onProgress = onProgress
                )
            }
        }
    }
}

@SuppressLint("NewApi")
private suspend fun saveToDownloadsMediaStore(
    context: Context,
    inputStream: InputStream,
    fileName: String,
    mimeType: String,
    subPath: String,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    val resolver = context.contentResolver
    var lastError: Exception? = null

    for (attempt in 0 until MAX_MEDIASTORE_NAME_ATTEMPTS) {
        val candidateFileName = if (attempt == 0) fileName else buildUniqueFileName(fileName, attempt)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidateFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Cannot create MediaStore record")
        } catch (error: Exception) {
            lastError = error
            if (isDuplicateMediaStoreError(error) && attempt < MAX_MEDIASTORE_NAME_ATTEMPTS - 1) {
                continue
            }
            throw error
        }

        try {
            val rawOutputStream = resolver.openOutputStream(uri)
                ?: throw IOException("Cannot open output stream")
            rawOutputStream.use { stream ->
                BufferedOutputStream(stream, STREAM_BUFFER_SIZE).use { outputStream ->
                    copyStreamWithProgress(inputStream, outputStream, totalLength, onProgress)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    throw lastError ?: IOException("Cannot create MediaStore record")
}

private suspend fun saveToDownloadsDirectory(
    inputStream: InputStream,
    fileName: String,
    subPath: String,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val appDir = File(downloadsDir, subPath)
    if (!appDir.exists() && !appDir.mkdirs()) {
        throw IOException("Cannot create save directory")
    }

    val targetFile = createUniqueFile(appDir, fileName)
    FileOutputStream(targetFile).use { stream ->
        BufferedOutputStream(stream, STREAM_BUFFER_SIZE).use { outputStream ->
            copyStreamWithProgress(inputStream, outputStream, totalLength, onProgress)
        }
    }
}

private suspend fun trySaveVideoWithParallelRange(
    context: Context,
    client: OkHttpClient,
    request: Request,
    finalFileName: String,
    mimeType: String,
    subPath: String,
    probe: SaveSizeProbe?,
    onProgress: suspend (Long, Long) -> Unit
): Boolean {
    // 复用保存前探测结果；若探测明确不支持 Range 或文件过小，直接走单流
    val resolvedProbe = probe?.let { p ->
        if (!p.rangeSupported || p.totalBytes < MIN_PARALLEL_VIDEO_BYTES) {
            return false
        }
        RangeProbe(
            totalLength = p.totalBytes,
            segmentCount = chooseRangeSegmentCount(p.totalBytes)
        )
    } ?: run {
        try {
            probeVideoRangeSupport(client, request)
        } catch (error: IOException) {
            Log.d("SaveError", "range probe failed, fallback to single stream", error)
            null
        } ?: return false
    }

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveVideoRangesToDownloadsMediaStore(
                context = context,
                client = client,
                request = request,
                fileName = finalFileName,
                mimeType = mimeType,
                subPath = subPath,
                probe = resolvedProbe,
                onProgress = onProgress
            )
        } else {
            saveVideoRangesToDownloadsDirectory(
                client = client,
                request = request,
                fileName = finalFileName,
                subPath = subPath,
                probe = resolvedProbe,
                onProgress = onProgress
            )
        }
        true
    } catch (error: UnsupportedRangeDownloadException) {
        Log.d("SaveError", "range download unsupported, fallback to single stream", error)
        false
    }
}

@SuppressLint("NewApi")
private suspend fun saveVideoRangesToDownloadsMediaStore(
    context: Context,
    client: OkHttpClient,
    request: Request,
    fileName: String,
    mimeType: String,
    subPath: String,
    probe: RangeProbe,
    onProgress: suspend (Long, Long) -> Unit
) {
    val resolver = context.contentResolver
    var lastError: Exception? = null

    for (attempt in 0 until MAX_MEDIASTORE_NAME_ATTEMPTS) {
        val candidateFileName = if (attempt == 0) fileName else buildUniqueFileName(fileName, attempt)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidateFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Cannot create MediaStore record")
        } catch (error: Exception) {
            lastError = error
            if (isDuplicateMediaStoreError(error) && attempt < MAX_MEDIASTORE_NAME_ATTEMPTS - 1) {
                continue
            }
            throw error
        }

        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Cannot open output file descriptor")
            descriptor.use {
                FileOutputStream(it.fileDescriptor).channel.use { channel ->
                    onProgress(0L, probe.totalLength)
                    downloadFileInRanges(
                        client = client,
                        request = request,
                        totalLength = probe.totalLength,
                        segmentCount = probe.segmentCount,
                        writer = RangeChunkWriter { position, buffer, byteCount ->
                            writeFully(channel, buffer, byteCount, position)
                        },
                        onProgress = onProgress
                    )
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    throw lastError ?: IOException("Cannot create MediaStore record")
}

private suspend fun saveVideoRangesToDownloadsDirectory(
    client: OkHttpClient,
    request: Request,
    fileName: String,
    subPath: String,
    probe: RangeProbe,
    onProgress: suspend (Long, Long) -> Unit
) {
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val appDir = File(downloadsDir, subPath)
    if (!appDir.exists() && !appDir.mkdirs()) {
        throw IOException("Cannot create save directory")
    }

    val targetFile = createUniqueFile(appDir, fileName)
    try {
        RandomAccessFile(targetFile, "rw").use { outputFile ->
            outputFile.setLength(probe.totalLength)
            onProgress(0L, probe.totalLength)
            downloadFileInRanges(
                client = client,
                request = request,
                totalLength = probe.totalLength,
                segmentCount = probe.segmentCount,
                writer = RangeChunkWriter { position, buffer, byteCount ->
                    synchronized(outputFile) {
                        outputFile.seek(position)
                        outputFile.write(buffer, 0, byteCount)
                    }
                },
                onProgress = onProgress
            )
        }
    } catch (error: Exception) {
        if (targetFile.exists() && !targetFile.delete()) {
            Log.w("SaveError", "failed to delete partial download file: ${targetFile.absolutePath}")
        }
        throw error
    }
}

private fun probeVideoRangeSupport(
    client: OkHttpClient,
    request: Request
): RangeProbe? {
    val probeRequest = request.newBuilder()
        .header("Range", "bytes=0-0")
        .header("Accept-Encoding", "identity")
        .build()

    client.newCall(probeRequest).execute().use { response ->
        if (response.code != 206) {
            return null
        }

        val totalLength = parseContentRangeTotal(response.header("Content-Range"))
            ?: return null
        if (totalLength < MIN_PARALLEL_VIDEO_BYTES) {
            return null
        }

        return RangeProbe(
            totalLength = totalLength,
            segmentCount = chooseRangeSegmentCount(totalLength)
        )
    }
}

private fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) {
        return null
    }

    return Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)
        .find(contentRange)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}

private fun chooseRangeSegmentCount(totalLength: Long): Int {
    // 动态分段：小文件少段、大文件多段（上限 MAX_PARALLEL_VIDEO_RANGES）
    val targetCount = ((totalLength + RANGE_SEGMENT_TARGET_BYTES - 1) / RANGE_SEGMENT_TARGET_BYTES)
        .toInt()
        .coerceAtLeast(2)
    // 超大文件（>1GB）再多拉一段提升速度
    val boosted = if (totalLength > 1024L * 1024L * 1024L) targetCount + 2 else targetCount
    return boosted.coerceAtMost(MAX_PARALLEL_VIDEO_RANGES)
}

private fun buildByteRanges(totalLength: Long, segmentCount: Int): List<ByteRange> {
    val segmentSize = totalLength / segmentCount
    var start = 0L
    return (0 until segmentCount).map { index ->
        val end = if (index == segmentCount - 1) {
            totalLength - 1
        } else {
            start + segmentSize - 1
        }
        ByteRange(start, end).also {
            start = end + 1
        }
    }
}

private suspend fun downloadFileInRanges(
    client: OkHttpClient,
    request: Request,
    totalLength: Long,
    segmentCount: Int,
    writer: RangeChunkWriter,
    onProgress: suspend (Long, Long) -> Unit
) = coroutineScope {
    val progress = AtomicLong(0L)
    buildByteRanges(totalLength, segmentCount)
        .map { range ->
            async(Dispatchers.IO) {
                downloadRangeSegment(
                    client = client,
                    request = request,
                    range = range,
                    writer = writer,
                    progress = progress,
                    totalLength = totalLength,
                    onProgress = onProgress
                )
            }
        }
        .awaitAll()

    val downloaded = progress.get()
    if (downloaded != totalLength) {
        throw IOException("range download incomplete: $downloaded/$totalLength")
    }
    onProgress(totalLength, totalLength)
}

private suspend fun downloadRangeSegment(
    client: OkHttpClient,
    request: Request,
    range: ByteRange,
    writer: RangeChunkWriter,
    progress: AtomicLong,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    var nextStart = range.start
    var failedAttempts = 0

    while (nextStart <= range.end) {
        val slice = ByteRange(nextStart, range.end)
        try {
            val written = downloadRangeSlice(
                client = client,
                request = request,
                range = slice,
                writer = writer,
                progress = progress,
                totalLength = totalLength,
                onProgress = onProgress
            )
            if (written <= 0L) {
                throw IOException("range ${slice.start}-${slice.end} made no progress")
            }
            nextStart += written
            failedAttempts = 0
        } catch (error: UnsupportedRangeDownloadException) {
            throw error
        } catch (error: PartialRangeDownloadException) {
            nextStart += error.bytesWritten
            if (nextStart > range.end) {
                return
            }
            failedAttempts++
            if (failedAttempts >= MAX_RANGE_SEGMENT_ATTEMPTS) {
                throw error
            }
            delay(SAVE_RETRY_DELAY_MS * failedAttempts)
        } catch (error: IOException) {
            failedAttempts++
            if (failedAttempts >= MAX_RANGE_SEGMENT_ATTEMPTS) {
                throw error
            }
            delay(SAVE_RETRY_DELAY_MS * failedAttempts)
        }
    }
}

private suspend fun downloadRangeSlice(
    client: OkHttpClient,
    request: Request,
    range: ByteRange,
    writer: RangeChunkWriter,
    progress: AtomicLong,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
): Long {
    val rangeRequest = request.newBuilder()
        .header("Range", "bytes=${range.start}-${range.end}")
        .header("Accept-Encoding", "identity")
        .build()

    client.newCall(rangeRequest).execute().use { response ->
        if (response.code != 206) {
            throw UnsupportedRangeDownloadException("range response code ${response.code}")
        }

        val body = response.body ?: throw IOException("range response body empty")
        body.byteStream().use { inputStream ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var bytesRead: Int
            var written = 0L
            var lastUpdateMillis = 0L

            try {
                while (written < range.length) {
                    val maxRead = minOf(buffer.size.toLong(), range.length - written).toInt()
                    if (inputStream.read(buffer, 0, maxRead).also { bytesRead = it } == -1) {
                        break
                    }
                    writer.write(range.start + written, buffer, bytesRead)
                    written += bytesRead
                    val downloaded = progress.addAndGet(bytesRead.toLong())

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMillis >= PROGRESS_UPDATE_INTERVAL_MS) {
                        onProgress(downloaded.coerceAtMost(totalLength), totalLength)
                        lastUpdateMillis = now
                    }
                }
            } catch (error: UnsupportedRangeDownloadException) {
                throw error
            } catch (error: IOException) {
                if (written > 0L) {
                    throw PartialRangeDownloadException(written, error)
                }
                throw error
            }

            return written
        }
    }
}

private fun writeFully(
    channel: FileChannel,
    buffer: ByteArray,
    byteCount: Int,
    position: Long
) {
    var offset = 0
    var writePosition = position
    try {
        while (offset < byteCount) {
            val byteBuffer = ByteBuffer.wrap(buffer, offset, byteCount - offset)
            val written = channel.write(byteBuffer, writePosition)
            if (written <= 0) {
                throw IOException("channel write made no progress")
            }
            offset += written
            writePosition += written
        }
    } catch (error: IOException) {
        if (isPositionalWriteUnsupported(error)) {
            throw UnsupportedRangeDownloadException("positional write unsupported")
        }
        throw error
    } catch (error: RuntimeException) {
        throw IOException("channel write failed", error)
    }
}

private fun isPositionalWriteUnsupported(error: IOException): Boolean {
    val message = error.message.orEmpty().lowercase()
    return message.contains("illegal seek") ||
        message.contains("espipe") ||
        message.contains("not seekable")
}

private fun shouldRetrySave(error: Exception): Boolean {
    if (error is SocketTimeoutException) {
        return true
    }

    if (error is IOException) {
        val message = error.message.orEmpty().lowercase()
        return message.contains("stream was reset") ||
            message.contains("internal_error") ||
            message.contains("timeout") ||
            message.contains("unexpected end of stream") ||
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("failed to connect")
    }

    return false
}

private fun isDuplicateMediaStoreError(error: Exception): Boolean {
    val message = error.message.orEmpty()
    return message.contains("UNIQUE constraint failed", ignoreCase = true) ||
        message.contains("code 2067", ignoreCase = true)
}

private fun buildUniqueFileName(fileName: String, suffix: Int): String {
    val suffixNumber = suffix + 1
    val lastDot = fileName.lastIndexOf('.')
    return if (lastDot > 0) {
        val baseName = fileName.substring(0, lastDot)
        val extension = fileName.substring(lastDot)
        "${baseName}_$suffixNumber$extension"
    } else {
        "${fileName}_$suffixNumber"
    }
}

private fun createUniqueFile(directory: File, fileName: String): File {
    var candidate = File(directory, fileName)
    if (!candidate.exists()) {
        return candidate
    }

    val nameWithoutExtension = candidate.nameWithoutExtension
    val extension = candidate.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
    var counter = 2
    while (candidate.exists()) {
        candidate = File(directory, "${nameWithoutExtension}_$counter$extension")
        counter++
    }
    return candidate
}

private fun ensureExtension(name: String, extension: String): String {
    return if (name.lowercase().endsWith(".$extension")) {
        name
    } else {
        "$name.$extension"
    }
}

private suspend fun copyStreamWithProgress(
    inputStream: InputStream,
    outputStream: OutputStream,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var bytesRead: Int
    var totalBytesRead = 0L
    var lastUpdateMillis = 0L

    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        outputStream.write(buffer, 0, bytesRead)
        totalBytesRead += bytesRead

        val now = System.currentTimeMillis()
        if (totalLength > 0 && now - lastUpdateMillis >= PROGRESS_UPDATE_INTERVAL_MS) {
            onProgress(totalBytesRead, totalLength)
            lastUpdateMillis = now
        }
    }

    outputStream.flush()
    onProgress(totalBytesRead, totalLength)
}
