package com.comfypilot.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.comfypilot.core.workflow.UiGraph
import com.comfypilot.core.workflow.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点图画布的几何计算：外接矩形、初始聚焦范围、小地图投影。
 * 这三块出错的表现分别是"图偏到一边""整张图缩成一小团""点小地图跳错位置"，肉眼很难定位。
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
    fun `focus bounds ignore far away outlier nodes`() {
        // 主图 6 个节点挤在左上角，另有 2 个被拖到很远的注释节点
        val nodes = listOf(
            node(1, 0.0, 0.0), node(2, 250.0, 0.0), node(3, 500.0, 0.0),
            node(4, 0.0, 200.0), node(5, 250.0, 200.0), node(6, 500.0, 200.0),
            node(7, 9000.0, -6000.0), node(8, -8000.0, 7000.0),
        )
        val focus = graphFocusBounds(graph(nodes))!!
        val full = graphBounds(graph(nodes))!!
        assertTrue("聚焦范围应明显小于全图", focus.width < full.width / 2f)
        assertTrue("聚焦范围应包含主图的节点", focus.right >= 700f)
        assertTrue("聚焦范围不该包含远处的注释节点", focus.right < 2000f)
    }

    @Test
    fun `focus bounds fall back to full bounds for tiny graphs`() {
        val g = graph(listOf(node(1, 0.0, 0.0), node(2, 3000.0, 0.0)))
        assertEquals(graphBounds(g), graphFocusBounds(g))
    }

    @Test
    fun `map projection keeps the graph inside the thumbnail`() {
        val bounds = Rect(0f, 0f, 2000f, 1000f)
        val canvas = Size(300f, 360f)
        val p = mapProjection(bounds, canvas)
        assertTrue(p.s > 0f)
        assertTrue(bounds.width * p.s <= canvas.width + 0.01f)
        assertTrue(bounds.height * p.s <= canvas.height + 0.01f)
        assertTrue(p.ox >= 0f && p.oy >= 0f)
    }

    @Test
    fun `map projection maps a world point back to the thumbnail`() {
        val bounds = Rect(100f, 50f, 1100f, 550f)
        val canvas = Size(200f, 200f)
        val p = mapProjection(bounds, canvas)
        val rightBottomX = p.ox + bounds.width * p.s
        val rightBottomY = p.oy + bounds.height * p.s
        assertTrue(rightBottomX <= canvas.width + 0.01f)
        assertTrue(rightBottomY <= canvas.height + 0.01f)
        // 反算：缩略图坐标 → 世界坐标应回到原范围
        val worldX = bounds.left + (rightBottomX - p.ox) / p.s
        assertEquals(bounds.right, worldX, 0.5f)
    }
}
