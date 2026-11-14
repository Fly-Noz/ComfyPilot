package com.comfypilot.app.backup

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.comfypilot.app.LocalWorkflowStore
import com.comfypilot.app.ui.ConnectionHistoryStore
import com.comfypilot.app.ui.ForceSubmitPrefs
import com.comfypilot.app.ui.PinnedWorkflowStore
import com.comfypilot.app.ui.WeakNetworkPrefs
import com.comfypilot.app.update.UpdatePrefs
import com.comfypilot.core.host.PERSIST_SCHEMA_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地备份：导出/导入 ComfyPilot 的本机记录。
 *
 * 备份是纯 JSON；schema 由这里统一维护，Android 与鸿蒙使用同一份字段名。
 * 任务快照内部仍然是 Kotlin 的 toPersistJson schema，这里不解释字段。
 */
object BackupManager {
    private const val FORMAT = "comfypilot-backup"
    private const val VERSION = 1
    private const val MAX_BACKUP_BYTES = 30L * 1024 * 1024

    sealed interface ExportResult {
        data class Saved(val displayName: String, val uri: Uri?) : ExportResult
        data class Failed(val message: String) : ExportResult
    }

    sealed interface ImportResult {
        data class Imported(val theme: String) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    suspend fun export(context: Context): ExportResult = withContext(Dispatchers.IO) {
        try {
            val json = buildBackup(context)
            val text = json.toString(2)
            if (text.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
                return@withContext ExportResult.Failed("备份超过 30 MB，请先删除部分本地工作流或任务历史")
            }
            val name = "ComfyPilot-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
            val uri = writeToDownloads(context, name, text)
            ExportResult.Saved(name, uri)
        } catch (t: Throwable) {
            ExportResult.Failed(t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "导出失败")
        }
    }

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri)
            val bytes = input?.use { it.readBytes() }
            if (bytes == null) return@withContext ImportResult.Failed("读不到备份文件")
            if (bytes.size > MAX_BACKUP_BYTES) {
                return@withContext ImportResult.Failed("备份文件过大，最多 30 MB")
            }
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.optString("format") != FORMAT) {
                return@withContext ImportResult.Failed("不是 ComfyPilot 本地备份")
            }
            if (root.optInt("version", 0) != VERSION) {
                return@withContext ImportResult.Failed("备份版本不支持")
            }
            restore(context, root)
            ImportResult.Imported(restoreTheme(root))
        } catch (t: Throwable) {
            ImportResult.Failed(t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "导入失败")
        }
    }

    private fun restoreTheme(root: JSONObject): String =
        (root.optJSONObject("settings") ?: JSONObject()).optString("theme", "system")

    private fun buildBackup(context: Context): JSONObject {
        val local = LocalWorkflowStore(context)
        val address = ConnectionHistoryStore(context)
        val tasks = readTasks(context)

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAtMs", System.currentTimeMillis())
            put("platform", "android")

            put("addresses", JSONObject().apply {
                put("last", address.last())
                put("history", JSONArray().apply {
                    address.history().forEach { url ->
                        put(JSONObject().put("url", url).put("usedAtMs", 0L))
                    }
                })
            })

            put("workflowDrafts", dumpPrefs(context, "workflow_drafts"))
            put("nodeNotes", dumpPrefs(context, "node_notes"))
            put("pinnedNodes", dumpPrefs(context, "pinned_nodes"))
            put("pinnedWorkflows", JSONArray(PinnedWorkflowStore(context).load().sorted()))

            val seed = context.getSharedPreferences("comfypilot_seed", Context.MODE_PRIVATE)
            put("seedEvery", seed.getString("every", "{}") ?: "{}")
            put("seedLast", seed.getString("last", "{}") ?: "{}")

            put("tasks", tasks)

            put("localWorkflows", JSONArray().apply {
                local.list().forEach { item ->
                    val raw = local.read(item.path) ?: return@forEach
                    put(JSONObject().apply {
                        put("path", item.path)
                        put("name", item.name)
                        put("importedAtMs", item.importedAtMs)
                        put("raw", raw)
                    })
                }
            })

            put("settings", JSONObject().apply {
                put("theme", com.comfypilot.app.ui.theme.AppearanceStore(context).load().storageValue)
                put("forceSubmit", ForceSubmitPrefs.isEnabled(context))
                put("weakNetwork", WeakNetworkPrefs.isEnabled(context))
                put("updateMode", when (UpdatePrefs.mode(context)) {
                    UpdatePrefs.Mode.ONCE_PER_VERSION -> "once"
                    UpdatePrefs.Mode.ALWAYS -> "always"
                })
                put("dismissedVersion", UpdatePrefs.dismissedVersion(context))
            })
        }
    }

    private fun restore(context: Context, root: JSONObject) {
        val settings = root.optJSONObject("settings") ?: JSONObject()

        // 先把所有 JSON 形状校验完，再开始清空，避免一半新一半旧。
        val drafts = stringMap(root.optJSONObject("workflowDrafts"))
        val notes = stringMap(root.optJSONObject("nodeNotes"))
        val pinnedNodes = stringMap(root.optJSONObject("pinnedNodes"))
        val pinnedWorkflows = root.optJSONArray("pinnedWorkflows").toStringList()
        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        val localItems = parseLocalWorkflows(root.optJSONArray("localWorkflows"))
        val addresses = root.optJSONObject("addresses") ?: JSONObject()
        val addressHistory = addresses.optJSONArray("history").toStringListFromObjects()
        val seedEvery = root.optString("seedEvery", "{}").ifBlank { "{}" }
        val seedLast = root.optString("seedLast", "{}").ifBlank { "{}" }

        replaceStringPrefs(context, "workflow_drafts", drafts)
        replaceStringPrefs(context, "node_notes", notes)
        replaceStringPrefs(context, "pinned_nodes", pinnedNodes)
        context.getSharedPreferences("comfypilot_seed", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("every", seedEvery)
            .putString("last", seedLast)
            .apply()

        PinnedWorkflowStore(context).save(pinnedWorkflows.toSet())
        ConnectionHistoryStore(context).replace(
            last = addresses.optString("last", ""),
            history = addressHistory,
        )

        LocalWorkflowStore(context).replaceAll(localItems)
        writeTasks(context, tasks)

        com.comfypilot.app.ui.theme.AppearanceStore(context).save(
            com.comfypilot.app.ui.theme.ThemeMode.fromStorage(settings.optString("theme", "system")),
        )
        ForceSubmitPrefs.setEnabled(context, settings.optBoolean("forceSubmit", false))
        WeakNetworkPrefs.setEnabled(context, settings.optBoolean("weakNetwork", false))
        UpdatePrefs.setMode(
            context,
            if (settings.optString("updateMode") == "once") {
                UpdatePrefs.Mode.ONCE_PER_VERSION
            } else {
                UpdatePrefs.Mode.ALWAYS
            },
        )
        val dismissed = settings.optInt("dismissedVersion", 0)
        if (dismissed > 0) UpdatePrefs.markDismissed(context, dismissed) else UpdatePrefs.clearDismissed(context)
    }

    private fun dumpPrefs(context: Context, name: String): JSONObject {
        val result = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (value is String) result.put(key, value)
        }
        return result
    }

    private fun replaceStringPrefs(context: Context, name: String, values: Map<String, String>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key)
            if (value is String) result[key] = value
        }
        return result
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun JSONArray?.toStringListFromObjects(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.optString("url")?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseLocalWorkflows(array: JSONArray?): List<LocalWorkflowStore.RestoreItem> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val path = obj.optString("path")
            val name = obj.optString("name")
            val raw = obj.optString("raw")
            if (path.isBlank() || name.isBlank() || raw.isBlank()) return@mapNotNull null
            LocalWorkflowStore.RestoreItem(
                path = path,
                name = name,
                importedAtMs = obj.optLong("importedAtMs"),
                raw = raw,
            )
        }
    }

    private fun readTasks(context: Context): JSONArray {
        val file = File(context.filesDir, "tasks.json")
        if (!file.isFile) return JSONArray()
        return runCatching {
            val root = JSONObject(file.readText())
            val source = root.optJSONArray("tasks") ?: JSONArray()
            JSONArray().apply {
                for (index in 0 until source.length()) {
                    val task = source.optJSONObject(index) ?: continue
                    put(task.toString())
                }
            }
        }.getOrElse {
            // 兼容早期直接把任务写成 JSONArray 的版本。
            runCatching {
                val array = JSONArray(file.readText())
                JSONArray().apply {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { put(it.toString()) }
                    }
                }
            }.getOrDefault(JSONArray())
        }
    }

    private fun writeTasks(context: Context, tasks: JSONArray) {
        val file = File(context.filesDir, "tasks.json")
        val cleaned = JSONArray()
        for (index in 0 until tasks.length()) {
            val task = when (val value = tasks.opt(index)) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            } ?: continue
            if (task.optString("id").isBlank()) continue
            cleaned.put(task)
        }
        val payload = JSONObject().apply {
            put("v", PERSIST_SCHEMA_VERSION)
            put("tasks", cleaned)
        }.toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.writeText(payload)
            tmp.delete()
        }
    }

    private fun writeToDownloads(context: Context, name: String, text: String): Uri? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建 Download 文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("无法写入 Download 文件")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/json"), null)
        return Uri.fromFile(file)
    }
}
