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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
    /** 本次提交里真正会执行的节点 id（生效模式 normal + 节点图已启用 + 连到了输出）。 */
    activeNodeIds: Set<Int>,
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

    // 完整包围盒用于边界约束与小地图；初始适配用去掉离群 Node 后的聚焦范围，
    // 否则一个远处的备注节点就能把整张图缩成一小团。
    val bounds = remember(graph) { graphBounds(graph) }
    val focusBounds = remember(graph) { graphFocusBounds(graph) }
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
        val b = focusBounds ?: bounds ?: return
        if (viewport.width == 0 || viewport.height == 0) return
        val pad = 28f
        val sx = (viewport.width - pad * 2) / max(1f, b.width)
        val sy = (viewport.height - pad * 2) / max(1f, b.height)
        // 小图不要放大到 1:1 以上，否则十几个节点会撑满屏幕、显得很糙。
        scale = min(sx, sy).coerceIn(0.02f, 1.0f)
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

    // viewport 是 state，onSizeChanged 同一帧里读到的仍是旧值，用 Effect 在尺寸落定后做一次初始适配。
    LaunchedEffect(viewport, graph) {
        if (!fitted && viewport.width > 0 && viewport.height > 0) {
            fitToScreen()
        }
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
                            "${nodes.size} 节点 · ${groups.size} 分组 · $linkCount 连线",
                            color = c.textDim,
                            fontSize = 9.5.sp,
                            fontFamily = SpectrumMono,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("搜索节点名 / 类型 / 编号", color = c.textDim, fontSize = 11.sp)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = c.text, fontSize = 12.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
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
                            activeIds = activeNodeIds,
                            scale = scale,
                            offset = offset,
                            selected = selected,
                            measurer = measurer,
                            labels = labels,
                            density = density,
                            c = c,
                        )

                    }

                    if (graph != null && bounds != null) {
                        Canvas(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp)
                                .size(width = 92.dp, height = 118.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.dialog.copy(alpha = 0.92f))
                                .border(1.dp, c.borderStrong, RoundedCornerShape(10.dp))
                                .pointerInput(bounds, scale, offset) {
                                    detectTapGestures { tap ->
                                        val projection = mapProjection(bounds, Size(size.width.toFloat(), size.height.toFloat()))
                                        val worldX = bounds.left + (tap.x - projection.ox) / projection.s
                                        val worldY = bounds.top + (tap.y - projection.oy) / projection.s
                                        val next = Offset(
                                            viewport.width / 2f - worldX * scale,
                                            viewport.height / 2f - worldY * scale,
                                        )
                                        offset = clamp(next, scale)
                                    }
                                },
                        ) {
                            drawMinimapLocal(graph, bounds, scale, offset, viewport, c)
                        }
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


/**
 * 画布：分组 → 连线 → 节点。
 * 文本用固定的屏幕字号（不随缩放无限变小），并缓存在 [labels] 里，避免每帧重新排版。
 */
