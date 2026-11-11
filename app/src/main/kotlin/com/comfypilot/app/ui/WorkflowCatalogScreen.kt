package com.comfypilot.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.comfypilot.app.LocalWorkflowStore
import com.comfypilot.app.ui.theme.Dot
import com.comfypilot.app.ui.theme.GentleFloat
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.ShimmerBox
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.WorkflowCatalog
import com.comfypilot.core.workflow.WorkflowCodec
import com.comfypilot.core.workflow.WorkflowEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@Composable
fun WorkflowCatalogScreen(
    state: SessionState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onTasks: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onOpenLocal: (String, String, String) -> Unit,
) {
    val c = Spectrum.colors
    val connected = state.lease != null
    val context = LocalContext.current
    val pinnedStore = remember(context) { PinnedWorkflowStore(context.applicationContext) }
    var pinnedPaths by remember(context) { mutableStateOf(pinnedStore.load()) }
    val localStore = remember(context) { LocalWorkflowStore(context.applicationContext) }
    var localItems by remember(context) { mutableStateOf(localStore.list()) }
    var pendingDelete by remember { mutableStateOf<LocalWorkflowStore.Item?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { readLocalWorkflow(context, uri) }) {
                is LocalImport.Ok -> {
                    val item = withContext(Dispatchers.IO) { localStore.save(result.name, result.raw) }
                    localItems = localStore.list()
                    Toast.makeText(context, "已导入 ${item.name}", Toast.LENGTH_SHORT).show()
                    onOpenLocal(item.path, item.name, result.raw)
                }
                is LocalImport.Bad -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 进入页面时如果还没拉过列表，自动拉一次；失败后不自动重试，交给用户点"刷新"。
    LaunchedEffect(connected, state.catalog) {
        if (connected && state.catalog is WorkflowCatalog.Idle) onRefresh()
    }

    SpectrumScaffold(
        topBar = {
            SpectrumTopBar(
                title = "工作流",
                onBack = onBack,
                actions = {
                    TopBarAction(text = "设置", onClick = onSettings)
                    TopBarAction(text = "刷新", onClick = onRefresh, enabled = connected)
                    TopBarAction(text = "任务", onClick = onTasks)
                    TopBarAction(
                        text = "断开",
                        onClick = {
                            onDisconnect()
                            onBack()
                        },
                        enabled = connected,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Dot(color = if (connected) c.ok else c.textDim)
                Text(
                    text = state.connectionLabel,
                    color = c.textMuted,
                    fontSize = 11.5.sp,
                )
                Text(
                    text = "· ${state.network.transport.name} · 第 ${state.generation} 代",
                    color = c.textDim,
                    fontSize = 10.sp,
                    fontFamily = SpectrumMono,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(
                    text = "导入本地工作流",
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"),
                        )
                    },
                    enabled = connected,
                    height = 32.dp,
                )
            }

            pendingDelete?.let { item ->
                SpectrumDialog(
                    title = "删除本地工作流？",
                    onDismiss = { pendingDelete = null },
                    confirmText = "删除",
                    dismissText = "取消",
                    onConfirm = {
                        localStore.delete(item.path)
                        localItems = localStore.list()
                        pendingDelete = null
                        Toast.makeText(context, "已删除 ${item.name}", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(
                        text = "只会删除本机的导入副本，不影响主机上的工作流文件。",
                        color = c.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            // 加载态 → 内容态交叉淡化：骨架屏不是"啪"地消失，内容也不是突然冒出来。
            val catalogKind = when (state.catalog) {
                is WorkflowCatalog.Idle -> "idle"
                is WorkflowCatalog.Loading -> "loading"
                is WorkflowCatalog.Failed -> "failed"
                is WorkflowCatalog.Ready -> "ready"
            }
            AnimatedContent(
                targetState = catalogKind,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(260)).togetherWith(fadeOut(tween(160)))
                },
                label = "catalog-state",
            ) { _ ->
            // 内容自己的 Column 作用域：列表的 weight(1f) 必须挂在这个 Column 上，
            // 挂到 AnimatedContent 里会丢失作用域，导致列表和上方文字重叠。
            Column(modifier = Modifier.fillMaxSize()) {
            when (val catalog = state.catalog) {                is WorkflowCatalog.Idle -> HintCard(
                    text = if (connected) "准备读取服务器工作流…" else "尚未连接 ComfyUI。",
                )

                is WorkflowCatalog.Loading -> Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = c.accent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "正在读取服务器 workflows 目录…",
                            color = c.textMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // 骨架屏：先用和真实卡片同样的形状占位，数据到达时不会整页跳动。
                    repeat(4) { index ->
                        GlassCard {
                            ShimmerBox(
                                modifier = Modifier
                                    .fillMaxWidth((0.62f - index * 0.07f).coerceAtLeast(0.36f))
                                    .height(13.dp),
                                shape = RoundedCornerShape(6.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            ShimmerBox(
                                modifier = Modifier.fillMaxWidth(0.34f).height(10.dp),
                                shape = RoundedCornerShape(5.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }

                is WorkflowCatalog.Failed -> GlassCard {
                    Text(
                        text = "读取失败：${catalog.message}",
                        color = c.err,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        text = "重试",
                        onClick = onRefresh,
                        enabled = connected,
                        modifier = Modifier.width(96.dp),
                    )
                }

                is WorkflowCatalog.Ready -> {
                    if (catalog.entries.isEmpty() && localItems.isEmpty()) {
                        GlassCard {
                            Text(
                                text = "服务器 workflows 目录里没有 .json 工作流。",
                                color = c.textMuted,
                                fontSize = 12.5.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            GhostButton(
                                text = "刷新",
                                onClick = onRefresh,
                                modifier = Modifier.width(96.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "本地 ${localItems.size} 个 · 服务器 ${catalog.entries.size} 个，点一个打开。",
                            color = c.textDim,
                            fontSize = 10.5.sp,
                            fontFamily = SpectrumMono,
                        )
                        val orderedEntries = remember(catalog.entries, pinnedPaths) {
                            orderWorkflowEntries(catalog.entries, pinnedPaths)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                        ) {
                            if (localItems.isNotEmpty()) {
                                item(key = "local-header") {
                                    Text(
                                        text = "本地导入",
                                        color = c.textDim,
                                        fontSize = 9.5.sp,
                                        fontFamily = SpectrumMono,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                items(localItems, key = { "local:${it.path}" }) { item ->
                                    LocalEntryCard(
                                        item = item,
                                        onClick = {
                                            scope.launch {
                                                val raw = withContext(Dispatchers.IO) { localStore.read(item.path) }
                                                if (raw == null) {
                                                    Toast.makeText(context, "本地文件已丢失", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    onOpenLocal(item.path, item.name, raw)
                                                }
                                            }
                                        },
                                        onDelete = { pendingDelete = item },
                                    )
                                }
                            }
                            if (orderedEntries.isNotEmpty()) {
                                item(key = "server-header") {
                                    Text(
                                        text = "服务器",
                                        color = c.textDim,
                                        fontSize = 9.5.sp,
                                        fontFamily = SpectrumMono,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                                items(orderedEntries, key = { it.path }) { entry ->
                                    EntryCard(
                                        entry = entry,
                                        pinned = entry.path in pinnedPaths,
                                        onClick = { onOpen(entry.path) },
                                        onTogglePin = {
                                            val pinning = entry.path !in pinnedPaths
                                            val next = if (pinning) {
                                                pinnedPaths + entry.path
                                            } else {
                                                pinnedPaths - entry.path
                                            }
                                            pinnedPaths = next
                                            pinnedStore.save(next)
                                            if (pinning) scope.launch { listState.animateScrollToItem(0) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    val c = Spectrum.colors
    GlassCard {
        GentleFloat {
            Text(text = text, color = c.textMuted, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun EntryCard(
    entry: WorkflowEntry,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, if (pinned) c.accentBorder else c.border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (pinned) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(c.gradientBrush()),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.name,
                        color = c.text,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    entry.sizeBytes?.let {
                        Text(
                            text = formatSize(it),
                            color = c.textDim,
                            fontSize = 9.5.sp,
                            fontFamily = SpectrumMono,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.path,
                        color = c.textDim,
                        fontSize = 10.sp,
                        fontFamily = SpectrumMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinned) {
                        Text(
                            text = "已置顶",
                            color = c.accent,
                            fontSize = 9.5.sp,
                            fontFamily = SpectrumMono,
                        )
                    }
                    Text(
                        text = if (pinned) "取消置顶" else "置顶",
                        color = c.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, c.borderStrong, RoundedCornerShape(7.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTogglePin,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
private sealed interface LocalImport {
    data class Ok(val name: String, val raw: String) : LocalImport
    data class Bad(val message: String) : LocalImport
}

/** 读取用户选中的 JSON，限制 2 MB；不是 UI 工作流或过大都直接拒绝。 */
private fun readLocalWorkflow(context: android.content.Context, uri: Uri): LocalImport {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull().orEmpty().ifBlank { "导入的工作流" }
    val raw = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 2 * 1024 * 1024) return LocalImport.Bad("工作流文件过大，最多 2 MB")
                output.write(buffer, 0, count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }.getOrNull() ?: return LocalImport.Bad("无法读取文件")

    return when (val parsed = WorkflowCodec.parseDocument(raw)) {
        is WorkflowCodec.Parsed.Bad -> LocalImport.Bad("不是 ComfyUI UI 工作流：${parsed.reason}")
        is WorkflowCodec.Parsed.Ok -> LocalImport.Ok(
            name = name.removeSuffix(".json").ifBlank { "本地工作流" },
            raw = raw,
        )
    }
}

/** 本地导入条目：和服务器卡片同一列表，但明确标记来源，并带删除入口。 */
@Composable
private fun LocalEntryCard(
    item: LocalWorkflowStore.Item,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.accentBorder, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(c.accentSoft),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = item.name,
                    color = c.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "本地导入",
                        color = c.accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.accentSoft)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        text = "仅存本机 · 不会同步到主机",
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                    )
                }
            }
            Text(
                text = "删除",
                color = c.textDim,
                fontSize = 10.5.sp,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDelete,
                    ),
            )
        }
    }
}
