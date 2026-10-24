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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
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
import com.comfypilot.core.workflow.WorkflowField
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
    /** 生效模式为 normal 的节点 id（含 Note/Label 这类只做提示、不参与执行的节点）。 */
    activeNodeIds: Set<Int>,
    /** 所属节点图被关闭的节点：它们不提交，图上显示成灰。 */
    disabledNodeIds: Set<Int>,
    /** 节点 id → 输入区里的节点视图：选中节点时在浮层内展开它的参数卡片。 */
    nodeViews: Map<Int, com.comfypilot.core.workflow.WorkflowNodeView>,
    /** 节点 id → 当前是否启用（用于卡片上的启用/关闭按钮）。 */
    nodeEnabled: Map<Int, Boolean>,
    /** 切换节点启用状态；第二个参数为要设置的新状态（true=启用）。 */
    onToggleNode: (Int, Boolean) -> Unit,
    /** 跳到输入页并定位到该节点卡片。 */
    onJumpToNode: (Int) -> Unit,
    /** 本次会话里已断开的连线 id。 */
    removedLinkIds: Set<Int>,
    /** 断开一条连线（父级负责持久到会话状态）。 */
    onRemoveLink: (Int) -> Unit,
    /** 会话级连线改接：把某输入接到另一个输出。 */
    linkOverrides: List<com.comfypilot.core.workflow.InputLinkOverride>,
    onAddLink: (com.comfypilot.core.workflow.InputLinkOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Spectrum.colors
    val density = LocalDensity.current.density
    val graph = remember(raw, removedLinkIds, linkOverrides) {
        UiWorkflowParser.parse(raw)
            ?.withoutLinks(removedLinkIds)
            ?.withInputLinks(linkOverrides)
    }
    val measurer = rememberTextMeasurer()
    // 文本布局只跟节点宽度、字号有关，与缩放无关；跨帧缓存，避免每帧重排上百段文字。
    val labels = remember(graph) { mutableMapOf<Int, TextLayoutResult>() }

    // 视图状态只在浮层生命周期内保存：改连线后不重置缩放/平移。
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    // 参数卡片默认只显示标题行，用户点「展开」才列出参数。
    var cardExpanded by remember { mutableStateOf(false) }
    var selectedLink by remember { mutableStateOf<Int?>(null) }
    // 参数级连线：选中的槽位、连线起点、待确认的一对槽位
    var selectedSlot by remember { mutableStateOf<SlotRef?>(null) }
    var linkStart by remember { mutableStateOf<SlotRef?>(null) }
    var pendingLink by remember { mutableStateOf<Pair<SlotRef, SlotRef>?>(null) }
    val highlightSlots: Set<Int> = remember(selectedSlot, linkStart, pendingLink) {
        buildSet {
            selectedSlot?.let { add(it.key) }
            linkStart?.let { add(it.key) }
            pendingLink?.let { add(it.first.key); add(it.second.key) }
        }
    }
    var confirmLinkRemoval by remember(graph) { mutableStateOf(false) }
    LaunchedEffect(selected) { cardExpanded = false }
    LaunchedEffect(selectedLink) { confirmLinkRemoval = false }
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

    fun zoomBy(factor: Float) {
        if (viewport.width == 0) return
        val nextScale = (scale * factor).coerceIn(0.02f, 3f)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val focus = (center - offset) / scale
        offset = clamp(center - focus * nextScale, nextScale)
        scale = nextScale
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
    LaunchedEffect(viewport) {
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
                    GhostButton(text = "－", onClick = { zoomBy(0.7f) }, height = 34.dp)
                    GhostButton(text = "＋", onClick = { zoomBy(1.4f) }, height = 34.dp)
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

                                    // 先判参数槽位（连线入口），再判节点体
                                    val slot = if (graph == null) null else hitSlot(graph, tap, scale, offset, measurer, density)
                                    val start = linkStart
                                    if (start != null && slot != null) {
                                        val out = if (!start.input) start else if (!slot.input) slot else null
                                        val inn = if (start.input) start else if (slot.input) slot else null
                                        if (out != null && inn != null && out.nodeId != inn.nodeId) {
                                            pendingLink = out to inn
                                            linkStart = null
                                            selectedSlot = null
                                            selected = null
                                        }
                                    } else if (slot != null) {
                                        selectedSlot = slot
                                        linkStart = null
                                        pendingLink = null
                                        selected = null
                                        selectedLink = null
                                    } else {
                                        val world = (tap - offset) / scale
                                        val hit = nodes.firstOrNull { node ->
                                            world.x >= node.x &&
                                                world.x <= node.x + node.width &&
                                                world.y >= node.y &&
                                                world.y <= node.y + node.height
                                        }
                                        selected = hit?.id
                                        selectedLink = null
                                        selectedSlot = null
                                        linkStart = null
                                        pendingLink = null
                                    }
                                },
                                onDoubleTap = { fitToScreen() },
                            )
                        },
                ) {

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawScene(
                            graph = graph,
                            activeIds = activeNodeIds,
                            disabledIds = disabledNodeIds,
                            removedLinkIds = removedLinkIds,
                            selectedLink = selectedLink,
                            highlightSlots = highlightSlots,
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

                    val activeLink = selectedLink?.let { id -> graph?.links?.get(id) }
                    if (activeLink != null && graph != null) {
                        val fromNode = graph.byId[activeLink.originId]
                        val toNode = graph.byId[activeLink.targetId]
                        if (fromNode != null && toNode != null) {
                            val outName = fromNode.outputs.getOrNull(activeLink.originSlot)?.name ?: "输出"
                            val inName = toNode.inputs.getOrNull(activeLink.targetSlot)?.name ?: "输入"
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.dialog.copy(alpha = 0.96f))
                                    .border(1.dp, c.accentBorder, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "连线",
                                        color = c.accent,
                                        fontSize = 11.sp,
                                        fontFamily = SpectrumMono,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "✕",
                                        color = c.textMuted,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedLink = null }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                Text(
                                    "${nodeLabel(fromNode)}.$outName  →  ${nodeLabel(toNode)}.$inName",
                                    color = c.text,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "类型 ${activeLink.type}",
                                    color = c.textDim,
                                    fontSize = 9.5.sp,
                                    fontFamily = SpectrumMono,
                                )
                                if (!confirmLinkRemoval) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ActionChip(
                                            text = "断开连接",
                                            tone = c.warn,
                                            onClick = { confirmLinkRemoval = true },
                                        )
                                        ActionChip(
                                            text = "关闭",
                                            tone = c.textMuted,
                                            onClick = { selectedLink = null },
                                        )
                                    }
                                } else {
                                    Text(
                                        "断开后该输入会变成未连接，提交可能被阻断，确认断开？",
                                        color = c.warn,
                                        fontSize = 11.sp,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ActionChip(
                                            text = "确认断开",
                                            tone = c.err,
                                            onClick = {
                                                selectedLink = null
                                                confirmLinkRemoval = false
                                                onRemoveLink(activeLink.id)
                                            },
                                        )
                                        ActionChip(
                                            text = "取消",
                                            tone = c.textMuted,
                                            onClick = { confirmLinkRemoval = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val slot = selectedSlot
                    if (slot != null && graph != null) {
                        val node = graph.byId[slot.nodeId]
                        val slotName = if (slot.input) {
                            node?.inputs?.getOrNull(slot.index)?.name
                        } else {
                            node?.outputs?.getOrNull(slot.index)?.name
                        }
                        val slotType = if (slot.input) {
                            node?.inputs?.getOrNull(slot.index)?.type
                        } else {
                            node?.outputs?.getOrNull(slot.index)?.type
                        }
                        val linkedId = if (slot.input) node?.inputs?.getOrNull(slot.index)?.linkId else null
                        val linking = linkStart?.key == slot.key
                        if (node != null && slotName != null) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.dialog.copy(alpha = 0.96f))
                                    .border(1.dp, c.accentBorder, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "#${node.id} ${nodeLabel(node)} · $slotName",
                                        color = c.text,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "✕",
                                        color = c.textMuted,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedSlot = null; linkStart = null }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                Text(
                                    "类型 ${slotType ?: "?"} · ${if (linkedId != null) "已连接" else "未连接"}",
                                    color = c.textDim,
                                    fontSize = 9.5.sp,
                                    fontFamily = SpectrumMono,
                                )
                                if (linking) {
                                    Text(
                                        "已选中这个参数，去点另一个节点的输出/输入完成连线",
                                        color = c.accent,
                                        fontSize = 11.sp,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (linking) {
                                        ActionChip(
                                            text = "取消连线",
                                            tone = c.textMuted,
                                            onClick = { linkStart = null },
                                        )
                                    } else {
                                        ActionChip(
                                            text = "连线",
                                            tone = c.accent,
                                            onClick = { linkStart = slot },
                                        )
                                    }
                                    if (slot.input && linkedId != null) {
                                        ActionChip(
                                            text = "断开此参数连线",
                                            tone = c.warn,
                                            onClick = {
                                                onRemoveLink(linkedId)
                                                selectedSlot = null
                                            },
                                        )
                                    }
                                    ActionChip(
                                        text = "关闭",
                                        tone = c.textMuted,
                                        onClick = { selectedSlot = null; linkStart = null },
                                    )
                                }
                            }
                        }
                    }

                    val pending = pendingLink
                    if (pending != null && graph != null) {
                        val (outSlot, inSlot) = pending
                        val outNode = graph.byId[outSlot.nodeId]
                        val inNode = graph.byId[inSlot.nodeId]
                        val outName = outNode?.outputs?.getOrNull(outSlot.index)?.name ?: "?"
                        val inName = inNode?.inputs?.getOrNull(inSlot.index)?.name ?: "?"
                        val linkType = outNode?.outputs?.getOrNull(outSlot.index)?.type
                            ?: inNode?.inputs?.getOrNull(inSlot.index)?.type
                            ?: "?"
                        if (outNode != null && inNode != null) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.dialog.copy(alpha = 0.96f))
                                    .border(1.dp, c.accent, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "新建连线",
                                    color = c.accent,
                                    fontSize = 11.sp,
                                    fontFamily = SpectrumMono,
                                )
                                Text(
                                    "${nodeLabel(outNode)}.$outName  →  ${nodeLabel(inNode)}.$inName",
                                    color = c.text,
                                    fontSize = 12.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "类型 $linkType",
                                    color = c.textDim,
                                    fontSize = 9.5.sp,
                                    fontFamily = SpectrumMono,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionChip(
                                        text = "确认连线",
                                        tone = c.ok,
                                        onClick = {
                                            onAddLink(
                                                com.comfypilot.core.workflow.InputLinkOverride(
                                                    nodeId = inNode.id,
                                                    inputName = inName,
                                                    originNodeId = outNode.id,
                                                    originSlot = outSlot.index,
                                                    type = linkType,
                                                ),
                                            )
                                            pendingLink = null
                                        },
                                    )
                                    ActionChip(
                                        text = "取消",
                                        tone = c.textMuted,
                                        onClick = { pendingLink = null },
                                    )
                                }
                            }
                        }
                    }
                    val current = nodes.firstOrNull { it.id == selected }
                    val detail = current?.let { nodeViews[it.id] }
                    if (current != null) {
                        val enabled = nodeEnabled[current.id] ?: (current.mode == UiWorkflowParser.MODE_NORMAL)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.dialog.copy(alpha = 0.96f))
                                .border(1.dp, c.borderStrong, RoundedCornerShape(14.dp)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 8.dp, top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "#${current.id}  ${current.title?.takeIf { it.isNotBlank() } ?: current.type}",
                                        color = c.text,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append(current.type)
                                            append(" · ")
                                            append(if (enabled) "启用" else "已关闭")
                                            if (current.id in disabledNodeIds) append(" · 所在节点图未启用")
                                            append(" · 输入 ${current.inputs.size} / 输出 ${current.outputs.size}")
                                        },
                                        color = c.textDim,
                                        fontSize = 9.5.sp,
                                        fontFamily = SpectrumMono,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    "✕",
                                    color = c.textMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selected = null }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }

                            // 操作行：启用/关闭、展开参数、跳转到输入页
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = if (cardExpanded) 6.dp else 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val graphActive = current.id !in disabledNodeIds
                                ActionChip(
                                    text = when {
                                        !graphActive -> "节点图未启用"
                                        enabled -> "关闭节点"
                                        else -> "启用节点"
                                    },
                                    tone = when {
                                        !graphActive -> c.textDim
                                        enabled -> c.warn
                                        else -> c.ok
                                    },
                                    onClick = {
                                        // 节点图整体关闭时，单节点开关没有意义，按钮只做提示。
                                        if (graphActive) onToggleNode(current.id, !enabled)
                                    },
                                )
                                ActionChip(
                                    text = if (cardExpanded) "收起参数" else "展开参数",
                                    tone = c.accent,
                                    onClick = { cardExpanded = !cardExpanded },
                                )
                                ActionChip(
                                    text = "跳转",
                                    tone = c.textMuted,
                                    onClick = { onJumpToNode(current.id) },
                                )
                            }

                            if (cardExpanded) {
                                val fields = detail?.fields.orEmpty()
                                if (fields.isEmpty()) {
                                    Text(
                                        "这个节点没有可调参数。",
                                        color = c.textMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 300.dp)
                                            .padding(horizontal = 12.dp),
                                    ) {
                                        items(fields, key = { it.inputName }) { field ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 5.dp),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Text(
                                                    field.label ?: field.inputName,
                                                    color = c.textMuted,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    fieldValueText(field),
                                                    color = c.text,
                                                    fontSize = 11.sp,
                                                    fontFamily = SpectrumMono,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1.4f),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
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
    disabledIds: Set<Int>,
    removedLinkIds: Set<Int>,
    selectedLink: Int?,
    highlightSlots: Set<Int>,
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

    fun isActive(node: UiNode): Boolean {
        if (node.id !in activeIds) return false
        // 没有数据输出的节点（Note / Label / 分组说明）本质是画布注释，
        // 即使在未启用的节点图里也保持正常颜色，方便阅读。
        if (node.outputs.isEmpty()) return true
        return node.id !in disabledIds
    }

    // 标题栏在世界坐标里的高度：与 ComfyUI 的 30 单位保持一致，小节点按比例收。
    // 与绘制时 26% 的标题栏保持一致：槽位从标题栏下沿开始，标题不会被参数名压住。
    fun headerWorld(node: UiNode): Double = node.height * 0.26

    /** 第 index 个槽位在世界坐标里的纵向中心。 */
    fun slotYWorld(node: UiNode, index: Int): Double {
        val slots = max(node.inputs.size, node.outputs.size).coerceAtLeast(1)
        val usable = (node.height - headerWorld(node)).coerceAtLeast(1.0)
        val step = (usable / slots).coerceIn(4.0, 22.0)
        return node.y + headerWorld(node) + step * (index + 0.5)
    }

    /** 先按比例量一次，超出可用宽度就等比缩小后重量；用 Clip 保证不会出现省略号。 */
    fun fitLabel(
        text: String,
        maxWidth: Float,
        propPx: Float,
        color: androidx.compose.ui.graphics.Color,
        cacheKey: Int,
    ): TextLayoutResult {
        val widthBucket = (maxWidth / 8f).toInt()
        val propBucket = propPx.roundToInt().coerceAtLeast(1)
        val key = cacheKey * 1_000_000 + widthBucket * 1000 + propBucket
        return labels.getOrPut(key) {
            val probe = measurer.measure(
                text = text,
                style = TextStyle(color = color, fontSize = (propBucket / density).sp),
                maxLines = 1,
            )
            val fitted = if (probe.size.width > maxWidth && probe.size.width > 0) {
                (propBucket * maxWidth / probe.size.width).coerceAtLeast(1f)
            } else {
                propBucket.toFloat()
            }
            measurer.measure(
                text = text,
                style = TextStyle(
                    color = color,
                    fontSize = (fitted.roundToInt().coerceAtLeast(1) / density).sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }

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
            val b = (12f * density).roundToInt()
            val label = labels.getOrPut(-group.id - 1 - b * 100000) {
                measurer.measure(
                    text = group.title,
                    style = TextStyle(color = c.textMuted, fontSize = (b / density).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = max(48, size.width.roundToInt() - 24)),
                )
            }
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

    // 连线：起点/终点落在真实的输出/输入槽位上（槽位序号来自工作流 links）
    val byId = graph.byId
    graph.links.values.forEach { link ->
        if (link.id in removedLinkIds) return@forEach
        val from = byId[link.originId] ?: return@forEach
        val to = byId[link.targetId] ?: return@forEach
        val outIndex = link.originSlot.coerceIn(0, max(0, from.outputs.size - 1))
        val inIndex = link.targetSlot.coerceIn(0, max(0, to.inputs.size - 1))
        val start = Offset(sx(from.x + from.width), sy(slotYWorld(from, outIndex)))
        val end = Offset(sx(to.x), sy(slotYWorld(to, inIndex)))
        val dx = max(20f, abs(end.x - start.x) / 2f)
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y)
        }
        drawPath(
            path = path,
            color = if (link.id == selectedLink) c.accent else if (isActive(from) && isActive(to)) {
                c.accent.copy(alpha = 0.55f)
            } else {
                c.textDim.copy(alpha = 0.28f)
            },
            style = Stroke(width = if (link.id == selectedLink) 3.4f else 1.8f * scale.coerceIn(0.8f, 1.4f)),
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

        drawRoundRect(
            color = c.cardStrong.copy(alpha = if (active) 0.97f else 0.55f),
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
        )

        // 标题栏：高度固定 28px（不随缩放变），标题字号也固定 10sp。
        // 标题栏固定占卡片高的 26%；字号跟卡片宽度走，但不超过标题栏高度：
        // 同宽卡片标题一样大，标题也永远不会溢出到参数名那一行。
        val headerH = size.height * 0.26f
        val titlePx = (size.width * 0.11f).coerceAtMost(headerH * 0.62f)
        val headerColor = (if (active) c.accent else c.textDim).copy(alpha = 0.85f * alpha)
        drawRoundRect(
            color = headerColor,
            topLeft = topLeft,
            size = Size(size.width, headerH),
            cornerRadius = CornerRadius(min(radius.x, headerH / 2f)),
        )
        drawRect(
            color = headerColor,
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

        if (headerH >= 4f) {
            val title = node.title?.takeIf { it.isNotBlank() } ?: node.type
            // 按比例给出目标字号，再按实际测量宽度收缩：任何缩放下标题都完整显示。
            val label = fitLabel(
                text = title,
                maxWidth = size.width - 10f,
                propPx = titlePx,
                color = c.accentInk.copy(alpha = alpha),
                cacheKey = node.id,
            )
            clipRect(
                left = topLeft.x,
                top = topLeft.y,
                right = topLeft.x + size.width,
                bottom = topLeft.y + headerH,
            ) {
                drawText(
                    label,
                    topLeft = topLeft + Offset(5f, max(0f, (headerH - label.size.height) / 2f)),
                )
            }
        }

        // 真实槽位：输入在左边缘、输出在右边缘；缩小时只画圆点，放大后补参数名。
        val slots = max(node.inputs.size, node.outputs.size).coerceAtLeast(1)
        val slotSpacingPx = ((node.height - headerWorld(node)) / slots * scale).toFloat()
        // 名字之间至少留 11px，否则一列参数名会互相压叠，只留圆点更清爽。
        // 参数名按行高比例缩放：缩小时跟着变小（不叠字），放大时跟着变大，永远和卡片同比例。
        val showNames = slotSpacingPx >= 4f
        val dotR = 2.6f
        fun drawSlot(nodeId: Int, index: Int, input: Boolean, slotName: String) {
            val y = sy(slotYWorld(node, index))
            if (y < topLeft.y || y > topLeft.y + size.height) return
            val x = if (input) topLeft.x else topLeft.x + size.width
            val slotKey = nodeId * 1000 + index * 2 + (if (input) 1 else 0)
            val highlighted = slotKey in highlightSlots
            drawCircle(
                color = if (highlighted) c.accent else if (active) c.accent.copy(alpha = 0.9f) else c.textDim.copy(alpha = 0.6f),
                radius = if (highlighted) dotR + 2.5f else dotR,
                center = Offset(x, y),
            )
            if (highlighted) {
                drawCircle(
                    color = c.accent.copy(alpha = 0.45f),
                    radius = dotR + 7f,
                    center = Offset(x, y),
                    style = Stroke(width = 1.6f),
                )
            }
            if (!showNames || slotName.isBlank()) return
            // 参数名只占半张卡片宽，按实际测量收缩，不省略。
            val label = fitLabel(
                text = slotName,
                maxWidth = size.width / 2f - 12f,
                propPx = (size.width * 0.055f).coerceAtMost(slotSpacingPx * 0.8f).coerceAtLeast(5f),
                color = c.textMuted.copy(alpha = alpha),
                cacheKey = 100_000 + nodeId * 100 + index * 2 + (if (input) 0 else 1),
            )
            val textTop = y - label.size.height / 2f
            // 裁在「本槽位那一行」内：既不会盖住标题，也不会压到相邻槽位。
            val rowTop = max(topLeft.y + headerH, y - slotSpacingPx / 2f)
            val rowBottom = min(topLeft.y + size.height, y + slotSpacingPx / 2f)
            clipRect(
                left = topLeft.x,
                top = rowTop,
                right = topLeft.x + size.width,
                bottom = rowBottom,
            ) {
                if (input) {
                    drawText(label, topLeft = Offset(topLeft.x + 9f, textTop))
                } else {
                    drawText(label, topLeft = Offset(topLeft.x + size.width - label.size.width - 9f, textTop))
                }
            }
        }

        node.inputs.forEachIndexed { index, input -> drawSlot(node.id, index, true, input.name) }
        node.outputs.forEachIndexed { index, output -> drawSlot(node.id, index, false, output.name) }
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

/** 参数卡片里的一行值：连线/空值都有明确文案，不显示 JSON 引号。 */
private fun fieldValueText(field: WorkflowField): String = when {
    field.linked -> "由连线提供"
    field.value == null -> "—"
    else -> field.value.toString().trim('"')
}

/** 参数卡片上的一枚操作按钮：圆角小胶囊，按语义着色。 */
@Composable
private fun ActionChip(text: String, tone: Color, onClick: () -> Unit) {
    val c = Spectrum.colors
    Text(
        text = text,
        color = tone,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 连线端点的屏幕坐标：与绘制时使用同一套槽位公式。 */
private fun linkScreenAnchors(
    link: com.comfypilot.core.workflow.UiLink,
    from: UiNode,
    to: UiNode,
    scale: Float,
    offset: Offset,
): Pair<Offset, Offset> {
    fun slotY(node: UiNode, index: Int): Float {
        val slots = max(node.inputs.size, node.outputs.size).coerceAtLeast(1)
        val header = node.height * 0.26
        val usable = (node.height - header).coerceAtLeast(1.0)
        val step = (usable / slots).coerceIn(4.0, 22.0)
        return (node.y + header + step * (index + 0.5)).toFloat()
    }
    val outIndex = link.originSlot.coerceIn(0, max(0, from.outputs.size - 1))
    val inIndex = link.targetSlot.coerceIn(0, max(0, to.inputs.size - 1))
    val start = Offset(
        (from.x + from.width).toFloat() * scale + offset.x,
        slotY(from, outIndex) * scale + offset.y,
    )
    val end = Offset(
        to.x.toFloat() * scale + offset.x,
        slotY(to, inIndex) * scale + offset.y,
    )
    return start to end
}

/** 三次贝塞尔采样；命中判定用折线近似，足够精确且比解方程便宜。 */
private fun bezierSamples(start: Offset, end: Offset, samples: Int = 24): List<Offset> {
    val dx = max(20f, abs(end.x - start.x) / 2f)
    return (0..samples).map { i ->
        val t = i.toFloat() / samples
        val mt = 1f - t
        val x = mt * mt * mt * start.x + 3 * mt * mt * t * (start.x + dx) +
            3 * mt * t * t * (end.x - dx) + t * t * t * end.x
        val y = mt * mt * mt * start.y + 3 * mt * mt * t * start.y +
            3 * mt * t * t * end.y + t * t * t * end.y
        Offset(x, y)
    }
}

private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSq = ab.x * ab.x + ab.y * ab.y
    if (lengthSq <= 0.0001f) return (point - a).getDistance()
    val t = (((point - a).x * ab.x + (point - a).y * ab.y) / lengthSq).coerceIn(0f, 1f)
    return (point - (a + ab * t)).getDistance()
}

/** 找到离点击位置最近的一条连线（阈值内）。 */
private fun nearestLink(
    graph: UiGraph,
    tap: Offset,
    scale: Float,
    offset: Offset,
    removedLinkIds: Set<Int>,
    threshold: Float,
): Int? {
    var best: Int? = null
    var bestDistance = threshold
    graph.links.values.forEach { link ->
        if (link.id in removedLinkIds) return@forEach
        val from = graph.byId[link.originId] ?: return@forEach
        val to = graph.byId[link.targetId] ?: return@forEach
        val (start, end) = linkScreenAnchors(link, from, to, scale, offset)
        val points = bezierSamples(start, end)
        for (i in 0 until points.size - 1) {
            val d = distanceToSegment(tap, points[i], points[i + 1])
            if (d < bestDistance) {
                bestDistance = d
                best = link.id
            }
        }
    }
    return best
}

/** 卡片上展示节点名：优先标题，其次类型，最后编号。 */
private fun nodeLabel(node: UiNode): String =
    node.title?.takeIf { it.isNotBlank() } ?: node.type.ifBlank { "#${node.id}" }

/** 节点图里的一个参数槽位（input=true 表示输入）。 */
private data class SlotRef(val nodeId: Int, val index: Int, val input: Boolean) {
    val key: Int get() = nodeId * 1000 + index * 2 + (if (input) 1 else 0)
}

private fun slotScreenPoint(
    node: UiNode,
    index: Int,
    input: Boolean,
    scale: Float,
    offset: Offset,
): Offset {
    val slots = max(node.inputs.size, node.outputs.size).coerceAtLeast(1)
    val header = node.height * 0.26
    val usable = (node.height - header).coerceAtLeast(1.0)
    val step = (usable / slots).coerceIn(4.0, 22.0)
    val worldY = node.y + header + step * (index + 0.5)
    val worldX = if (input) node.x else node.x + node.width
    return Offset(worldX.toFloat() * scale + offset.x, worldY.toFloat() * scale + offset.y)
}

/**
 * 命中参数：按参数文字的实际排版尺寸判定（不是整行整块），
 * 文字/圆点之外一律返回 null，由调用方落到"点节点卡片"。
 */
private fun hitSlot(
    graph: UiGraph,
    tap: Offset,
    scale: Float,
    offset: Offset,
    measurer: TextMeasurer,
    density: Float,
): SlotRef? {
    val margin = 4f
    graph.nodes.forEach { node ->
        val nodeW = node.width.toFloat() * scale
        val nodeH = node.height.toFloat() * scale
        if (nodeW < 2f || nodeH < 2f) return@forEach
        val slots = max(node.inputs.size, node.outputs.size)
        if (slots <= 0) return@forEach
        val header = node.height * 0.26
        val usable = (node.height - header).coerceAtLeast(1.0)
        val stepWorld = (usable / slots).coerceIn(4.0, 22.0)
        val stepPx = stepWorld.toFloat() * scale

        node.inputs.forEachIndexed { index, input ->
            val name = input.name
            if (name.isNotBlank() && stepPx >= 4f) {
                val propPx = (nodeW * 0.055f).coerceAtMost(stepPx * 0.8f).coerceAtLeast(5f)
                val maxWidth = nodeW / 2f - 12f
                val layout = measurer.measure(
                    text = name,
                    style = TextStyle(fontSize = (propPx / density).sp),
                    maxLines = 1,
                )
                val width = if (layout.size.width > maxWidth && layout.size.width > 0) {
                    maxWidth
                } else {
                    layout.size.width.toFloat()
                }
                val height = layout.size.height.toFloat()
                val p = slotScreenPoint(node, index, input = true, scale = scale, offset = offset)
                val left = p.x + 9f
                val top = p.y - height / 2f
                if (tap.x in left - margin..left + width + margin && tap.y in top - margin..top + height + margin) {
                    return SlotRef(node.id, index, true)
                }
            }
            // 圆点本身也算
            val dot = slotScreenPoint(node, index, input = true, scale = scale, offset = offset)
            if ((tap - dot).getDistance() <= 9f) return SlotRef(node.id, index, true)
        }

        node.outputs.forEachIndexed { index, output ->
            val name = output.name
            if (name.isNotBlank() && stepPx >= 4f) {
                val propPx = (nodeW * 0.055f).coerceAtMost(stepPx * 0.8f).coerceAtLeast(5f)
                val maxWidth = nodeW / 2f - 12f
                val layout = measurer.measure(
                    text = name,
                    style = TextStyle(fontSize = (propPx / density).sp),
                    maxLines = 1,
                )
                val width = if (layout.size.width > maxWidth && layout.size.width > 0) {
                    maxWidth
                } else {
                    layout.size.width.toFloat()
                }
                val height = layout.size.height.toFloat()
                val p = slotScreenPoint(node, index, input = false, scale = scale, offset = offset)
                val right = p.x - 9f
                val left = right - width
                val top = p.y - height / 2f
                if (tap.x in left - margin..right + margin && tap.y in top - margin..top + height + margin) {
                    return SlotRef(node.id, index, false)
                }
            }
            val dot = slotScreenPoint(node, index, input = false, scale = scale, offset = offset)
            if ((tap - dot).getDistance() <= 9f) return SlotRef(node.id, index, false)
        }
    }
    return null
}