private fun DrawScope.drawScene(
    graph: UiGraph?,
    activeIds: Set<Int>,
    scale: Float,
    offset: Offset,
    selected: Int?,
    measurer: TextMeasurer,
    labels: MutableMap<Int, TextLayoutResult>,
    density: Float,
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
        if (group.title.isNotBlank() && size.width >= 160f) {
            val groupPx = (size.height * 0.05f).coerceIn(9f, 20f)
            val b = groupPx.roundToInt()
            val key = -group.id - 1 - b * 100000
            val label = labels.getOrPut(key) {
                measurer.measure(
                    text = group.title,
                    style = TextStyle(color = c.textMuted, fontSize = (b / density).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(48, size.width.roundToInt() - 24)),
                )
            }
            // 标题给一块底衬，压在节点上方时仍然可读
            val pillW = (label.size.width + 10).toFloat().coerceAtMost(size.width)
            val pillH = label.size.height + 4f
            drawRoundRect(
                color = c.background.copy(alpha = 0.82f),
                topLeft = topLeft + Offset(3f, 3f),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(5f),
            )
            drawText(label, topLeft = topLeft + Offset(8f, 5f))
        }
    }

    // 连线：源节点右中 → 目标节点左中，三次贝塞尔
    val byId = graph.byId
    // 「亮」= 本次提交会执行：生效模式 normal、所在节点图已启用、并且连到了输出。
    // 只看 mode 不够：图级关闭不改 mode；也不够判断孤立节点（它们不会被提交）。
    fun isActive(node: UiNode): Boolean = node.id in activeIds
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
            color = if (isActive(from) && isActive(to)) {
                c.accent.copy(alpha = 0.55f)
            } else {
                c.textDim.copy(alpha = 0.28f)
            },
            // 线宽不随缩放无限变细：缩到再小也保持 1.6px，看得见拓扑
            style = Stroke(width = (1.8f * scale.coerceIn(0.8f, 1.4f))),
        )
    }

    // 节点
    graph.nodes.forEach { node ->
        val topLeft = Offset(sx(node.x), sy(node.y))
        val size = Size(node.width.toFloat() * scale, node.height.toFloat() * scale)
        if (size.width < 1.5f || size.height < 1.5f) return@forEach
        val active = isActive(node)
        val alpha = if (active) 1f else 0.42f
        val radius = CornerRadius(6f.coerceAtLeast(min(10f, size.height * 0.25f)))
        // 节点本体：比分组框亮一档，形成"浮在画布上"的层次
        drawRoundRect(
            color = c.cardStrong.copy(alpha = (if (active) 0.97f else 0.55f)),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
        )
        // 顶部标题条：启用=强调色，旁路/静音=灰
        // 标题条要容得下字号，否则文字会溢出到块外、被下一层盖掉半截
        val titlePx = (size.height * 0.16f).coerceIn(9f, 26f)
        val headerH = (titlePx + 6f).coerceAtLeast(14f).coerceAtMost(size.height * 0.42f)
        drawRoundRect(
            color = (if (active) c.accent else c.textDim).copy(alpha = 0.85f * alpha),
            topLeft = topLeft,
            size = Size(size.width, headerH),
            cornerRadius = CornerRadius(min(radius.x, headerH / 2f)),
        )
        // 下半部分把标题条盖回圆角，避免出现上下两截
        drawRect(
            color = (if (active) c.accent else c.textDim).copy(alpha = 0.85f * alpha),
            topLeft = topLeft + Offset(0f, headerH * 0.6f),
            size = Size(size.width, headerH * 0.4f),
        )
        drawRoundRect(
            color = if (node.id == selected) c.accent else c.borderStrong.copy(alpha = 0.75f * alpha),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(width = if (node.id == selected) 2.5f else 1f),
        )
        // 身体里的两条槽位线：空矩形太像占位框，加两条短线才有"节点"的样子
        if (size.height > headerH + 16f) {
            val slotColor = c.borderStrong.copy(alpha = 0.5f * alpha)
            val slotW = (size.width * 0.42f).coerceAtMost(90f)
            val slotH = 3f.coerceAtLeast(size.height * 0.05f)
            drawRoundRect(
                color = slotColor,
                topLeft = topLeft + Offset(6f, headerH + size.height * 0.16f),
                size = Size(slotW, slotH),
                cornerRadius = CornerRadius(slotH / 2f),
            )
            if (size.height > headerH + 44f) {
                drawRoundRect(
                    color = slotColor,
                    topLeft = topLeft + Offset(6f, headerH + size.height * 0.34f),
                    size = Size(slotW * 0.8f, slotH),
                    cornerRadius = CornerRadius(slotH / 2f),
                )
            }
        }
        if (size.width >= 72f && size.height >= 26f) {
            // 字号跟着块大小走：缩小时字也缩小，避免几十个标签互相压叠
            val bucket = titlePx.roundToInt()
            val title = node.title?.takeIf { it.isNotBlank() } ?: node.type
            val label = labels.getOrPut(node.id * 1000 + bucket) {
                measurer.measure(
                    text = title,
                    style = TextStyle(
                        color = c.accentInk.copy(alpha = alpha),
                        fontSize = (bucket / density).sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(20, size.width.roundToInt() - 8)),
                )
            }
            clipRect(
                left = topLeft.x,
                top = topLeft.y,
                right = topLeft.x + size.width,
                bottom = topLeft.y + headerH,
            ) {
                drawText(
                    label,
                    topLeft = topLeft + Offset(4f, max(0f, (headerH - label.size.height) / 2f)),
                )
            }
        }
        // 类型行：节点明显放大后才补，避免小方块里出现两行糊字
        val displayTitle = node.title?.takeIf { it.isNotBlank() } ?: node.type
        if (size.height >= 92f && size.width >= 150f && node.type != displayTitle) {
            val typePx = (size.height * 0.14f).coerceIn(8f, 22f)
            val typeBucket = typePx.roundToInt()
            val typeKey = node.id * 1000 + 500 + typeBucket
            val typeLabel = labels.getOrPut(typeKey) {
                measurer.measure(
                    text = node.type,
                    style = TextStyle(color = c.textDim, fontSize = (typeBucket / density).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(20, size.width.roundToInt() - 8)),
                )
            }
            clipRect(
                left = topLeft.x,
                top = topLeft.y + headerH,
                right = topLeft.x + size.width,
                bottom = topLeft.y + size.height,
            ) {
                drawText(typeLabel, topLeft = topLeft + Offset(4f, headerH + 3f))
            }
        }
    }
}

