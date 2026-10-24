package com.comfypilot.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.comfypilot.core.workflow.UiGraph
import com.comfypilot.core.workflow.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点图画布的几何计算：外接矩形与小地图命中区域。
 * 这两块出错的表现分别是"适配全图后图偏到一边"和"点小地图跳错位置"，都很难靠肉眼定位。
 */
class NodeGraphGeometryTest {

    private fun node(id: Int, x: Double, y: Double, w: Double = 200.0, h: Double = 100.0) = UiNode(
        id = id,
        type = "KSampler",
        title = null,
        mode = 0,
        order = id,
        inputs = emptyList(),
        outputs = emptyList(),
        widgets = null,
        widgetsNamed = emptyMap(),
        x = x,
        y = y,
        width = w,
        height = h,
    )

    private fun graph(nodes: List<UiNode>) = UiGraph(
        nodes = nodes,
        links = emptyMap(),
        hasSubgraphs = false,
        groups = emptyList(),
    )

    @Test
    fun `bounds cover every node`() {
        val bounds = graphBounds(
            graph(
                listOf(
                    node(1, x = 0.0, y = 0.0),
                    node(2, x = 500.0, y = 300.0),
                ),
            ),
        )
        assertEquals(Rect(0f, 0f, 700f, 400f), bounds)
    }

    @Test
    fun `bounds are null without nodes`() {
        assertNull(graphBounds(null))
        assertNull(graphBounds(graph(emptyList())))
    }

    @Test
    fun `minimap sits inside the viewport with margins`() {
        val bounds = Rect(0f, 0f, 2000f, 1000f)
        val viewport = IntSize(1080, 1800)
        val rect = minimapRect(viewport, bounds, density = 3f)!!
        assertTrue(rect.left > viewport.width / 2f)
        assertTrue(rect.top > viewport.height / 2f)
        assertTrue(rect.right <= viewport.width)
        assertTrue(rect.bottom <= viewport.height)
        assertEquals(12f * 3f, viewport.width - rect.right, 0.01f)
    }

    @Test
    fun `minimap keeps a sane aspect for tall graphs`() {
        val tall = minimapRect(IntSize(1080, 2400), Rect(0f, 0f, 400f, 4000f), 3f)!!
        val wide = minimapRect(IntSize(1080, 2400), Rect(0f, 0f, 4000f, 400f), 3f)!!
        assertTrue(tall.height <= 150f * 3f + 0.01f)
        assertTrue(wide.height > 0f)
    }

    @Test
    fun `minimap is unavailable without geometry or viewport`() {
        assertNull(minimapRect(IntSize.Zero, Rect(0f, 0f, 100f, 100f), 3f))
        assertNull(minimapRect(IntSize(1080, 1800), null, 3f))
    }
}
