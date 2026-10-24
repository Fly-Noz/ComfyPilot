package com.comfypilot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumBackdrop
import com.comfypilot.app.ui.theme.SpectrumColors
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.core.workflow.UiGraph
import com.comfypilot.core.workflow.UiNode
import com.comfypilot.core.workflow.UiWorkflowParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 节点图预览：把工作流里的节点位置、分组与连线在手机上重绘一遍。
 *
 * 只读：不支持拖节点或改连线。手机屏幕精确命中 socket 不可行，
 * 交互收敛为「捏合缩放 → 单指平移 → 搜索定位 → 点节点看信息」，双击回到适配全图。
 * 右下角小地图可以直接点，把视野带过去。
 */
@Composable
fun NodeGraphDialog(
    raw: String,
    onDismiss: () -> Unit,
) {
    val c = Spectrum.colors
    val density = LocalDensity.current.density
    val graph = remember(raw) { UiWorkflowParser.parse(raw) }
    val measurer = rememberTextMeasurer()
    // 文本布局只跟节点宽度、字号有关，与缩放无关；跨帧缓存，避免每帧重排上百段文字。
    val labels = remember(graph) { mutableMapOf<Int, TextLayoutResult>() }

    var scale by remember(graph) { mutableStateOf(1f) }
    var offset by remember(graph) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(graph) { mutableStateOf<Int?>(null) }
    var query by remember(graph) { mutableStateOf("") }
    var fitted by remember(graph) { mutableStateOf(false) }

    val bounds = remember(graph) { graphBounds(graph) }
    val nodes = graph?.nodes.orEmpty()
    val groups = graph?.groups.orEmpty()
    val linkCount = graph?.links?.size ?: 0

    fun clamp(nextOffset: Offset, nextScale: Float): Offset {
        val b = bounds ?: return nextOffset
        if (viewport.width == 0 || viewport.height == 0) return nextOffset
        val left = b.left * nextScale + nextOffset.x
        val top = b.top * nextScale + nextOffset.y
        val right = b.right * nextScale + nextOffset.x
        val bottom = b.bottom * nextScale + nextOffset.y
        // 至少留 80px 在屏幕里，避免整张图被甩出视野后找不回来。
        val keep = 80f
        val x = when {
            right < keep -> nextOffset.x + (keep - right)
            left > viewport.width - keep -> nextOffset.x - (left - (viewport.width - keep))
            else -> nextOffset.x
        }
        val y = when {
            bottom < keep -> nextOffset.y + (keep - bottom)
            top > viewport.height - keep -> nextOffset.y - (top - (viewport.height - keep))
            else -> nextOffset.y
        }
        return Offset(x, y)
    }

    fun fitToScreen() {
        val b = bounds ?: return
        if (viewport.width == 0 || viewport.height == 0) return
        val pad = 28f
        val sx = (viewport.width - pad * 2) / max(1f, b.width)
        val sy = (viewport.height - pad * 2) / max(1f, b.height)
        scale = min(sx, sy).coerceIn(0.02f, 2.2f)
        offset = Offset(
            viewport.width / 2f - (b.left + b.width / 2f) * scale,
            viewport.height / 2f - (b.top + b.height / 2f) * scale,
        )
        fitted = true
    }

    fun centerOn(node: UiNode) {
        if (viewport.width == 0) return
        val nextScale = 0.85f
        val next = Offset(
            viewport.width / 2f - (node.x + node.width / 2f).toFloat() * nextScale,
            viewport.height / 2f - (node.y + node.height / 2f).toFloat() * nextScale,
        )
        scale = nextScale
        offset = clamp(next, nextScale)
        selected = node.id
        fitted = true
    }

    val results = remember(query, nodes) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            nodes.filter { node ->
                (node.title ?: "").contains(q, ignoreCase = true) ||
                    node.type.contains(q, ignoreCase = true) ||
                    node.id.toString() == q
            }.take(12)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SpectrumBackdrop(modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("节点图", color = c.text, fontSize = 16.sp)
                        Text(
                            "${nodes.size} 个节点 · ${groups.size} 个分组 · $linkCount 条连线",
                            color = c.textDim,
                            fontSize = 9.5.sp,
                            fontFamily = SpectrumMono,
                        )
                    }
                    GhostButton(text = "适配全图", onClick = { fitToScreen() }, height = 34.dp)
                    GhostButton(text = "关闭", onClick = onDismiss, height = 34.dp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(c.card)
                        .border(1.dp, c.border, RoundedCornerShape(11.dp))
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 12.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (query.isEmpty()) {
                        Text("搜索节点名 / 类型 / 编号", color = c.textDim, fontSize = 11.sp)
                    } else {
                        Text(
                            "清除",
                            color = c.textMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { query = "" },
                        )
                    }
                }

                if (results.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .heightIn(max = 168.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.card)
                            .border(1.dp, c.border, RoundedCornerShape(12.dp)),
                    ) {
                        items(results, key = { it.id }) { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        centerOn(node)
                                        query = ""
                                    }
                                    .padding(horizontal = 11.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "#${node.id}",
                                    color = c.textDim,
                                    fontSize = 10.sp,
                                    fontFamily = SpectrumMono,
                                    modifier = Modifier.width(52.dp),
                                )
                                Text(
                                    node.title?.takeIf { it.isNotBlank() } ?: node.type,
                                    color = c.text,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.background.copy(alpha = 0.55f))
                        .border(1.dp, c.border, RoundedCornerShape(16.dp))
                        .onSizeChanged { size ->
                            viewport = size
                            if (!fitted) fitToScreen()
                        }
                        .pointerInput(graph) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(0.02f, 3f)
                                // 以捏合中心为锚点缩放，避免"越放越跑偏"
                                val focus = (centroid - offset) / scale
                                val next = centroid - focus * nextScale + pan
                                offset = clamp(next, nextScale)
                                scale = nextScale
                            }
                        }
                        .pointerInput(graph, nodes, bounds) {
                            detectTapGestures(
                                onTap = { tap ->
                                    val mapRect = minimapRect(viewport, bounds, density)
                                    if (mapRect != null && mapRect.contains(tap) && bounds != null) {
                                        // 点小地图：换算成世界坐标并居中过去（保持当前缩放）
                                        val worldX = bounds.left +
                                            ((tap.x - mapRect.left) / mapRect.width) * bounds.width
                                        val worldY = bounds.top +
                                            ((tap.y - mapRect.top) / mapRect.height) * bounds.height
                                        val next = Offset(
                                            viewport.width / 2f - worldX * scale,
                                            viewport.height / 2f - worldY * scale,
                                        )
                                        offset = clamp(next, scale)
                                        return@detectTapGestures
                                    }
                                    val world = (tap - offset) / scale
                                    val hit = nodes.firstOrNull { node ->
                                        world.x >= node.x &&
                                            world.x <= node.x + node.width &&
                                            world.y >= node.y &&
                                            world.y <= node.y + node.height
                                    }
                                    selected = hit?.id
                                },
                                onDoubleTap = { fitToScreen() },
                            )
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawScene(
                            graph = graph,
                            scale = scale,
                            offset = offset,
                            selected = selected,
                            measurer = measurer,
                            labels = labels,
                            c = c,
                        )
                        drawMinimap(
                            graph = graph,
                            bounds = bounds,
                            viewport = viewport,
                            scale = scale,
                            offset = offset,
                            density = density,
                            c = c,
                        )
                    }

                    val current = nodes.firstOrNull { it.id == selected }
                    if (current != null) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.dialog.copy(alpha = 0.94f))
                                .border(1.dp, c.borderStrong, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "#${current.id}  ${current.title?.takeIf { it.isNotBlank() } ?: current.type}",
                                    color = c.text,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${current.type} · ${modeLabel(current.mode)} · 输入 ${current.inputs.size} / 输出 ${current.outputs.size}",
                                    color = c.textDim,
                                    fontSize = 9.5.sp,
                                    fontFamily = SpectrumMono,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "关闭",
                                color = c.textMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { selected = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun modeLabel(mode: Int): String = when (mode) {
    UiWorkflowParser.MODE_BYPASS -> "已旁路"
    UiWorkflowParser.MODE_MUTED -> "已静音"
    else -> "启用"
}

/** 所有节点与分组的外接矩形；没有节点时返回 null。 */
internal fun graphBounds(graph: UiGraph?): Rect? {
    if (graph == null || graph.nodes.isEmpty()) return null
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    graph.nodes.forEach { node ->
        left = min(left, node.x.toFloat())
        top = min(top, node.y.toFloat())
        right = max(right, (node.x + node.width).toFloat())
        bottom = max(bottom, (node.y + node.height).toFloat())
    }
    graph.groups.forEach { group ->
        left = min(left, group.x.toFloat())
        top = min(top, group.y.toFloat())
        right = max(right, (group.x + group.width).toFloat())
        bottom = max(bottom, (group.y + group.height).toFloat())
    }
    return Rect(left, top, right, bottom)
}

/** 小地图在屏幕上的矩形；尺寸与 [drawMinimap] 共用，保证点击命中一致。 */
internal fun minimapRect(viewport: IntSize, bounds: Rect?, density: Float): Rect? {
    if (bounds == null || bounds.width <= 0f || bounds.height <= 0f) return null
    if (viewport.width == 0 || viewport.height == 0) return null
    val pad = 12f * density
    val mapW = 104f * density
    val mapH = min(150f * density, mapW * (bounds.height / bounds.width).coerceIn(0.5f, 1.9f))
    return Rect(
        left = viewport.width - mapW - pad,
        top = viewport.height - mapH - pad,
        right = viewport.width - pad,
        bottom = viewport.height - pad,
    )
}

/**
 * 画布：分组 → 连线 → 节点。
 * 文本用固定的屏幕字号（不随缩放无限变小），并缓存在 [labels] 里，避免每帧重新排版。
 */
private fun DrawScope.drawScene(
    graph: UiGraph?,
    scale: Float,
    offset: Offset,
    selected: Int?,
    measurer: TextMeasurer,
    labels: MutableMap<Int, TextLayoutResult>,
    c: SpectrumColors,
) {
    if (graph == null) return

    fun sx(x: Double): Float = x.toFloat() * scale + offset.x
    fun sy(y: Double): Float = y.toFloat() * scale + offset.y

    // 分组框
    graph.groups.forEach { group ->
        val topLeft = Offset(sx(group.x), sy(group.y))
        val size = Size(group.width.toFloat() * scale, group.height.toFloat() * scale)
        if (size.width < 8f || size.height < 8f) return@forEach
        val radius = CornerRadius((8f * scale).coerceIn(2f, 14f))
        drawRoundRect(
            color = c.cardStrong.copy(alpha = 0.10f),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = c.border.copy(alpha = 0.55f),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(width = 1f),
        )
        if (scale > 0.16f && group.title.isNotBlank()) {
            val key = -group.id - 1
            val label = labels.getOrPut(key) {
                measurer.measure(
                    text = group.title,
                    style = TextStyle(color = c.textDim, fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(40, size.width.roundToInt() - 8)),
                )
            }
            drawText(label, topLeft = topLeft + Offset(5f, 3f))
        }
    }

    // 连线：源节点右中 → 目标节点左中，三次贝塞尔
    val byId = graph.byId
    graph.links.values.forEach { link ->
        val from = byId[link.originId] ?: return@forEach
        val to = byId[link.targetId] ?: return@forEach
        val start = Offset(sx(from.x + from.width), sy(from.y + from.height / 2))
        val end = Offset(sx(to.x), sy(to.y + to.height / 2))
        val dx = max(20f, abs(end.x - start.x) / 2f)
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y)
        }
        drawPath(
            path = path,
            color = if (from.mode == UiWorkflowParser.MODE_NORMAL && to.mode == UiWorkflowParser.MODE_NORMAL) {
                c.accent.copy(alpha = 0.40f)
            } else {
                c.textDim.copy(alpha = 0.20f)
            },
            style = Stroke(width = 1.4f * scale.coerceIn(0.6f, 1.8f)),
        )
    }

    // 节点
    graph.nodes.forEach { node ->
        val topLeft = Offset(sx(node.x), sy(node.y))
        val size = Size(node.width.toFloat() * scale, node.height.toFloat() * scale)
        if (size.width < 1.5f || size.height < 1.5f) return@forEach
        val active = node.mode == UiWorkflowParser.MODE_NORMAL
        val alpha = if (active) 1f else 0.45f
        val radius = CornerRadius((8f * scale).coerceIn(2f, 12f))
        drawRoundRect(
            color = c.dialog.copy(alpha = 0.92f * alpha),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = if (node.id == selected) c.accent else c.borderStrong.copy(alpha = 0.9f * alpha),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(width = if (node.id == selected) 2.5f else 1f),
        )
        // 顶部色条：缩小时也能看出节点块的朝向
        drawRect(
            color = c.accent.copy(alpha = 0.5f * alpha),
            topLeft = topLeft,
            size = Size(size.width, max(2f, 3f * scale.coerceAtMost(2f))),
        )
        if (scale > 0.18f && size.width > 54f && size.height > 18f) {
            val title = node.title?.takeIf { it.isNotBlank() } ?: node.type
            val label = labels.getOrPut(node.id) {
                measurer.measure(
                    text = title,
                    style = TextStyle(color = c.text.copy(alpha = alpha), fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(40, size.width.roundToInt() - 8)),
                )
            }
            drawText(label, topLeft = topLeft + Offset(4f, 4f))
        }
    }
}

