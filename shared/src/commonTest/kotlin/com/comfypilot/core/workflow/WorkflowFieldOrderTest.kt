package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class WorkflowFieldOrderTest {

    @Test
    fun `a widget without a value needs input`() {
        assertFalse(field(value = JsonPrimitive(1)).needsInput)
        assertTrue(field(value = null).needsInput)
        // 有连线的控件值由上游提供，不算缺值。
        assertFalse(field(value = null, linked = true).needsInput)
    }

    @Test
    fun `a required link without a connection needs input`() {
        assertTrue(field(widget = false, required = true, value = null).needsInput)
        assertFalse(field(widget = false, required = false, value = null).needsInput)
    }

    @Test
    fun `a node is flagged when any field needs input`() {
        val complete = node(field(value = JsonPrimitive(1)))
        val incomplete = node(field(value = null))
        assertFalse(complete.needsInput)
        assertTrue(incomplete.needsInput)
    }

    @Test
    fun `a bypassed node is not flagged even when a field has no value`() {
        val bypassed = node(field(value = null), reachable = false)
        assertFalse(bypassed.needsInput)
        assertTrue(node(field(value = null), reachable = true).needsInput)
    }

    private fun field(
        widget: Boolean = true,
        required: Boolean = true,
        value: JsonElement? = null,
        linked: Boolean = false,
    ): WorkflowField = WorkflowField(
        nodeId = 1,
        nodeType = "X",
        nodeTitle = null,
        inputName = "value",
        label = null,
        type = "INT",
        options = emptyList(),
        value = value,
        linked = linked,
        widget = widget,
        required = required,
        common = false,
    )

    private fun node(
        vararg fields: WorkflowField,
        reachable: Boolean = true,
    ): WorkflowNodeView = WorkflowNodeView(
        id = 1,
        type = "X",
        displayName = "X",
        title = null,
        mode = 0,
        missing = false,
        reachable = reachable,
        fields = fields.toList(),
    )
}