/** 小地图投影：把世界坐标映射到缩略图局部坐标（留 6% 内边距）。 */
internal data class MapProjection(val s: Float, val ox: Float, val oy: Float)

internal fun mapProjection(bounds: Rect, canvas: Size): MapProjection {
    val s = min(canvas.width / bounds.width, canvas.height / bounds.height) * 0.88f
    val ox = (canvas.width - bounds.width * s) / 2f
    val oy = (canvas.height - bounds.height * s) / 2f
    return MapProjection(s, ox, oy)
}

/** 小地图内容：整图缩略 + 当前视野框（局部坐标，由独立浮层 Canvas 调用）。 */
private fun DrawScope.drawMinimapLocal(
    graph: UiGraph,
    bounds: Rect,
    scale: Float,
    offset: Offset,
    viewport: IntSize,
    c: SpectrumColors,
) {
    val projection = mapProjection(bounds, size)
    val s = projection.s

    graph.nodes.forEach { node ->
        val w = max(1.2f, node.width.toFloat() * s)
        val h = max(1.2f, node.height.toFloat() * s)
        drawRect(
            color = if (node.mode == UiWorkflowParser.MODE_NORMAL) {
                c.accent.copy(alpha = 0.78f)
            } else {
                c.textDim.copy(alpha = 0.45f)
            },
            topLeft = Offset(
                projection.ox + (node.x.toFloat() - bounds.left) * s,
                projection.oy + (node.y.toFloat() - bounds.top) * s,
            ),
            size = Size(w, h),
        )
    }

    if (scale > 0f && viewport.width > 0) {
        val viewLeft = projection.ox + (-offset.x / scale - bounds.left) * s
        val viewTop = projection.oy + (-offset.y / scale - bounds.top) * s
        val viewW = viewport.width / scale * s
        val viewH = viewport.height / scale * s
        drawRect(
            color = c.accent.copy(alpha = 0.18f),
            topLeft = Offset(viewLeft, viewTop),
            size = Size(viewW, viewH),
        )
        drawRect(
            color = c.accent,
            topLeft = Offset(viewLeft, viewTop),
            size = Size(viewW, viewH),
            style = Stroke(width = 1.2f),
        )
    }
}

/**
 * 初始视野用的"聚焦包围盒"：用固定网格找节点最密集的一块。
 *
 * ComfyUI 画布上常有被拖到很远的 Note / 注释节点，按全图包围盒适配会把主图缩成一小团，
 * 所以初始只适配最密的那一格及其 3×3 邻域；远处节点仍会画出来，可以平移或点小地图过去。
 */
internal fun graphFocusBounds(graph: UiGraph?): Rect? {
    if (graph == null || graph.nodes.isEmpty()) return null
    if (graph.nodes.size <= 3) return graphBounds(graph)

    val cell = 700.0
    val buckets = mutableMapOf<Long, MutableList<UiNode>>()
    graph.nodes.forEach { node ->
        val gx = Math.floor((node.x + node.width / 2.0) / cell).toLong()
        val gy = Math.floor((node.y + node.height / 2.0) / cell).toLong()
        buckets.getOrPut(gx * 100_000L + gy) { mutableListOf() }.add(node)
    }
    val seedKey = buckets.maxByOrNull { it.value.size }?.key ?: return graphBounds(graph)
    val seedX = Math.floorDiv(seedKey, 100_000L)
    val seedY = seedKey - seedX * 100_000L

    val kept = mutableListOf<UiNode>()
    for (dx in -1L..1L) {
        for (dy in -1L..1L) {
            buckets[(seedX + dx) * 100_000L + (seedY + dy)]?.let { kept.addAll(it) }
        }
    }
    if (kept.isEmpty()) return graphBounds(graph)

    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    kept.forEach { node ->
        left = min(left, node.x.toFloat())
        top = min(top, node.y.toFloat())
        right = max(right, (node.x + node.width).toFloat())
        bottom = max(bottom, (node.y + node.height).toFloat())
    }
    return Rect(left, top, right, bottom)
}