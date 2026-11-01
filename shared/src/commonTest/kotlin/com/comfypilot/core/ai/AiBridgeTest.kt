package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiBridgeTest {

    private val request = """
        {
          "path": "demo.json",
          "nodes": [
            {
              "id": 195, "class": "SamplerCustomAdvanced", "title": "采样器",
              "enabled": true, "reachable": true,
              "fields": [
                {"name": "steps", "type": "INT", "value": 4, "editable": true},
                {"name": "linked", "type": "INT", "value": 2, "editable": false, "fromNode": 7, "fromSlot": 0}
              ]
            }
          ],
          "graphs": [{"id": "g1", "title": "图生视频", "selected": true}],
          "state": {
            "overrides": {"195": {"steps": 6}},
            "bypassed": [189],
            "enabled": [],
            "graphs": ["g1"]
          }
        }
    """.trimIndent()

    @Test
    fun `digest includes overrides and marks linked fields as not editable`() {
        val response = Json.parseToJsonElement(AiBridge.digest(request)).jsonObject
        assertEquals(true, response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(response["tokens"]!!.jsonPrimitive.content.toInt() > 0)
        val digest = response["digest"]!!.jsonObject
        val node = digest["nodes"]!!.jsonArray.first().jsonObject
        val fields = node["fields"]!!.jsonArray
        assertEquals(2, fields.size)
        val editable = fields.first { it.jsonObject["editable"]!!.jsonPrimitive.content == "true" }
        assertEquals("6", editable.jsonObject["value"]!!.jsonPrimitive.content)
        // 连线驱动的字段带 fromNode，模型据此去上游改，不会直接 set_input。
        val linked = fields.first { it.jsonObject["editable"]!!.jsonPrimitive.content == "false" }
        assertTrue(linked.jsonObject["fromNode"] != null)
    }

    @Test
    fun `apply returns normalized state and changes`() {
        val proposal = """
            {"summary":"改步数","ops":[{"op":"set_input","node":195,"input":"steps","value":8}]}
        """.trimIndent()
        val body = request.trimEnd().removeSuffix("}") + ",\"proposal\":" + proposal + "}"
        val response = Json.parseToJsonElement(AiBridge.apply(body)).jsonObject
        assertEquals(true, response["ok"]!!.jsonPrimitive.content.toBoolean())
        val state = response["state"]!!.jsonObject
        val steps = state["overrides"]!!.jsonObject["195"]!!.jsonObject["steps"]!!.jsonPrimitive.content
        assertEquals("8", steps)
        assertTrue(response["changes"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `apply reports invalid ops without partial state`() {
        val proposal = """
            {"summary":"坏值","ops":[{"op":"set_input","node":195,"input":"steps","value":"abc"}]}
        """.trimIndent()
        val body = request.trimEnd().removeSuffix("}") + ",\"proposal\":" + proposal + "}"
        val response = Json.parseToJsonElement(AiBridge.apply(body)).jsonObject
        assertEquals(false, response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(response["errors"]!!.jsonArray.isNotEmpty())
    }
}
