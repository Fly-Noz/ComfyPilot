package com.comfypilot.app.artifact

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ArtifactNames
import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.api.ViewRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

sealed interface ArtifactDownloadResult {
    data class Saved(val displayName: String, val uri: Uri? = null) : ArtifactDownloadResult
    data class Failed(val message: String) : ArtifactDownloadResult
}

/**
 * 把 `/view` 的产物流式写入系统下载目录。
 *
 * Android 10+ 使用 MediaStore；Android 9 及以下写入公共 Downloads 目录。
 * 逐块复制，避免把视频等大产物整体放在内存里。
 */
class ArtifactDownloader(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun download(
        context: Context,
        baseUrl: String,
        output: TaskOutput,
    ): ArtifactDownloadResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext ArtifactDownloadResult.Failed("未连接 ComfyUI")

        val appContext = context.applicationContext
        val filename = ArtifactNames.sanitize(output.filename)
        val mimeType = ArtifactNames.mimeType(filename)
        val url = ApiPaths.viewUrl(
            baseUrl,
            ViewRequest(output.filename, output.subfolder, output.type),
        )

        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ArtifactDownloadResult.Failed("HTTP ${response.code}")
                }
                val body = response.body
                body.byteStream().use { input ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToMediaStore(appContext, filename, mimeType, input)
                    } else {
                        saveToLegacyDownloads(appContext, filename, mimeType, input)
                    }
                }
            }
        } catch (t: Throwable) {
            ArtifactDownloadResult.Failed(
                t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "下载失败",
            )
        }
    }

    private fun saveToMediaStore(
        context: Context,
        originalName: String,
        mimeType: String,
        input: InputStream,
    ): ArtifactDownloadResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val displayName = ArtifactNames.uniqueName(originalName) { candidate ->
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(candidate),
                null,
            )?.use { it.moveToFirst() } == true
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: return ArtifactDownloadResult.Failed("无法创建下载文件")

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                copy(input, output)
            } ?: error("无法打开下载文件")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            ArtifactDownloadResult.Saved(displayName, uri)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            ArtifactDownloadResult.Failed(
                t.message?.takeIf { it.isNotBlank() } ?: "写入下载文件失败",
            )
        }
    }

    private fun saveToLegacyDownloads(
        context: Context,
        originalName: String,
        mimeType: String,
        input: InputStream,
    ): ArtifactDownloadResult {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.exists() && !directory.mkdirs()) {
            return ArtifactDownloadResult.Failed("无法创建下载目录")
        }
        val displayName = ArtifactNames.uniqueName(originalName) { File(directory, it).exists() }
        val target = File(directory, displayName)
        return try {
            FileOutputStream(target).use { output -> copy(input, output) }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            ArtifactDownloadResult.Saved(displayName)
        } catch (t: Throwable) {
            target.delete()
            ArtifactDownloadResult.Failed(
                t.message?.takeIf { it.isNotBlank() } ?: "写入下载文件失败",
            )
        }
    }

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}
