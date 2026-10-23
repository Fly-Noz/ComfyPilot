package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowNodeTraitsTest {

    private fun field(type: String, value: JsonPrimitive? = JsonPrimitive("x")) = WorkflowField(
        nodeId = 1,
        nodeType = "Demo",
        nodeTitle = null,
        inputName = "in",
        label = null,
        type = type,
        options = emptyList(),
        value = value,
        linked = false,
        widget = true,
        required = false,
        common = false,
    )

    private fun node(type: String = "Demo", fields: List<WorkflowField>) = WorkflowNodeView(
        id = 1,
        type = type,
        displayName = "Demo Node",
        title = null,
        mode = 0,
        missing = false,
        reachable = true,
        fields = fields,
    )

    @Test
    fun `image inputs and load image nodes are image traits`() {
        assertTrue(NodeTrait.Image in WorkflowNodeTraits.of(node(fields = listOf(field("IMAGE")))))
        assertTrue(NodeTrait.Image in WorkflowNodeTraits.of(node("LoadImage", emptyList())))
    }

    @Test
    fun `string and numeric inputs get separate traits`() {
        val traits = WorkflowNodeTraits.of(node(fields = listOf(field("STRING"), field("INT"))))

        assertTrue(NodeTrait.Text in traits)
        assertTrue(NodeTrait.Number in traits)
        assertTrue(NodeTrait.Image !in traits)
    }

    @Test
    fun `unfilled widget marks needs input`() {
        val traits = WorkflowNodeTraits.of(node(fields = listOf(field("STRING", value = null))))

        assertTrue(NodeTrait.NeedsInput in traits)
        assertEquals(setOf(NodeTrait.Text, NodeTrait.NeedsInput), traits)
    }

    @Test
    fun `search matches name type id and note`() {
        val target = node(fields = listOf(field("STRING")))

        assertTrue(WorkflowNodeTraits.matches(target, "demo"))
        assertTrue(WorkflowNodeTraits.matches(target, "#1"))
        assertTrue(WorkflowNodeTraits.matches(target, "str"))
        assertTrue(WorkflowNodeTraits.matches(target, "首图", note = "首图"))
        assertTrue(!WorkflowNodeTraits.matches(target, "none"))
        assertTrue(WorkflowNodeTraits.matches(target, "  "))
    }
}