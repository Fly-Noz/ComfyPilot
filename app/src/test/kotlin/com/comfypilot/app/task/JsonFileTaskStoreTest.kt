package com.comfypilot.app.task

import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class JsonFileTaskStoreTest {

    private val dir: File = Files.createTempDirectory("cp-tasks").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `a saved task survives a new store instance`() = runBlocking {
        val file = File(dir, "tasks.json")
        val task = PromptTask(
            id = "p1",
            promptId = "p1",
            displayName = "Qwen 图像编辑",
            workflowPath = "workflows/Qwen-image21.json",
            state = TaskState.Completed,
            createdAtMs = 10,
            updatedAtMs = 20,
            outputs = listOf(TaskOutput("9", "images", "a.png", "", "output")),
        )
        JsonFileTaskStore(file).save(task)

        val loaded = JsonFileTaskStore(file).load().single()

        assertEquals(task, loaded)
        assertEquals("workflows/Qwen-image21.json", loaded.workflowPath)
        assertEquals("a.png", loaded.outputs.single().filename)
    }

    @Test
    fun `an older snapshot cannot overwrite a newer one`() = runBlocking {
        val file = File(dir, "tasks.json")
        val store = JsonFileTaskStore(file)
        val newer = task(id = "p1", updatedAtMs = 20, state = TaskState.Completed)
        val older = task(id = "p1", updatedAtMs = 10, state = TaskState.Running)

        store.save(newer)
        store.save(older)

        assertEquals(TaskState.Completed, store.load().single().state)
        assertEquals(TaskState.Completed, JsonFileTaskStore(file).load().single().state)
    }

    @Test
    fun `remove is persisted`() = runBlocking {
        val file = File(dir, "tasks.json")
        val store = JsonFileTaskStore(file)
        store.save(task(id = "p1", updatedAtMs = 20))

        store.remove("p1")

        assertTrue(store.load().isEmpty())
        assertTrue(JsonFileTaskStore(file).load().isEmpty())
    }

    @Test
    fun `a late save cannot resurrect a removed draft`() = runBlocking {
        val file = File(dir, "tasks.json")
        val store = JsonFileTaskStore(file)
        val draft = task(id = "draft-1", updatedAtMs = 20)

        store.save(draft)
        store.remove("draft-1")
        // 模拟 Persist(draft) 的协程晚于 remove 才执行。
        store.save(draft)

        assertTrue(JsonFileTaskStore(file).load().isEmpty())
    }

    @Test
    fun `a corrupt file is treated as no history and can be replaced`() = runBlocking {
        val file = File(dir, "tasks.json")
        file.writeText("{not json")

        val store = JsonFileTaskStore(file)
        assertTrue(store.load().isEmpty())

        store.save(task(id = "p1", updatedAtMs = 20))
        assertEquals(listOf("p1"), JsonFileTaskStore(file).load().map { it.id })
    }

    @Test
    fun `only the newest tasks are kept`() = runBlocking {
        val file = File(dir, "tasks.json")
        val store = JsonFileTaskStore(file, limit = 2)
        store.save(task(id = "old", updatedAtMs = 10))
        store.save(task(id = "middle", updatedAtMs = 20))
        store.save(task(id = "new", updatedAtMs = 30))

        assertEquals(listOf("middle", "new"), JsonFileTaskStore(file).load().map { it.id })
    }

    @Test
    fun `a task without a workflow path remains readable`() = runBlocking {
        val file = File(dir, "tasks.json")
        val store = JsonFileTaskStore(file)
        store.save(task(id = "old", updatedAtMs = 10))

        assertNull(JsonFileTaskStore(file).load().single().workflowPath)
    }

    private fun task(id: String, updatedAtMs: Long, state: TaskState = TaskState.Completed) = PromptTask(
        id = id,
        promptId = id,
        displayName = null,
        workflowPath = null,
        state = state,
        createdAtMs = 1,
        updatedAtMs = updatedAtMs,
    )
}