/** 右下角小地图：整图缩略 + 当前视野框。 */
private fun DrawScope.drawMinimap(
    graph: UiGraph?,
    bounds: Rect?,
    viewport: IntSize,
    scale: Float,
    offset: Offset,
    density: Float,
    c: SpectrumColors,
) {
    if (graph == null || bounds == null) return
    val rect = minimapRect(viewport, bounds, density) ?: return

    drawRoundRect(
        color = c.dialog.copy(alpha = 0.90f),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(8f * density),
    )
    drawRoundRect(
        color = c.borderStrong,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(8f * density),
        style = Stroke(width = 1f),
    )

    val innerW = rect.width * 0.9f
    val innerH = rect.height * 0.9f
    val s = min(innerW / bounds.width, innerH / bounds.height)
    val ox = rect.left + (rect.width - bounds.width * s) / 2f
    val oy = rect.top + (rect.height - bounds.height * s) / 2f

    graph.nodes.forEach { node ->
        val w = max(1.2f, node.width.toFloat() * s)
        val h = max(1.2f, node.height.toFloat() * s)
        drawRect(
            color = if (node.mode == UiWorkflowParser.MODE_NORMAL) {
                c.accent.copy(alpha = 0.75f)
            } else {
                c.textDim.copy(alpha = 0.45f)
            },
            topLeft = Offset(
                ox + (node.x.toFloat() - bounds.left) * s,
                oy + (node.y.toFloat() - bounds.top) * s,
            ),
            size = Size(w, h),
        )
    }

    // 视野框：屏幕左上角对应的世界坐标 = -offset / scale
    if (scale > 0f) {
        val viewLeft = -offset.x / scale
        val viewTop = -offset.y / scale
        val viewW = viewport.width / scale
        val viewH = viewport.height / scale
        val rectLeft = ox + (viewLeft - bounds.left) * s
        val rectTop = oy + (viewTop - bounds.top) * s
        drawRect(
            color = c.accent.copy(alpha = 0.16f),
            topLeft = Offset(rectLeft, rectTop),
            size = Size(viewW * s, viewH * s),
        )
        drawRect(
            color = c.accent,
            topLeft = Offset(rectLeft, rectTop),
            size = Size(viewW * s, viewH * s),
            style = Stroke(width = 1f),
        )
    }
}
