package com.comfypilot.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.core.api.ApiPaths
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
) {
    val document = state.workflow.document

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        document?.name?.takeIf { it.isNotBlank() } ?: "工作流",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onTasks) { Text("任务") } },
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
                )
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("正在读取工作流…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ErrorBody(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("打开失败：$message", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onBack) { Text("返回列表") }
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
) {
    var enabledGraphOverride by remember(document.path) { mutableStateOf<Set<String>?>(null) }
    val context = LocalContext.current
    val pinnedNodeStore = remember(context) { PinnedNodeStore(context.applicationContext) }
    var pinnedNodeIds by remember(document.path) {
        mutableStateOf(pinnedNodeStore.load(document.path))
    }
    var overrides by remember(document.path) {
        mutableStateOf<Map<Int, Map<String, JsonElement>>>(emptyMap())
    }

    // 节点图、字段和待加载定义都由共享层决定；这里只缓存与字段值无关的部分。
    val analysis = remember(document, state.nodeInfo, enabledGraphOverride, state.nodeInfoRequested) {
        WorkflowDecisions.analyze(
            document = document,
            definitions = state.nodeInfo,
            requestedGraphIds = enabledGraphOverride,
            nodeInfoRequested = state.nodeInfoRequested,
        )
    }
    val graphs = analysis.graphs
    val selectedGraphIds = analysis.selectedGraphIds
    val fields = analysis.fields
    val disabledNodeIds = analysis.disabledNodeIds
    val showGraphPicker = graphs.size > 1 || (graphs.size == 1 && !graphs[0].enabled)
    // 置顶且会提交的节点最前；节点图未启用时保留置顶，但不会优先显示。
    // 其余节点仍按“可达优先、可达且缺值更优先”排序，未启用/旁路的节点放最后。
    val orderedNodes = remember(analysis, pinnedNodeIds) {
        WorkflowDecisions.orderNodes(analysis.fields.nodes, pinnedNodeIds)
    }
    var tab by remember(document.path) { mutableStateOf(0) }
    var expandedOverride by remember(document.path) { mutableStateOf<Set<Int>?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    var blockedProblems by remember { mutableStateOf<List<BuildProblem>?>(null) }
    var pendingReady by remember { mutableStateOf<PromptBuild.Ready?>(null) }
    var dismissedFailureAt by remember(document.path) { mutableStateOf(Long.MIN_VALUE) }

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

    // 上传只服务 LoadImage 的 image 控件：先传服务器，再用返回的文件名覆盖控件值。
    val uploader = remember { OkHttpHttpTransport() }
    var uploadTarget by remember { mutableStateOf<WorkflowField?>(null) }
    var uploading by remember { mutableStateOf(false) }
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
                is ImageUploadResult.Success -> applyOverride(
                    target.nodeId,
                    target.inputName,
                    JsonPrimitive(result.reference),
                )

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
                    tab = 1
                } else {
                    pendingReady = result
                }
            }

            is PromptBuild.Blocked -> blockedProblems = decision.problems
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ServerStatsStrip(baseUrl = state.lease?.baseUrl)

        if (latestFailure != null && latestFailure.updatedAtMs > dismissedFailureAt) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("ComfyUI 报错", style = MaterialTheme.typography.labelSmall)
                    Text(
                        latestFailure.error ?: "任务失败",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        TextButton(onClick = onTasks) { Text("查看任务") }
                        TextButton(onClick = { dismissedFailureAt = latestFailure.updatedAtMs }) {
                            Text("关闭")
                        }
                    }
                }
            }
        }

        if (showGraphPicker) {
            val parents = graphs.filter { it.outputNodeIds.isNotEmpty() }
            val childrenByParent = graphs
                .filter { it.parentId != null }
                .groupBy { it.parentId }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    parents.forEach { graph ->
                        GraphChip(
                            graph = graph,
                            selected = graph.id in selectedGraphIds,
                            onClick = { toggleGraph(graph) },
                        )
                    }
                }
                parents.filter { it.id in selectedGraphIds }.forEach { parent ->
                    val children = childrenByParent[parent.id].orEmpty()
                    if (children.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            children.forEach { child ->
                                GraphChip(
                                    graph = child,
                                    selected = child.id in selectedGraphIds,
                                    child = true,
                                    onClick = { toggleGraph(child) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabButton(
                text = "输入 (${fields.nodes.sumOf { it.fields.size }})",
                selected = tab == 0,
                onClick = { tab = 0 },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                text = "预览",
                selected = tab == 1,
                onClick = { tab = 1 },
                modifier = Modifier.weight(1f),
            )
        }

        if (pendingClasses > 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "正在加载节点定义：还差 $pendingClasses 个类",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (uploading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "正在上传图片…",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        uploadError?.let { message ->
            Text(
                "上传失败：$message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            TextButton(onClick = { uploadError = null }) { Text("知道了") }
        }
        if (fields.problems.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    fields.problems.forEach { problem ->
                        Text(problem.format(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                InputPane(
                    nodes = orderedNodes,
                    expanded = expanded,
                    overrides = overrides,
                    listState = listState,
                    disabledNodeIds = disabledNodeIds,
                    pinnedNodeIds = pinnedNodeIds,
                    onToggle = ::toggleNode,
                    onTogglePin = ::togglePin,
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

        Surface(tonalElevation = 3.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                blockReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { sheetVisible = true }) { Text("全部节点") }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${fields.nodes.count { it.reachable }} 个会提交",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Button(onClick = ::submit, enabled = canSubmit) { Text("提交任务") }
                }
            }
        }
    }

    if (sheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "全部节点",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxHeight(0.75f)) {
                items(fields.nodes, key = { it.id }) { node ->
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
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "#${node.id} ${node.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                node.type,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        val stateLabel = when {
                            node.missing -> "缺少定义"
                            node.id in disabledNodeIds -> "未启用"
                            !node.reachable -> "不会提交"
                            else -> "${node.fields.size} 个输入"
                        }
                        Text(stateLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    blockedProblems?.let { problems ->
        AlertDialog(
            onDismissRequest = { blockedProblems = null },
            title = { Text("不能提交") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    problems.forEach { problem ->
                        Text("• ${problem.format()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { blockedProblems = null }) { Text("知道了") }
            },
        )
    }

    pendingReady?.let { ready ->
        AlertDialog(
            onDismissRequest = { pendingReady = null },
            title = { Text("提交前确认") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("以下提示不会阻止提交：", style = MaterialTheme.typography.bodySmall)
                    ready.warnings.forEach { warning ->
                        Text("• $warning", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = ready.prompt.toString()
                        pendingReady = null
                        onSubmit(json, document.name, document.path)
                        tab = 1
                    },
                ) { Text("仍然提交") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReady = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
private fun InputPane(
    nodes: List<WorkflowNodeView>,
    expanded: Set<Int>,
    overrides: Map<Int, Map<String, JsonElement>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    disabledNodeIds: Set<Int>,
    pinnedNodeIds: Set<Int>,
    onToggle: (Int) -> Unit,
    onTogglePin: (Int) -> Unit,
    onOverride: (Int, String, JsonElement) -> Unit,
    onPickImage: (WorkflowField) -> Unit,
) {
    if (nodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("这个工作流没有可编辑的节点输入。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(nodes, key = { it.id }) { node ->
            NodeInputCard(
                node = node,
                expanded = node.id in expanded,
                overrides = overrides[node.id].orEmpty(),
                graphDisabled = node.id in disabledNodeIds,
                pinned = node.id in pinnedNodeIds,
                onToggle = { onToggle(node.id) },
                onTogglePin = { onTogglePin(node.id) },
                onChange = onOverride,
                onPickImage = onPickImage,
            )
        }
    }
}

@Composable
private fun NodeInputCard(
    node: WorkflowNodeView,
    expanded: Boolean,
    overrides: Map<String, JsonElement>,
    graphDisabled: Boolean,
    pinned: Boolean,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onChange: (Int, String, JsonElement) -> Unit,
    onPickImage: (WorkflowField) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "#${node.id} ${node.displayName}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${node.type} · ${node.fields.size} 个输入",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(
                    onClick = onTogglePin,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        if (pinned) "取消置顶" else "置顶",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (node.needsInput) {
                    Text(
                        "待填写",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            when {
                node.missing -> Text(
                    "服务端没有该节点的定义，不能调参；提交会被拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                graphDisabled -> Text(
                    "节点图未启用，不会被提交。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                !node.reachable -> Text(
                    "未连接到输出节点，不会被提交。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    node.fields.forEach { field ->
                        FieldEditor(
                            field = field,
                            value = overrides[field.inputName] ?: field.value,
                            editable = node.reachable,
                            onPickImage = if (node.reachable && field.nodeType == "LoadImage" && field.inputName == "image") {
                                { onPickImage(field) }
                            } else {
                                null
                            },
                            onChange = { value -> onChange(node.id, field.inputName, value) },
                        )
                    }
                    if (node.fields.isEmpty()) {
                        Text("没有可调参数。", style = MaterialTheme.typography.bodySmall)
                    }
                }
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

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${taskStateLabel(latest.state)} · ${latest.progress?.percent ?: 0}%",
            style = MaterialTheme.typography.titleMedium,
        )
        val output = latest.outputs.firstOrNull { isImageFile(it.filename) }
        val baseUrl = state.lease?.baseUrl
        when {
            output != null && baseUrl != null -> RemoteOutputImage(
                url = ApiPaths.viewUrl(
                    baseUrl,
                    ViewRequest(output.filename, output.subfolder, output.type),
                ),
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )

            latest.outputs.isNotEmpty() -> Text(
                "产物：${latest.outputs.joinToString("、") { it.filename }}",
                style = MaterialTheme.typography.bodySmall,
            )

            latest.state is TaskState.Failed -> Text(
                "任务失败：${(latest.state as TaskState.Failed).message ?: "未知原因"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Text("当前没有正在执行的任务。", style = MaterialTheme.typography.bodySmall)
        }
        if (latest.outputs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showArtifacts = true }) { Text("查看产物") }
                TextButton(onClick = onTasks) { Text("在任务页查看全部") }
            }
        }
    }

    if (showArtifacts) {
        ArtifactDialog(
            task = latest,
            baseUrl = state.lease?.baseUrl,
            onDismiss = { showArtifacts = false },
        )
    }
}

@Composable
private fun ActivePreview(
    task: PromptTask,
    preview: PreviewFrame?,
    onCancel: (String) -> Unit,
) {
    val frame = preview?.takeIf { it.promptId == null || it.promptId == task.promptId }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val progress = task.progress
        Text(
            "${taskStateLabel(task.state)} · ${progress?.percent ?: 0}%",
            style = MaterialTheme.typography.titleMedium,
        )
        if (progress != null && progress.max > 0) {
            Text(
                progressStepLabel(progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val percent = progress?.percent ?: 0
        if (task.state is TaskState.Running) {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (frame != null) {
            LivePreviewImage(bytes = frame.image)
        } else {
            Column(
                modifier = Modifier.padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (task.state is TaskState.Queued) "排队中，开始后显示采样画面" else "等待采样画面…",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (task.state is TaskState.Running) {
                    Text(
                        "若一直不出现，确认 ComfyUI 以 --preview-method auto 启动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        task.promptId?.let { promptId ->
            OutlinedButton(onClick = { onCancel(promptId) }) { Text("取消任务") }
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
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这个工作流还没有任务。", style = MaterialTheme.typography.bodyMedium)
        Text("提交后在这里显示采样画面和结果。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FieldEditor(
    field: WorkflowField,
    value: JsonElement?,
    editable: Boolean,
    onPickImage: (() -> Unit)? = null,
    onChange: (JsonElement) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                field.label ?: field.inputName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(field.type, style = MaterialTheme.typography.labelSmall)
        }

        when {
            field.linked -> Text("由连线提供", style = MaterialTheme.typography.bodySmall)
            !field.widget -> Text(
                if (field.required) {
                    "需要连线；当前未连接，提交会被拒绝。"
                } else {
                    "可选连线；当前未连接。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (field.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            !editable -> Text(
                "${valueText(value)}（不在提交范围内）",
                style = MaterialTheme.typography.bodySmall,
            )

            field.options.isNotEmpty() -> OptionPicker(
                options = field.options,
                current = valueText(value),
                onSelect = { onChange(JsonPrimitive(it)) },
            )

            field.type == "BOOLEAN" -> Switch(
                checked = (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onChange(JsonPrimitive(it)) },
            )

            field.type == "INT" || field.type == "FLOAT" -> NumberField(
                isFloat = field.type == "FLOAT",
                value = value,
                onChange = onChange,
            )

            else -> OutlinedTextField(
                value = valueText(value),
                onValueChange = { onChange(JsonPrimitive(it)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 1,
            )
        }

        if (field.required && field.widget && !field.linked && value == null) {
            Text(
                "必填",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (onPickImage != null && editable) {
            OutlinedButton(onClick = onPickImage) { Text("从相册选择") }
        }
    }
}

@Composable
private fun OptionPicker(
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(current.ifBlank { "选择…" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
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

    OutlinedTextField(
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
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isFloat) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

private fun valueText(value: JsonElement?): String = when (value) {
    null -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

@Composable
private fun GraphChip(
    graph: WorkflowGraph,
    selected: Boolean,
    child: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = if (child) Modifier.padding(start = 8.dp) else Modifier,
        label = {
            Text(
                if (child) "└ ${graph.title}" else graph.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
