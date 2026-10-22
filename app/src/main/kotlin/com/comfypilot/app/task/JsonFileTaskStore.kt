package com.comfypilot.app.task

import com.comfypilot.core.host.PERSIST_SCHEMA_VERSION
import com.comfypilot.core.host.parsePersistedTasks
import com.comfypilot.core.host.toPersistJson
import com.comfypilot.core.task.DEFAULT_RESTORE_LIMIT
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskStore
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Android 本地任务快照。
 *
 * schema 仍由 Kotlin 的 [toPersistJson] 独占；这里只做两件事：把整个 JSON 原子写进
 * `filesDir/tasks.json`，以及启动时按同一 schema 读回来。文件坏了就当没有历史，
 * 不能因为一条坏记录让会话起不来。
 */
class JsonFileTaskStore(
    private val file: File,
    private val limit: Int = DEFAULT_RESTORE_LIMIT,
) : TaskStore {

    private val mutex = Mutex()
    private val tasks = LinkedHashMap<String, PromptTask>()
    /** 已删除的 id：异步派发下，旧的 Persist(draft) 可能晚于 remove 到达。 */
    private val removed = mutableSetOf<String>()
    private var loaded = false

    override suspend fun load(): List<PromptTask> = mutex.withLock {
        ensureLoadedLocked()
        tasks.values.sortedBy { it.createdAtMs }
    }

    override suspend fun save(task: PromptTask) {
        mutex.withLock {
            ensureLoadedLocked()
            if (task.id in removed) return@withLock
            val old = tasks[task.id]
            // 持久化是异步派发的；旧快照不能覆盖新状态。
            if (old != null && old.updatedAtMs > task.updatedAtMs) return@withLock
            tasks[task.id] = task
            trimLocked()
            writeLocked()
        }
    }

    override suspend fun remove(id: String) {
        mutex.withLock {
            ensureLoadedLocked()
            removed += id
            if (tasks.remove(id) != null) writeLocked()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tasks"] as? JsonArray
            else -> null
        } ?: return
        parsePersistedTasks(array, limit).forEach { tasks[it.id] = it }
    }

    private fun trimLocked() {
        if (tasks.size <= limit) return
        tasks.entries
            .sortedByDescending { it.value.updatedAtMs }
            .drop(limit)
            .forEach { tasks.remove(it.key) }
    }

    private suspend fun writeLocked() {
        val payload = buildJsonObject {
            put("v", PERSIST_SCHEMA_VERSION)
            putJsonArray("tasks") {
                tasks.values.sortedBy { it.createdAtMs }.forEach { add(it.toPersistJson()) }
            }
        }.toString()
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                // 先落盘再替换，避免进程在写一半时被杀留下半个 JSON。
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // 某些文件系统不支持覆盖式 rename；退化处理后仍保留原子写的第一层保护。
                file.writeText(payload)
                tmp.delete()
            }
        }
    }
}
