package com.comfypilot.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.core.api.ImageUpload
import com.comfypilot.core.api.ImageUploadResult
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.BuildProblem
import com.comfypilot.core.workflow.PromptBuild
import com.comfypilot.core.workflow.PromptBuilder
import com.comfypilot.core.workflow.WorkflowDocument
import com.comfypilot.core.workflow.WorkflowField
import com.comfypilot.core.workflow.WorkflowFields
import com.comfypilot.core.workflow.WorkflowNodeView
import kotlinx.coroutines.Dispatchers
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
    onSubmit: (String) -> Unit,
) {
    val document = state.workflow.document

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document?.name?.takeIf { it.isNotBlank() } ?: "工作流") },
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

                else -> DetailBody(document = document, state = state, onSubmit = onSubmit)
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
    onSubmit: (String) -> Unit,
) {
    val fields = remember(document, state.nodeInfo) {
        WorkflowFields.build(document, state.nodeInfo)
    }
    var overrides by remember(document.path) {
        mutableStateOf<Map<Int, Map<String, JsonElement>>>(emptyMap())
    }
    var tab by remember(document.path) { mutableStateOf(0) }
    var blockedProblems by remember { mutableStateOf<List<BuildProblem>?>(null) }
    var pendingReady by remember { mutableStateOf<PromptBuild.Ready?>(null) }

    val pendingClasses = document.nodeClasses.count { it !in state.nodeInfo }

    fun applyOverride(nodeId: Int, inputName: String, value: JsonElement) {
        val forNode = overrides[nodeId].orEmpty() + (inputName to value)
        overrides = overrides + (nodeId to forNode)
    }

    // 上传只服务 LoadImage 的 image 控件：先传服务器，再用返回的文件名覆盖控件值。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                document.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "格式 ${document.format} · ${document.nodeClasses.size} 个节点类 · ${fields.nodes.count { it.reachable }} 个会提交",
                style = MaterialTheme.typography.bodySmall,
            )
            if (pendingClasses > 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "正在加载节点定义：还差 $pendingClasses 个类。未加载完时提交会被拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (fields.problems.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        fields.problems.forEach { problem ->
                            Text(problem.format(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (uploading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("正在上传图片…", style = MaterialTheme.typography.bodySmall)
            }
            uploadError?.let { message ->
                Text(
                    "上传失败：$message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { uploadError = null }) { Text("知道了") }
            }
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("常用输入 (${fields.commonFields.size})") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("全部节点 (${fields.nodes.size})") },
            )
        }

        if (tab == 0) {
            if (fields.commonFields.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    Text(
                        "这个工作流没有识别到常用输入，请到「全部节点」里调整。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(fields.commonFields, key = { "${it.nodeId}:${it.inputName}" }) { field ->
                        FieldEditor(
                            field = field,
                            value = overrides[field.nodeId]?.get(field.inputName) ?: field.value,
                            editable = true,
                            showNode = true,
                            onPickImage = if (field.nodeType == "LoadImage" && field.inputName == "image") {
                                { pickImage(field) }
                            } else {
                                null
                            },
                            onChange = { value -> applyOverride(field.nodeId, field.inputName, value) },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(fields.nodes, key = { it.id }) { node ->
                    NodeCard(
                        node = node,
                        overrides = overrides[node.id].orEmpty(),
                        onChange = ::applyOverride,
                        onPickImage = ::pickImage,
                    )
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (pendingClasses > 0) "节点定义未加载完，提交可能被拒绝" else "参数只影响本次提交，不改服务器上的工作流文件",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        when (val result = PromptBuilder.build(document, state.nodeInfo, overrides)) {
                            is PromptBuild.Ready -> {
                                if (result.warnings.isEmpty()) {
                                    onSubmit(result.prompt.toString())
                                } else {
                                    pendingReady = result
                                }
                            }

                            is PromptBuild.Blocked -> blockedProblems = result.problems
                        }
                    },
                ) { Text("提交任务") }
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
                        onSubmit(json)
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
private fun NodeCard(
    node: WorkflowNodeView,
    overrides: Map<String, JsonElement>,
    onChange: (Int, String, JsonElement) -> Unit,
    onPickImage: (WorkflowField) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(node.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("#${node.id}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                node.type,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            when {
                node.missing -> Text(
                    "服务端没有该节点的定义，不能调参；提交会被拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                !node.reachable -> Text(
                    "未连接到输出节点，不会被提交。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
            if (node.fields.isEmpty() && !node.missing) {
                Text("没有可调参数。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FieldEditor(
    field: WorkflowField,
    value: JsonElement?,
    editable: Boolean,
    showNode: Boolean = false,
    onPickImage: (() -> Unit)? = null,
    onChange: (JsonElement) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = field.label ?: field.inputName
            Text(
                if (showNode) "$label · #${field.nodeId}" else label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(field.type, style = MaterialTheme.typography.labelSmall)
        }

        when {
            field.linked -> Text("由连线提供", style = MaterialTheme.typography.bodySmall)
            !field.widget -> Text(
                "需要连线；当前未连接，提交会被拒绝。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
