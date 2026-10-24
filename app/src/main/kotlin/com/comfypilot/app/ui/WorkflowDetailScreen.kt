package com.comfypilot.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.comfypilot.core.workflow.WorkflowNodeTraits
import com.comfypilot.core.workflow.NodeTrait
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.app.ui.theme.Dot
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.GradientButton
import com.comfypilot.app.ui.theme.PillTone
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumArt
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumChip
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumPill
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumProgress
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumTabs
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumTextField
import com.comfypilot.app.ui.theme.pressFeedback
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ArtifactNames
import com.comfypilot.core.api.ImageUpload
import com.comfypilot.core.api.ImageUploadResult
import com.comfypilot.core.api.ViewRequest
import com.comfypilot.core.net.PreviewFrame
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskProgress
import com.comfypilot.core.task.TaskState
import com.comfypilot.core.workflow.BuildProblem
import com.comfypilot.core.workflow.PromptBuild
import com.comfypilot.core.workflow.WorkflowDecision
import com.comfypilot.core.workflow.WorkflowDecisions
import com.comfypilot.core.workflow.WorkflowDocument
import com.comfypilot.core.workflow.WorkflowField
import com.comfypilot.core.workflow.WorkflowGraph
import com.comfypilot.core.workflow.WorkflowNodeView
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowDetailScreen(
    state: SessionState,
    onBack: () -> Unit,
    onTasks: () -> Unit,
    onSubmit: (String, String?, String?) -> Unit,
    onCancel: (String) -> Unit,
    onSetNodeBypassed: (Int, Boolean) -> Unit,
) {
    val document = state.workflow.document

    SpectrumScaffold(
        topBar = {
            SpectrumTopBar(
                title = document?.name?.takeIf { it.isNotBlank() } ?: "工作流",
                onBack = onBack,
                actions = { TopBarAction(text = "任务", onClick = onTasks) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                document == null && state.workflow.loadingPath != null -> LoadingBody()
                document == null -> ErrorBody(
                    message = state.workflow.error ?: "没有选中的工作流",
                    onBack = onBack,
                )

                else -> DetailBody(
                    document = document,
                    state = state,
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                    onTasks = onTasks,
                    onSetNodeBypassed = onSetNodeBypassed,
                )
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    val c = Spectrum.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(12.dp))
        Text("正在读取工作流…", color = c.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorBody(message: String, onBack: () -> Unit) {
    val c = Spectrum.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard {
            Text("打开失败", color = c.err, fontSize = 10.sp, fontFamily = SpectrumMono)
            Spacer(Modifier.height(6.dp))
            Text(message, color = c.textMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        GhostButton(text = "返回列表", onClick = onBack, modifier = Modifier.width(120.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBody(
    document: WorkflowDocument,
    state: SessionState,
    onSubmit: (String, String?, String?) -> Unit,
    onCancel: (String) -> Unit,
    onTasks: () -> Unit,
    onSetNodeBypassed: (Int, Boolean) -> Unit,
) {
    val c = Spectrum.colors
    var enabledGraphOverride by remember(document.path) { mutableStateOf<Set<String>?>(null) }
    val context = LocalContext.current
    val pinnedNodeStore = remember(context) { PinnedNodeStore(context.applicationContext) }
    var pinnedNodeIds by remember(document.path) {
        mutableStateOf(pinnedNodeStore.load(document.path))
    }
    val noteStore = remember { NodeNoteStore(context) }
    var nodeNotes by remember(document.path) {
        mutableStateOf(noteStore.load(document.path))
    }
    var noteTarget by remember { mutableStateOf<WorkflowNodeView?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var overrides by remember(document.path) {
        mutableStateOf<Map<Int, Map<String, JsonElement>>>(emptyMap())
    }

    // 单节点旁路开关存在会话状态里：Activity 重建（配置刷新）后仍然保留，
    // 但打开另一个工作流时由共享层清空。
    val bypassedNodeIds = state.bypassedNodeIds
    val enabledNodeIds = state.enabledNodeIds
    // 节点图、字段和待加载定义都由共享层决定；这里只缓存与字段值无关的部分。
    val analysis = remember(
        document,
        state.nodeInfo,
        enabledGraphOverride,
        state.nodeInfoRequested,
        bypassedNodeIds,
        enabledNodeIds,
    ) {
        WorkflowDecisions.analyze(
            document = document,
            definitions = state.nodeInfo,
            requestedGraphIds = enabledGraphOverride,
            nodeInfoRequested = state.nodeInfoRequested,
            bypassedNodeIds = bypassedNodeIds,
            enabledNodeIds = enabledNodeIds,
        )
    }
    val graphs = analysis.graphs
    val selectedGraphIds = analysis.selectedGraphIds
    val fields = analysis.fields
    val disabledNodeIds = analysis.disabledNodeIds
    val showGraphPicker = graphs.size > 1 || (graphs.size == 1 && !graphs[0].enabled)
    // 置顶且会提交的节点最前；其余节点按工作流原文顺序排列。
    // 单节点开关不参与排序：关掉一个节点不会让它跳到列表底部。
    val orderedNodes = remember(analysis, pinnedNodeIds) {
        WorkflowDecisions.orderNodes(analysis.fields.nodes, pinnedNodeIds)
    }
    // 节点图分组：父图下面挂它的辅助子图（image-2..8、提示词 PE 组）。
    // 启用中的节点图排在前面，未启用的整组后移；组内顺序仍跟主机一致。
    // 子图默认收起，展开由用户决定；不属于任何图组的节点单独放到末尾。
    val graphSections = remember(analysis, orderedNodes, selectedGraphIds) {
        val childrenByParent = analysis.graphs.filter { it.parentId != null }.groupBy { it.parentId }
        val (enabledParents, disabledParents) = analysis.graphs
            .filter { it.parentId == null }
            .partition { it.id in selectedGraphIds }
        (enabledParents + disabledParents)
            .map { parent ->
                GraphSection(
                    graph = parent,
                    nodes = orderedNodes.filter { it.id in parent.nodeIds },
                    children = childrenByParent[parent.id].orEmpty().map { child ->
                        GraphChild(
                            graph = child,
                            nodes = orderedNodes.filter { it.id in child.nodeIds },
                        )
                    },
                )
            }
    }
    val restNodes = remember(analysis, orderedNodes) {
        val claimed = analysis.graphs.flatMapTo(mutableSetOf()) { it.nodeIds }
        orderedNodes.filter { it.id !in claimed }
    }
    var tab by remember(document.path) { mutableStateOf(0) }
    var expandedChildGraphs by remember(document.path) { mutableStateOf<Set<String>>(emptySet()) }
    var restExpanded by remember(document.path) { mutableStateOf(false) }
    var expandedOverride by remember(document.path) { mutableStateOf<Set<Int>?>(null) }
    // 关闭节点前的确认弹窗：只有共享层判定 breaking 时才会出现。
    var bypassConfirm by remember(document.path) { mutableStateOf<BypassConfirm?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    var sheetQuery by remember { mutableStateOf("") }
    var sheetTraits by remember { mutableStateOf<Set<NodeTrait>>(emptySet()) }
    var blockedProblems by remember { mutableStateOf<List<BuildProblem>?>(null) }
    var pendingReady by remember { mutableStateOf<PromptBuild.Ready?>(null) }
    var dismissedFailureAt by remember(document.path) { mutableStateOf(Long.MIN_VALUE) }
    // 提交成功后按钮短暂显示「已提交 ✓」，比 toast 更贴近操作位置。
    var submitFlash by remember { mutableStateOf(false) }
    // 节点图预览：只读浮层，从底栏入口打开。
    var graphOpen by remember(document.path) { mutableStateOf(false) }
    // 从节点图点「查看参数」后要定位的节点 id（滚动完成即清空）。
    var jumpNodeId by remember(document.path) { mutableStateOf<Int?>(null) }
    LaunchedEffect(submitFlash) {
        if (submitFlash) {
            kotlinx.coroutines.delay(1_200)
            submitFlash = false
        }
    }

    // 只显示当前工作流的失败；旧任务没有 workflowPath，宁可不在详情页提示，
    // 也不要把它当成"这个工作流"的错误。
    val latestFailure = state.tasks.values
        .filter { it.state is TaskState.Failed && it.workflowPath == document.path }
        .maxByOrNull { it.updatedAtMs }

    val autoExpanded = remember(fields, pinnedNodeIds) {
        fields.nodes
            .filter { node ->
                node.reachable &&
                    (node.id in pinnedNodeIds || node.fields.any { it.common && it.widget && !it.linked })
            }
            .map { it.id }
            .toSet()
    }
    val expanded = expandedOverride ?: autoExpanded
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 图启停会改变可达节点和置顶顺序；沿用旧滚动位置会让列表停在已被挤到
    // 末尾的节点上，看起来像置顶仍然生效。切图后回到输入区顶部。
    LaunchedEffect(selectedGraphIds) {
        if (orderedNodes.isNotEmpty()) listState.scrollToItem(0)
    }

    val pendingClasses = analysis.pendingNodeClassCount

    // 与鸿蒙侧一致：输入变化后短暂延迟再重算，避免每敲一个字就重建整张 prompt。
    // 这里只用于按钮可用性和按钮上方的阻断原因；真正提交时 submit() 会再算一次，
    // 防止用户在 120ms 窗口内抢点导致提交旧值。
    var liveDecision by remember(document.path) { mutableStateOf<WorkflowDecision?>(null) }
    LaunchedEffect(analysis, overrides) {
        delay(120)
        liveDecision = withContext(Dispatchers.Default) {
            WorkflowDecisions.decide(analysis, overrides = overrides)
        }
    }
    // 节点图或节点定义变化后，旧决策不能继续代表当前工作流。
    val live = liveDecision?.takeIf { it.analysis === analysis }
    val canSubmit = pendingClasses == 0 && live?.canSubmit == true
    val blockReason = analysis.pendingBlockReason ?: when {
        live == null -> "正在校验工作流…"
        else -> live.blockReason
    }

    fun applyOverride(nodeId: Int, inputName: String, value: JsonElement) {
        val forNode = overrides[nodeId].orEmpty() + (inputName to value)
        overrides = overrides + (nodeId to forNode)
    }

    fun toggleNode(nodeId: Int) {
        val current = expandedOverride ?: autoExpanded
        expandedOverride = if (nodeId in current) current - nodeId else current + nodeId
    }

    fun togglePin(nodeId: Int) {
        val wasPinned = nodeId in pinnedNodeIds
        val next = if (wasPinned) pinnedNodeIds - nodeId else pinnedNodeIds + nodeId
        pinnedNodeIds = next
        pinnedNodeStore.save(document.path, next)
        // 置顶/取消置顶都会改变可达节点的位置。新置顶时展开该节点，
        // 两种操作都把列表带回顶部，避免视口跟着节点落到新位置。
        if (fields.nodes.firstOrNull { it.id == nodeId }?.reachable == true) {
            if (!wasPinned) {
                expandedOverride = (expandedOverride ?: autoExpanded) + nodeId
            }
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    fun toggleGraph(graph: WorkflowGraph) {
        val next = selectedGraphIds.toMutableSet()
        if (!next.add(graph.id)) {
            next.remove(graph.id)
            // 关闭父图时一起关掉它的辅助图组，避免隐藏的选择继续影响提交。
            graphs.filter { it.parentId == graph.id }.forEach { next.remove(it.id) }
        }
        enabledGraphOverride = next
        expandedOverride = null
    }

    /**
     * 节点开关：关闭前先做共享层的影响探测；只有会破坏工作流时才弹确认。
     * 文件里本来静音/旁路的节点显示为关闭，点一下就是“启用”，不需要再探测。
     */
    fun toggleBypass(node: WorkflowNodeView) {
        if (!node.enabled) {
            onSetNodeBypassed(node.id, false)
            return
        }
        scope.launch {
            val impact = withContext(Dispatchers.Default) {
                WorkflowDecisions.probeNodeDisable(
                    analysis = analysis,
                    nodeId = node.id,
                    overrides = overrides,
                )
            }
            if (impact.breaking) {
                bypassConfirm = BypassConfirm(node = node, reason = impact.reason)
            } else {
                onSetNodeBypassed(node.id, true)
            }
        }
    }

    // 上传只服务 LoadImage 的 image 控件：先传服务器，再用返回的文件名覆盖控件值。
    val uploader = remember { OkHttpHttpTransport() }
    var uploadTarget by remember { mutableStateOf<WorkflowField?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadPreviews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = uploadTarget
        uploadTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val lease = state.lease
        if (lease == null) {
            uploadError = "未连接 ComfyUI"
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            uploading = true
            uploadError = null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "image.png"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读不到图片内容")
                    name to bytes
                }
            }
            if (loaded.isFailure) {
                uploadError = loaded.exceptionOrNull()?.message ?: "读取图片失败"
                uploading = false
                return@launch
            }

            val (filename, bytes) = loaded.getOrThrow()
            when (
                val result = ImageUpload.upload(
                    http = uploader,
                    baseUrl = lease.baseUrl,
                    filename = filename,
                    bytes = bytes,
                )
            ) {
                is ImageUploadResult.Success -> {
                    val preview = withContext(Dispatchers.IO) { decodePreview(bytes) }
                    if (preview != null) {
                        uploadPreviews = uploadPreviews + ("${target.nodeId}:${target.inputName}" to preview)
                    }
                    applyOverride(
                        target.nodeId,
                        target.inputName,
                        JsonPrimitive(result.reference),
                    )
                }

                is ImageUploadResult.Failure -> uploadError = result.message
            }
            uploading = false
        }
    }

    fun pickImage(field: WorkflowField) {
        if (uploading) return
        if (state.lease == null) {
            uploadError = "未连接 ComfyUI"
            return
        }
        uploadTarget = field
        picker.launch("image/*")
    }

    fun submit() {
        // 和鸿蒙侧一致：每次提交给保存节点追加唯一后缀，避免工作流前缀不唯一时
        // （例如 ComfyUI 不支持的 %date:...%）后一次产物覆盖前一次。
        val decision = WorkflowDecisions.decide(
            analysis = analysis,
            overrides = overrides,
            runToken = UUID.randomUUID().toString().replace("-", "").take(10),
        )
        if (!decision.canSubmit) {
            blockedProblems = decision.problems.ifEmpty {
                listOf(BuildProblem(message = decision.blockReason ?: "工作流还没有就绪"))
            }
            return
        }
        when (val result = decision.build) {
            is PromptBuild.Ready -> {
                if (decision.warnings.isEmpty()) {
                    onSubmit(result.prompt.toString(), decision.name, decision.path)
                    submitFlash = true
                    tab = 1
                } else {
                    pendingReady = result
                }
            }

            is PromptBuild.Blocked -> blockedProblems = decision.problems
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            ServerStatsStrip(
                baseUrl = state.lease?.baseUrl,
                monitor = state.monitor,
            )
        }

        if (latestFailure != null && latestFailure.updatedAtMs > dismissedFailureAt) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                GlassCard(padding = PaddingValues(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Dot(color = c.err)
                        Text(
                            text = "COMFYUI 报错",
                            color = c.err,
                            fontSize = 9.5.sp,
                            fontFamily = SpectrumMono,
                            letterSpacing = 0.8.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        TopBarAction(text = "关闭", onClick = {
                            dismissedFailureAt = latestFailure.updatedAtMs
                        })
                    }
                    Text(
                        text = latestFailure.error ?: "任务失败",
                        color = c.textMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TopBarAction(text = "查看任务", onClick = onTasks)
                }
            }
        }

        if (showGraphPicker) {
            val parents = graphs.filter { it.outputNodeIds.isNotEmpty() }
            val childrenByParent = graphs.filter { it.parentId != null }.groupBy { it.parentId }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                parents.forEach { parent ->
                    GraphSelector(
                        graph = parent,
                        children = childrenByParent[parent.id].orEmpty(),
                        enabled = parent.id in selectedGraphIds,
                        selectedIds = selectedGraphIds,
                        onToggle = { toggleGraph(it) },
                    )
                }
            }
        }

        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            SpectrumTabs(
                labels = listOf("输入 (${fields.nodes.sumOf { it.fields.size }})", "预览"),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }

        if (pendingClasses > 0) {
            InlineNote("正在加载节点定义：还差 $pendingClasses 个类")
        }
        if (uploading) {
            InlineNote("正在上传图片…")
        }
        uploadError?.let { message ->
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
                Text(
                    text = "上传失败：$message",
                    color = c.err,
                    fontSize = 11.sp,
                )
                TopBarAction(text = "知道了", onClick = { uploadError = null })
            }
        }
        if (fields.problems.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                GlassCard(padding = PaddingValues(11.dp)) {
                    fields.problems.forEach { problem ->
                        Text(
                            text = problem.format(),
                            color = c.err,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(170)) + slideInHorizontally(tween(200)) { offset -> offset / 14 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "detail-tab",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { current ->
            if (current == 0) {
                InputPane(
                    sections = graphSections,
                    restNodes = restNodes,
                    expanded = expanded,
                    expandedChildGraphs = expandedChildGraphs,
                    restExpanded = restExpanded,
                    overrides = overrides,
                    listState = listState,
            jumpNodeId = jumpNodeId,
            onJumpHandled = { jumpNodeId = null },
                    disabledNodeIds = disabledNodeIds,
                    pinnedNodeIds = pinnedNodeIds,
                    notes = nodeNotes,
                    previews = uploadPreviews,
                    selectedGraphIds = selectedGraphIds,
                    onEditNote = { target ->
                        noteTarget = target
                        noteDraft = nodeNotes[target.id].orEmpty()
                    },
                    onToggle = ::toggleNode,
                    onTogglePin = ::togglePin,
                    onToggleBypass = ::toggleBypass,
                    onToggleChildGraph = { id ->
                        expandedChildGraphs = if (id in expandedChildGraphs) {
                            expandedChildGraphs - id
                        } else {
                            expandedChildGraphs + id
                        }
                    },
                    onToggleGraph = ::toggleGraph,
                    onToggleRest = { restExpanded = !restExpanded },
                    onOverride = ::applyOverride,
                    onPickImage = ::pickImage,
                )
            } else {
                PreviewPane(
                    state = state,
                    workflowPath = document.path,
                    onCancel = onCancel,
                    onTasks = onTasks,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.background.copy(alpha = 0.86f)),
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                blockReason?.let { reason ->
                    Text(
                        text = reason,
                        color = c.err,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GraphIconButton(onClick = { graphOpen = true })
                    GhostButton(
                        text = "全部节点",
                        onClick = { sheetVisible = true },
                        height = 42.dp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${fields.nodes.count { it.reachable }} 个会提交",
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                        textAlign = TextAlign.End,
                    )
                    GradientButton(
                        text = if (submitFlash) "已提交 ✓" else "提交任务",
                        onClick = ::submit,
                        enabled = canSubmit,
                        height = 44.dp,
                    )
                }
            }
        }
    }

    if (graphOpen) {
        NodeGraphDialog(
            raw = document.raw,
            onOpenNode = { nodeId ->
                graphOpen = false
                jumpNodeId = nodeId
                expandedOverride = expanded + nodeId
            },
            // 生效模式为 normal 的节点都算"亮"：Note/Label 这类提示节点虽然不参与执行，
            // 也应该保持正常颜色；只有所在节点图被关闭时才变灰。
            activeNodeIds = fields.nodes.filter { it.mode == 0 }.map { it.id }.toSet(),
            disabledNodeIds = disabledNodeIds,
            onDismiss = { graphOpen = false },
        )
    }

    noteTarget?.let { target ->
        SpectrumDialog(
            title = "节点备注",
            onDismiss = { noteTarget = null },
            confirmText = "保存",
            onConfirm = {
                val label = noteDraft.trim()
                val updated = if (label.isEmpty()) nodeNotes - target.id else nodeNotes + (target.id to label)
                nodeNotes = updated
                noteStore.save(document.path, updated)
                noteTarget = null
            },
            dismissText = "取消",
        ) {
            Text(
                text = "只保存在本机，用于卡片和「全部节点」里的显示名。",
                color = Spectrum.colors.textDim,
                fontSize = 11.sp,
            )
            SpectrumTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                placeholder = target.displayName,
                mono = false,
            )
            if (nodeNotes.containsKey(target.id)) {
                GhostButton(
                    text = "清除备注",
                    onClick = {
                        val updated = nodeNotes - target.id
                        nodeNotes = updated
                        noteStore.save(document.path, updated)
                        noteTarget = null
                    },
                    height = 34.dp,
                )
            }
        }
    }
    bypassConfirm?.let { confirm ->
        SpectrumDialog(
            title = "关闭节点",
            onDismiss = { bypassConfirm = null },
            confirmText = "仍要关闭",
            onConfirm = {
                onSetNodeBypassed(confirm.node.id, true)
                bypassConfirm = null
            },
            dismissText = "取消",
        ) {
            Text(
                text = "关闭该节点可能导致工作流无法运行，是否确认关闭",
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            confirm.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    color = c.err,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
    if (sheetVisible) {
        val sheetNodes = remember(fields.nodes, sheetQuery, sheetTraits, nodeNotes) {
            val typeFilters = sheetTraits - NodeTrait.NeedsInput
            fields.nodes.filter { node ->
                val traits = WorkflowNodeTraits.of(node)
                val typeOk = typeFilters.isEmpty() || typeFilters.any { it in traits }
                val needsOk = NodeTrait.NeedsInput !in sheetTraits || NodeTrait.NeedsInput in traits
                typeOk && needsOk && WorkflowNodeTraits.matches(node, sheetQuery, nodeNotes[node.id])
            }
        }
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.dialog,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "全部节点",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${sheetNodes.size} / ${fields.nodes.size}",
                    color = c.textDim,
                    fontSize = 10.sp,
                    fontFamily = SpectrumMono,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                TopBarAction(text = "关闭", onClick = { sheetVisible = false })
            }
            Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                SpectrumTextField(
                    value = sheetQuery,
                    onValueChange = { sheetQuery = it },
                    placeholder = "搜索名称、类型、#id、备注",
                    mono = false,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    NodeTrait.Image to "图像输入",
                    NodeTrait.Text to "文本输入",
                    NodeTrait.Number to "数值输入",
                    NodeTrait.NeedsInput to "待填写",
                ).forEach { (trait, label) ->
                    SpectrumChip(
                        text = label,
                        selected = trait in sheetTraits,
                        onClick = {
                            sheetTraits = if (trait in sheetTraits) sheetTraits - trait else sheetTraits + trait
                        },
                    )
                }
            }
            LazyColumn(modifier = Modifier.fillMaxHeight(0.66f)) {
                items(sheetNodes, key = { it.id }) { node ->
                    val note = nodeNotes[node.id]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedOverride = (expanded + node.id)
                                tab = 0
                                sheetVisible = false
                                val index = orderedNodes.indexOfFirst { it.id == node.id }
                                if (index >= 0) {
                                    scope.launch { listState.animateScrollToItem(index) }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${node.id} ${note ?: node.displayName}",
                                color = c.text,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (note != null) "${node.type} · ${node.displayName}" else node.type,
                                color = c.textDim,
                                fontSize = 9.5.sp,
                                fontFamily = SpectrumMono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val stateLabel = when {
                            node.missing -> "缺少定义"
                            !node.enabled -> "已关闭"
                            node.id in disabledNodeIds -> "未启用"
                            !node.reachable -> "不会提交"
                            else -> "${node.fields.size} 个输入"
                        }
                        Text(
                            text = stateLabel,
                            color = c.textDim,
                            fontSize = 10.sp,
                            fontFamily = SpectrumMono,
                        )
                    }
                    HorizontalDivider(color = c.border, thickness = 1.dp)
                }
            }
        }
    }

    blockedProblems?.let { problems ->
        SpectrumDialog(
            title = "不能提交",
            onDismiss = { blockedProblems = null },
            confirmText = "知道了",
            onConfirm = { blockedProblems = null },
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                problems.forEach { problem ->
                    Text(
                        text = "· ${problem.format()}",
                        color = c.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }

    pendingReady?.let { ready ->
        SpectrumDialog(
            title = "提交前确认",
            onDismiss = { pendingReady = null },
            confirmText = "仍然提交",
            onConfirm = {
                val json = ready.prompt.toString()
                pendingReady = null
                onSubmit(json, document.name, document.path)
                submitFlash = true
                tab = 1
            },
            dismissText = "取消",
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("以下提示不会阻止提交：", color = c.textDim, fontSize = 11.sp)
                ready.warnings.forEach { warning ->
                    Text(
                        text = "· $warning",
                        color = c.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineNote(text: String) {
    val c = Spectrum.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            color = c.accent,
            strokeWidth = 1.5.dp,
        )
        Text(text = text, color = c.textDim, fontSize = 10.5.sp)
    }
}

@Composable
private fun InputPane(
    sections: List<GraphSection>,
    restNodes: List<WorkflowNodeView>,
    expanded: Set<Int>,
    expandedChildGraphs: Set<String>,
    restExpanded: Boolean,
    overrides: Map<Int, Map<String, JsonElement>>,
    listState: LazyListState,
    jumpNodeId: Int?,
    onJumpHandled: () -> Unit,
    disabledNodeIds: Set<Int>,
    pinnedNodeIds: Set<Int>,
    notes: Map<Int, String>,
    previews: Map<String, Bitmap>,
    selectedGraphIds: Set<String>,
    onToggle: (Int) -> Unit,
    onEditNote: (WorkflowNodeView) -> Unit,
    onTogglePin: (Int) -> Unit,
    onToggleBypass: (WorkflowNodeView) -> Unit,
    onToggleChildGraph: (String) -> Unit,
    onToggleGraph: (WorkflowGraph) -> Unit,
    onToggleRest: () -> Unit,
    onOverride: (Int, String, JsonElement) -> Unit,
    onPickImage: (WorkflowField) -> Unit,
) {
    val c = Spectrum.colors
    val rows = remember(sections, restNodes, expandedChildGraphs, restExpanded, selectedGraphIds) {
        buildList {
            sections.forEachIndexed { index, section ->
                add(
                    InputRow.Header(
                        graph = section.graph,
                        nodeCount = section.nodes.size,
                        submitCount = section.nodes.count { it.reachable },
                        enabled = section.graph.id in selectedGraphIds,
                        first = index == 0,
                    )
                )
                section.nodes.forEach { add(InputRow.NodeRow(it, nested = false)) }
                section.children.forEach { child ->
                    val open = child.graph.id in expandedChildGraphs
                    add(
                        InputRow.ChildRow(
                            graph = child.graph,
                            nodeCount = child.nodes.size,
                            enabled = child.graph.id in selectedGraphIds,
                            expanded = open,
                        )
                    )
                    if (open) child.nodes.forEach { add(InputRow.NodeRow(it, nested = true)) }
                }
            }
            if (restNodes.isNotEmpty()) {
                add(
                    InputRow.RestRow(
                        count = restNodes.size,
                        reachable = restNodes.count { it.reachable },
                        expanded = restExpanded,
                    )
                )
                if (restExpanded) restNodes.forEach { add(InputRow.NodeRow(it, nested = false)) }
            }
        }
    }

    // 从节点图跳过来：展开目标节点并滚动到它那一行。
    LaunchedEffect(jumpNodeId, rows) {
        val target = jumpNodeId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is InputRow.NodeRow && it.node.id == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
        onJumpHandled()
    }

    if (sections.isEmpty() && restNodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            Text(
                text = "这个工作流没有可编辑的节点输入。",
                color = c.textMuted,
                fontSize = 12.5.sp,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            Box(modifier = Modifier.animateItem()) {
            when (row) {
                is InputRow.Header -> SectionHeader(row)
                is InputRow.ChildRow -> ChildGraphRow(
                    row = row,
                    onToggleExpand = { onToggleChildGraph(row.graph.id) },
                    onToggleEnabled = { onToggleGraph(row.graph) },
                )
                is InputRow.RestRow -> RestHeader(row = row, onClick = onToggleRest)
                is InputRow.NodeRow -> NodeInputCard(
                    node = row.node,
                    expanded = row.node.id in expanded,
                    nested = row.nested,
                    overrides = overrides[row.node.id].orEmpty(),
                    graphDisabled = row.node.id in disabledNodeIds,
                    pinned = row.node.id in pinnedNodeIds,
                    label = notes[row.node.id],
                    previews = previews,
                    onToggle = { onToggle(row.node.id) },
                    onTogglePin = { onTogglePin(row.node.id) },
                    onToggleBypass = { onToggleBypass(row.node) },
                    onEditNote = { onEditNote(row.node) },
                    onChange = onOverride,
                    onPickImage = onPickImage,
                )
            }
            }
        }
    }
}

@Composable
private fun GraphSelector(
    graph: WorkflowGraph,
    children: List<WorkflowGraph>,
    enabled: Boolean,
    selectedIds: Set<String>,
    onToggle: (WorkflowGraph) -> Unit,
) {
    val c = Spectrum.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box {
        Row(
            modifier = Modifier
                .clip(shape)
                .then(
                    if (enabled) {
                        Modifier.background(c.gradientBrush())
                    } else {
                        Modifier.background(c.card).border(1.dp, c.border, shape)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggle(graph) }
                    .padding(
                        start = 13.dp,
                        end = if (children.isEmpty()) 13.dp else 9.dp,
                        top = 7.dp,
                        bottom = 7.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = graph.title,
                    color = if (enabled) c.accentInk else c.textMuted,
                    fontSize = 11.5.sp,
                    fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (children.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(if (enabled) c.accentInk.copy(alpha = 0.3f) else c.border),
                )
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { open = !open }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (open) "▴" else "▾",
                        color = if (enabled) c.accentInk.copy(alpha = 0.85f) else c.textDim,
                        fontSize = 9.sp,
                    )
                }
            }
        }
        if (children.isNotEmpty() && open) {
            Popup(
                popupPositionProvider = remember { DropdownBelowAnchor() },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                // 面板从按钮下沿展开：淡入 + 轻微缩放，避免"啪"地出现。
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(150)) }
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.value
                        val scale = 0.96f + 0.04f * appear.value
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                ) {
                    DropdownPanel(
                        title = graph.title,
                        children = children,
                        selectedIds = selectedIds,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownPanel(
    title: String,
    children: List<WorkflowGraph>,
    selectedIds: Set<String>,
    onToggle: (WorkflowGraph) -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .width(256.dp)
            .shadow(
                elevation = 22.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.30f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(c.dialog)
            .border(1.dp, c.border, shape)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = "子节点图 · $title",
            color = c.textDim,
            fontSize = 9.sp,
            fontFamily = SpectrumMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .heightIn(max = 290.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            children.forEachIndexed { index, child ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(1.dp)
                            .background(c.border.copy(alpha = 0.7f)),
                    )
                }
                GraphMenuRow(
                    title = child.title,
                    subtitle = "${child.nodeIds.size} 个节点 · 参考图槽位",
                    checked = child.id in selectedIds,
                    onCheck = { onToggle(child) },
                )
            }
        }
    }
}

@Composable
private fun GraphMenuRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheck: () -> Unit,
    indent: Boolean = true,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(shape)
            .background(if (checked) c.accentSoft else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCheck,
            )
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.width(18.dp).height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) CheckGlyph(c.ok)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (indent) "└ $title" else title,
                color = if (checked) c.text else c.textMuted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = c.textDim,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CheckGlyph(color: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.17f
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.55f),
            end = Offset(w * 0.42f, h * 0.80f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.80f),
            end = Offset(w * 0.86f, h * 0.24f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private class DropdownBelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val below = anchorBounds.bottom + 6
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - 6 - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}
private data class GraphSection(
    val graph: WorkflowGraph,
    val nodes: List<WorkflowNodeView>,
    val children: List<GraphChild>,
)

/** 等待用户确认的"关闭节点"操作，以及共享层给出的破坏原因。 */
private data class BypassConfirm(
    val node: WorkflowNodeView,
    val reason: String?,
)

private data class GraphChild(
    val graph: WorkflowGraph,
    val nodes: List<WorkflowNodeView>,
)

private sealed interface InputRow {
    val key: String

    data class Header(
        val graph: WorkflowGraph,
        val nodeCount: Int,
        val submitCount: Int,
        val enabled: Boolean,
        val first: Boolean,
    ) : InputRow {
        override val key: String = "h:${graph.id}"
    }

    data class ChildRow(
        val graph: WorkflowGraph,
        val nodeCount: Int,
        val enabled: Boolean,
        val expanded: Boolean,
    ) : InputRow {
        override val key: String = "c:${graph.id}"
    }

    data class RestRow(val count: Int, val reachable: Int, val expanded: Boolean) : InputRow {
        override val key: String = "h:rest"
    }

    data class NodeRow(val node: WorkflowNodeView, val nested: Boolean) : InputRow {
        override val key: String = "n:${node.id}"
    }
}

@Composable
private fun SectionHeader(row: InputRow.Header) {
    val c = Spectrum.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = if (row.first) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.graph.title,
            color = if (row.enabled) c.textMuted else c.textDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = buildString {
                append("${row.nodeCount} 个节点")
                if (row.enabled) append(" · ${row.submitCount} 个会提交") else append(" · 未启用")
            },
            color = c.textDim,
            fontSize = 9.sp,
            fontFamily = SpectrumMono,
        )
    }
}

@Composable
private fun ChildGraphRow(
    row: InputRow.ChildRow,
    onToggleExpand: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp)
            .clip(shape)
            .background(c.card.copy(alpha = 0.5f))
            .border(1.dp, c.border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleExpand,
            )
            .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (row.enabled) "✓ └ ${row.graph.title}" else "└ ${row.graph.title}",
                color = if (row.enabled) c.text else c.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (row.enabled) {
                    "${row.nodeCount} 个节点 · 会提交"
                } else {
                    "${row.nodeCount} 个节点 · 不会提交"
                },
                color = c.textDim,
                fontSize = 9.sp,
                fontFamily = SpectrumMono,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = if (row.enabled) "已启用" else "未启用",
            color = if (row.enabled) c.ok else c.textDim,
            fontSize = 9.5.sp,
            fontFamily = SpectrumMono,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (row.enabled) c.okSoft else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (row.enabled) c.ok.copy(alpha = 0.35f) else c.border,
                    shape = RoundedCornerShape(7.dp),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleEnabled,
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Text(
            text = if (row.expanded) "▾" else "▸",
            color = c.textDim,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun RestHeader(row: InputRow.RestRow, onClick: () -> Unit) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(c.card.copy(alpha = 0.4f))
            .border(1.dp, c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "其他节点",
                color = c.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (row.reachable > 0) {
                    "${row.count} 个节点 · ${row.reachable} 个会提交"
                } else {
                    "${row.count} 个节点 · 都没连到输出，不会提交"
                },
                color = c.textDim,
                fontSize = 9.sp,
                fontFamily = SpectrumMono,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = if (row.expanded) "▾" else "▸",
            color = c.textDim,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun NodeInputCard(
    node: WorkflowNodeView,
    expanded: Boolean,
    nested: Boolean,
    overrides: Map<String, JsonElement>,
    graphDisabled: Boolean,
    pinned: Boolean,
    label: String?,
    previews: Map<String, Bitmap>,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleBypass: () -> Unit,
    onEditNote: () -> Unit,
    onChange: (Int, String, JsonElement) -> Unit,
    onPickImage: (WorkflowField) -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(18.dp)
    val enabled = node.enabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (nested) 10.dp else 0.dp)
            .clip(shape)
            .background(if (enabled) c.card else c.card.copy(alpha = 0.5f))
            .border(1.dp, if (enabled) c.border else c.border.copy(alpha = 0.55f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(start = 13.dp, end = 9.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "#${node.id}",
                        color = c.textDim,
                        fontSize = 11.sp,
                        fontFamily = SpectrumMono,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        text = label ?: node.displayName,
                        color = if (enabled) c.text else c.textDim,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (label != null) {
                        "${node.type} · ${node.displayName}"
                    } else {
                        "${node.type} · ${node.fields.size} 个输入"
                    },
                    color = c.textDim,
                    fontSize = 9.5.sp,
                    fontFamily = SpectrumMono,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (node.needsInput) {
                SpectrumPill(text = "待填写", tone = PillTone.Err)
            }
            NodeIconButton(glyph = NodeGlyph.Note, active = label != null, onClick = onEditNote)
            NodeIconButton(glyph = NodeGlyph.Pin, active = pinned, onClick = onTogglePin)
            NodeIconButton(
                glyph = if (expanded) NodeGlyph.ChevronUp else NodeGlyph.ChevronDown,
                active = false,
                onClick = onToggle,
            )
        }

        val note = when {
            node.missing -> "服务端没有该节点的定义，不能调参；提交会被拒绝。"
            !enabled -> "已关闭，提交时会跳过该节点。"
            graphDisabled -> "节点图未启用，不会被提交。"
            !node.reachable -> "未连接到输出节点，不会被提交。"
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                color = if (node.missing) c.err else c.textDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 2.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(160)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(110)),
        ) {
            Column(
                modifier = Modifier.padding(start = 13.dp, end = 13.dp, bottom = 13.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                node.fields.forEachIndexed { index, field ->
                    StaggeredIn(index = index) {
                    FieldEditor(
                        field = field,
                        value = overrides[field.inputName] ?: field.value,
                        editable = node.reachable && enabled,
                        preview = previews["${node.id}:${field.inputName}"],
                        onPickImage = if (
                            node.reachable && enabled &&
                            field.nodeType == "LoadImage" && field.inputName == "image"
                        ) {
                            { onPickImage(field) }
                        } else {
                            null
                        },
                        onChange = { value -> onChange(node.id, field.inputName, value) },
                    )
                    }
                }
                if (node.fields.isEmpty()) {
                    Text("没有可调参数。", color = c.textDim, fontSize = 11.sp)
                }
                // 启停放在展开内容的最下方：属于该节点的操作，不挤占标题行。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (enabled) "关闭该节点" else "启用该节点",
                        color = c.textDim,
                        fontSize = 10.5.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    BypassIconButton(enabled = enabled, onToggle = onToggleBypass)
                }
            }
        }
    }
}

@Composable
private fun BypassIconButton(enabled: Boolean, onToggle: () -> Unit) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(10.dp)
    // 开启时用强调色；关闭时用暗色 + 55% 透明度，和卡片整体的“已关闭”观感一致。
    val tint = if (enabled) c.accent else c.textDim.copy(alpha = 0.55f)
    val fill = if (enabled) c.accentSoft else Color.Transparent
    val rim = if (enabled) c.accent.copy(alpha = 0.45f) else c.border.copy(alpha = 0.7f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, rim, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (enabled) "关闭节点" else "启用节点",
                onClick = onToggle,
            )
            .semantics {
                contentDescription = if (enabled) "启用状态" else "已关闭"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.11f
            // 电源图标：顶部留口的圆环 + 一根竖杆，不依赖 material-icons-extended。
            drawArc(
                color = tint,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(w * 0.18f, h * 0.26f),
                size = Size(w * 0.64f, h * 0.64f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawLine(
                color = tint,
                start = Offset(w * 0.5f, h * 0.12f),
                end = Offset(w * 0.5f, h * 0.46f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun Hero(
    modifier: Modifier = Modifier,
    tag: String? = null,
    live: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(c.dialog)
            .border(1.dp, c.borderStrong, shape),
    ) {
        SpectrumArt(modifier = Modifier.matchParentSize())
        content()
        if (tag != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.background.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (live) Dot(color = c.ok, size = 5.dp)
                Text(
                    text = tag,
                    color = if (live) c.ok else c.heroText.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontFamily = SpectrumMono,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun PreviewPane(
    state: SessionState,
    workflowPath: String,
    onCancel: (String) -> Unit,
    onTasks: () -> Unit,
) {
    val active = state.tasks.values
        .filter { it.isActive && it.workflowPath == workflowPath }
        .maxByOrNull { it.createdAtMs }
    if (active != null) {
        ActivePreview(task = active, preview = state.preview, onCancel = onCancel)
        return
    }

    val latest = state.tasks.values
        .filter { it.workflowPath == workflowPath }
        .maxByOrNull { it.updatedAtMs }
    if (latest == null) {
        EmptyPreview()
        return
    }
    var showArtifacts by remember(latest.id) { mutableStateOf(false) }
    // 从视频首帧进产物弹窗时，直接停在该视频并自动播放。
    var autoVideo by remember(latest.id) { mutableStateOf(false) }
    val c = Spectrum.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val output = latest.outputs.firstOrNull { isImageFile(it.filename) }
        val baseUrl = state.lease?.baseUrl
        val imageUrl = output?.let { out ->
            baseUrl?.let { base ->
                ApiPaths.viewUrl(base, ViewRequest(out.filename, out.subfolder, out.type))
            }
        }
        val videoOutput = latest.outputs.firstOrNull { ArtifactNames.isVideo(it.filename) }
        val videoUrl = videoOutput?.let { out ->
            baseUrl?.let { base ->
                ApiPaths.viewUrl(base, ViewRequest(out.filename, out.subfolder, out.type))
            }
        }
        Hero(modifier = Modifier.fillMaxWidth().height(420.dp), tag = "RESULT") {
            if (imageUrl != null) {
                RemoteOutputImage(url = imageUrl, modifier = Modifier.matchParentSize())
            } else if (videoUrl != null) {
                // 与鸿蒙一致：首帧 + 播放按钮，点击打开产物弹窗并直接播放。
                VideoPosterFrame(
                    url = videoUrl,
                    label = "点击播放",
                    modifier = Modifier.matchParentSize(),
                    onClick = {
                        autoVideo = true
                        showArtifacts = true
                    },
                )
            } else {
                Text(
                    text = if (latest.state is TaskState.Failed) "任务失败" else "没有可预览的图片",
                    color = c.heroText.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = taskStateLabel(latest.state),
                color = c.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${latest.progress?.percent ?: 0}",
                style = c.gradientStyle().copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                ),
            )
            Text(
                text = "%",
                color = c.textDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }

        if (latest.state is TaskState.Failed) {
            Text(
                text = "任务失败：${(latest.state as TaskState.Failed).message ?: "未知原因"}",
                color = c.err,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else if (latest.outputs.isNotEmpty() && imageUrl == null) {
            Text(
                text = "产物：${latest.outputs.joinToString("、") { it.filename }}",
                color = c.textDim,
                fontSize = 10.5.sp,
                fontFamily = SpectrumMono,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (latest.outputs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GhostButton(text = "查看产物", onClick = { showArtifacts = true }, height = 38.dp)
                GhostButton(text = "任务页查看全部", onClick = onTasks, height = 38.dp)
            }
        }
    }

    if (showArtifacts) {
        ArtifactDialog(
            task = latest,
            baseUrl = state.lease?.baseUrl,
            onDismiss = {
                showArtifacts = false
                autoVideo = false
            },
            initialOutput = if (autoVideo) latest.outputs.firstOrNull { ArtifactNames.isVideo(it.filename) } else null,
            autoStartVideo = autoVideo,
        )
    }
}

@Composable
private fun ActivePreview(
    task: PromptTask,
    preview: PreviewFrame?,
    onCancel: (String) -> Unit,
) {
    val c = Spectrum.colors
    val frame = preview?.takeIf { it.promptId == null || it.promptId == task.promptId }
    val progress = task.progress
    val percent = progress?.percent ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(
            modifier = Modifier.fillMaxWidth().height(420.dp),
            tag = if (task.state is TaskState.Running) "LIVE" else "QUEUED",
            live = task.state is TaskState.Running,
        ) {
            if (frame != null) {
                LivePreviewImage(bytes = frame.image, modifier = Modifier.matchParentSize())
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (task.state is TaskState.Queued) "排队中" else "等待采样画面…",
                        color = c.heroText.copy(alpha = 0.85f),
                        fontSize = 12.5.sp,
                    )
                    if (task.state is TaskState.Running) {
                        Text(
                            text = "若一直不出现，确认 ComfyUI 以 --preview-method auto 启动",
                            color = c.heroText.copy(alpha = 0.55f),
                            fontSize = 9.5.sp,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = taskStateLabel(task.state),
                    color = c.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (progress != null && progress.max > 0) {
                    Text(
                        text = progressStepLabel(progress),
                        color = c.textDim,
                        fontSize = 10.sp,
                        fontFamily = SpectrumMono,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                text = "$percent",
                style = c.gradientStyle().copy(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                ),
            )
            Text(
                text = "%",
                color = c.textDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        SpectrumProgress(fraction = percent / 100f, height = 8.dp)

        task.promptId?.let { promptId ->
            GhostButton(
                text = "取消任务",
                onClick = { onCancel(promptId) },
                height = 38.dp,
            )
        }
    }
}

private fun progressStepLabel(progress: TaskProgress): String {
    fun number(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString()
    }
    val node = progress.node?.let { "#$it · " } ?: ""
    return "$node${number(progress.value)}/${number(progress.max)}"
}

@Composable
private fun EmptyPreview() {
    val c = Spectrum.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(modifier = Modifier.fillMaxWidth().weight(1f), tag = "IDLE") {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "还没有任务",
                    color = c.heroText.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "提交后在这里显示采样画面和结果",
                    color = c.heroText.copy(alpha = 0.55f),
                    fontSize = 10.5.sp,
                )
            }
        }
        Text(
            text = "这个工作流还没有任务。",
            color = c.textDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun FieldEditor(
    field: WorkflowField,
    value: JsonElement?,
    editable: Boolean,
    preview: Bitmap? = null,
    onPickImage: (() -> Unit)? = null,
    onChange: (JsonElement) -> Unit,
) {
    val c = Spectrum.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.label ?: field.inputName,
                color = c.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = field.type,
                color = c.textDim,
                fontSize = 8.5.sp,
                fontFamily = SpectrumMono,
                letterSpacing = 0.6.sp,
            )
        }

        when {
            field.linked -> Text("由连线提供", color = c.textDim, fontSize = 11.sp)
            !field.widget -> Text(
                text = if (field.required) {
                    "需要连线；当前未连接，提交会被拒绝。"
                } else {
                    "可选连线；当前未连接。"
                },
                color = if (field.required) c.err else c.textDim,
                fontSize = 11.sp,
            )

            !editable -> Text(
                text = "${valueText(value)}（不在提交范围内）",
                color = c.textDim,
                fontSize = 11.sp,
            )

            // 图片控件只允许自己上传：不再列出服务器 input 目录里的图片，
            // 这里只回显当前文件名（可能来自刚上传的图片）。
            onPickImage != null -> Text(
                text = valueText(value).ifEmpty { "未选择图片" },
                color = if (valueText(value).isEmpty()) c.textDim else c.text,
                fontSize = 12.sp,
                fontFamily = SpectrumMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            field.options.isNotEmpty() -> OptionPicker(                options = field.options,
                current = valueText(value),
                onSelect = { onChange(JsonPrimitive(it)) },
            )

            field.type == "BOOLEAN" -> Switch(
                checked = (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onChange(JsonPrimitive(it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.accentInk,
                    checkedTrackColor = c.accent,
                    checkedBorderColor = c.accent,
                    uncheckedThumbColor = c.textMuted,
                    uncheckedTrackColor = c.barTrack,
                    uncheckedBorderColor = c.borderStrong,
                ),
            )

            field.type == "INT" || field.type == "FLOAT" -> NumberField(
                isFloat = field.type == "FLOAT",
                value = value,
                onChange = onChange,
            )

            else -> {
                // 提示词这类长文本必须换行显示：单行输入框看不到整段内容，也没法挑错。
                val longText = field.type == "STRING"
                SpectrumTextField(
                    value = valueText(value),
                    onValueChange = { onChange(JsonPrimitive(it)) },
                    placeholder = "",
                    mono = !longText,
                    singleLine = !longText,
                    minLines = if (longText) 3 else 1,
                )
            }
        }

        if (field.required && field.widget && !field.linked && value == null) {
            Text(
                text = "必填",
                color = c.err,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
            )
        }

        if (onPickImage != null && editable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (preview != null) {
                    val previewShape = RoundedCornerShape(10.dp)
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "已上传的图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(previewShape)
                            .border(1.dp, c.border, previewShape),
                    )
                }
                GhostButton(
                    text = if (preview == null) "从相册选择" else "更换图片",
                    onClick = onPickImage,
                    height = 36.dp,
                )
            }
        }
    }
}

@Composable
private fun OptionPicker(
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    val c = Spectrum.colors
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.border, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.ifBlank { "选择…" },
                color = c.text,
                fontSize = 11.5.sp,
                fontFamily = SpectrumMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = "▾", color = c.textDim, fontSize = 10.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = c.dialog,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            color = c.text,
                            fontSize = 12.sp,
                            fontFamily = SpectrumMono,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    isFloat: Boolean,
    value: JsonElement?,
    onChange: (JsonElement) -> Unit,
) {
    val initial = valueText(value)
    var text by remember { mutableStateOf(initial) }
    LaunchedEffect(value) {
        val next = valueText(value)
        val same = if (isFloat) {
            next.toDoubleOrNull() == text.toDoubleOrNull()
        } else {
            next.toLongOrNull() == text.toLongOrNull()
        }
        if (!same) text = next
    }

    SpectrumTextField(
        value = text,
        onValueChange = { input ->
            text = input
            val parsed = if (isFloat) {
                input.toDoubleOrNull()?.let { JsonPrimitive(it) }
            } else {
                input.toLongOrNull()?.let { JsonPrimitive(it) }
            }
            if (parsed != null) onChange(parsed)
        },
        placeholder = "",
        keyboardType = if (isFloat) KeyboardType.Decimal else KeyboardType.Number,
    )
}

private fun valueText(value: JsonElement?): String = when (value) {
    null -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

/** 上传后的本地预览：只解码一张缩略图，不把原图整张留在内存里。 */
private fun decodePreview(bytes: ByteArray, maxSide: Int = 512): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

private enum class NodeGlyph { Pin, Note, ChevronDown, ChevronUp }

/** 节点卡片上的小图标按钮：备注、置顶、展开。手绘线条，不引入图标包。 */
@Composable
private fun NodeIconButton(
    glyph: NodeGlyph,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(10.dp)
    val tint by animateColorAsState(
        targetValue = if (active) c.accent else c.textMuted,
        animationSpec = tween(160),
        label = "node-icon-tint",
    )
    val fill by animateColorAsState(
        targetValue = if (active) c.accentSoft else Color.Transparent,
        animationSpec = tween(160),
        label = "node-icon-fill",
    )
    val rim by animateColorAsState(
        targetValue = if (active) c.accent.copy(alpha = 0.4f) else c.border,
        animationSpec = tween(160),
        label = "node-icon-rim",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(30.dp)
            .pressFeedback(interaction, pressedScale = 0.9f)
            .clip(shape)
            .background(fill)
            .border(1.dp, rim, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.12f
            when (glyph) {
                NodeGlyph.Pin -> {
                    // 顶栏 + 向下箭头：把节点放到最上面。
                    drawLine(tint, Offset(w * 0.26f, h * 0.20f), Offset(w * 0.74f, h * 0.20f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.5f, h * 0.30f), Offset(w * 0.5f, h * 0.82f), stroke, StrokeCap.Round)
                    val head = Path().apply {
                        moveTo(w * 0.32f, h * 0.46f)
                        lineTo(w * 0.5f, h * 0.28f)
                        lineTo(w * 0.68f, h * 0.46f)
                    }
                    if (active) {
                        drawPath(
                            path = Path().apply {
                                addPath(head)
                                close()
                            },
                            color = tint,
                        )
                    } else {
                        drawPath(
                            path = head,
                            color = tint,
                            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }

                NodeGlyph.Note -> {
                    // 铅笔：笔身描边 + 实心笔尖 + 笔杆分隔。
                    val body = Path().apply {
                        moveTo(w * 0.34f, h * 0.72f)
                        lineTo(w * 0.66f, h * 0.40f)
                        lineTo(w * 0.78f, h * 0.52f)
                        lineTo(w * 0.46f, h * 0.84f)
                        close()
                    }
                    if (active) drawPath(body, tint.copy(alpha = 0.22f))
                    drawPath(
                        path = body,
                        color = tint,
                        style = Stroke(width = stroke * 0.85f, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = Path().apply {
                            moveTo(w * 0.22f, h * 0.88f)
                            lineTo(w * 0.44f, h * 0.84f)
                            lineTo(w * 0.32f, h * 0.70f)
                            close()
                        },
                        color = tint,
                    )
                    drawLine(
                        color = tint,
                        start = Offset(w * 0.62f, h * 0.46f),
                        end = Offset(w * 0.74f, h * 0.58f),
                        strokeWidth = stroke * 0.8f,
                        cap = StrokeCap.Round,
                    )
                }

                NodeGlyph.ChevronDown -> {
                    drawLine(tint, Offset(w * 0.28f, h * 0.40f), Offset(w * 0.5f, h * 0.62f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.5f, h * 0.62f), Offset(w * 0.72f, h * 0.40f), stroke, StrokeCap.Round)
                }

                NodeGlyph.ChevronUp -> {
                    drawLine(tint, Offset(w * 0.28f, h * 0.60f), Offset(w * 0.5f, h * 0.38f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.72f, h * 0.60f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
/** 展开时字段依次淡入上移：每个字段延迟 22ms，最多叠 10 层，避免长列表等太久。 */
@Composable
private fun StaggeredIn(index: Int, content: @Composable () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 200,
                delayMillis = index.coerceAtMost(10) * 22,
            ),
        )
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 8.dp.toPx()
        },
    ) {
        content()
    }
}

/** 底栏的节点图入口：42dp 方块按钮，画一个三节点连线的示意图标。 */
@Composable
private fun GraphIconButton(onClick: () -> Unit) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(42.dp)
            .width(42.dp)
            .pressFeedback(interaction)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.borderStrong, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val box = w * 0.26f
            val stroke = 1.4f
            fun boxAt(x: Float, y: Float) {
                drawRoundRect(
                    color = c.textMuted,
                    topLeft = Offset(x, y),
                    size = Size(box, box * 0.8f),
                    cornerRadius = CornerRadius(2f),
                    style = Stroke(width = stroke),
                )
            }
            boxAt(w * 0.02f, h * 0.10f)
            boxAt(w * 0.70f, h * 0.10f)
            boxAt(w * 0.36f, h * 0.62f)
            drawLine(c.textDim, Offset(w * 0.28f, h * 0.20f), Offset(w * 0.36f, h * 0.62f), stroke)
            drawLine(c.textDim, Offset(w * 0.70f, h * 0.20f), Offset(w * 0.62f, h * 0.62f), stroke)
        }
    }
}