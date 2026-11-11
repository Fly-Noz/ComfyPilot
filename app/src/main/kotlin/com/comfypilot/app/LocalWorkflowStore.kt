package com.comfypilot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 本地导入的工作流：原文存沙盒，索引只记展示信息。
 *
 * 路径形如 `local://<uuid>`，和服务器路径分开；草稿/备注按主机 + 路径隔离。
 * 不写回 ComfyUI 主机，也不上传到任何开发者服务器。
 */
class LocalWorkflowStore(context: Context) {
    data class Item(
        val path: String,
        val name: String,
        val importedAtMs: Long,
    )

    private val dir = File(context.applicationContext.filesDir, "local_workflows")
    private val indexFile = File(dir, "index.json")

    fun list(): List<Item> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val path = obj.optString("path")
                val name = obj.optString("name")
                if (path.isBlank() || name.isBlank()) return@mapNotNull null
                if (!fileFor(path).isFile) return@mapNotNull null
                Item(path, name, obj.optLong("importedAtMs"))
            }.sortedByDescending { it.importedAtMs }
        }.getOrDefault(emptyList())
    }

    /** 原文过大或不是 JSON 的校验交给调用方；这里只负责落盘。 */
    fun save(name: String, raw: String): Item {
        dir.mkdirs()
        val id = UUID.randomUUID().toString().replace("-", "")
        val item = Item(
            path = "local://$id",
            name = name.ifBlank { "本地工作流" },
            importedAtMs = System.currentTimeMillis(),
        )
        fileFor(item.path).writeText(raw)
        val items = list().filter { it.path != item.path } + item
        writeIndex(items)
        return item
    }

    fun read(path: String): String? {
        val file = fileFor(path)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** 删除本地导入；只删沙盒文件，不影响主机。 */
    fun delete(path: String) {
        val remaining = list().filter { it.path != path }
        fileFor(path).delete()
        writeIndex(remaining)
    }

    private fun fileFor(path: String): File {
        val id = path.substringAfterLast('/').substringAfterLast(':')
        return File(dir, "$id.json")
    }

    private fun writeIndex(items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("path", item.path)
                    .put("name", item.name)
                    .put("importedAtMs", item.importedAtMs),
            )
        }
        dir.mkdirs()
        indexFile.writeText(array.toString())
    }
}
