package com.comfypilot.core.host

import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskProgress
import com.comfypilot.core.task.TaskState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 持久化快照的 schema。宿主只搬字节，所以"写出去的能原样读回来"是这里唯一的硬要求；
 * 读不回来宁可丢一条，也不能猜。
 */
class PersistedTaskTest {

    private val sample = PromptTask(
        id = "p1",
        promptId = "p1",
        displayName = "水彩",
        state = TaskState.Failed("CUDA OOM"),
        createdAtMs = 10,
        updatedAtMs = 20,
        progress = TaskProgress(3.0, 10.0, "3"),
        outputs = listOf(TaskOutput("9", "images", "a.png", "sub", "output")),
        lastProgressAtMs = 15,
        error = "CUDA OOM",
        workflowPath = "workflows/qwen.json",
    )

    @Test
    fun `snapshot round trips the fields the ui needs`() {
        val back = parsePersistedTask(sample.toPersistJson())

        assertEquals(sample, back)
        assertEquals("水彩", back?.displayName)
        assertEquals("workflows/qwen.json", back?.workflowPath)
        assertEquals("a.png", back?.outputs?.single()?.filename)
    }

    @Test
    fun `old snapshots without a workflow path still load`() {
        val old = JsonObject(
            mapOf(
                "id" to JsonPrimitive("old"),
                "state" to JsonPrimitive("Completed"),
                "createdAtMs" to JsonPrimitive(1),
            ),
        )

        val back = assertNotNull(parsePersistedTask(old))

        assertNull(back.workflowPath)
    }

    @Test
    fun `progress keeps decimals`() {
        val task = sample.copy(state = TaskState.Running, progress = TaskProgress(0.5, 1.0, null), outputs = emptyList())

        val back = assertNotNull(parsePersistedTask(task.toPersistJson()))

        assertEquals(50, back.progress?.percent)
    }

    @Test
    fun `unknown reasons survive`() {
        val task = sample.copy(state = TaskState.Unknown("对账超时"), error = null)

        val back = assertNotNull(parsePersistedTask(task.toPersistJson()))

        assertEquals("对账超时", assertIs<TaskState.Unknown>(back.state).reason)
    }

    @Test
    fun `unreadable records are dropped instead of guessed`() {
        assertNull(parsePersistedTask(JsonObject(mapOf("id" to JsonPrimitive("p1"), "state" to JsonPrimitive("Weird")))))
        assertNull(parsePersistedTask(JsonObject(mapOf("state" to JsonPrimitive("Running")))))
        assertNull(parsePersistedTask(JsonObject(emptyMap())))
    }

    @Test
    fun `one bad record does not take the good ones down`() {
        val array = Json.parseToJsonElement(
            """[{"id":"ok","state":"Running","createdAtMs":1},{"id":"bad","state":"??"},42,{"state":"Running"}]""",
        ).jsonArray

        assertEquals(listOf("ok"), parsePersistedTasks(array).map { it.id })
    }

    @Test
    fun `tasks shipped with the config are restored`() {
        val raw = """{"baseUrl":"http://127.0.0.1:8188","tasks":[${sample.toPersistJson()}]}"""

        val restored = restoredTasksFromJson(raw)

        assertEquals(listOf("p1"), restored.map { it.id })
        assertEquals(sample, restored.single())
    }

    @Test
    fun `broken config json restores nothing`() {
        assertTrue(restoredTasksFromJson("not json").isEmpty())
        assertTrue(restoredTasksFromJson("""{"tasks":"nope"}""").isEmpty())
        assertTrue(restoredTasksFromJson("""{"tasks":[1,2,3]}""").isEmpty())
    }
}
