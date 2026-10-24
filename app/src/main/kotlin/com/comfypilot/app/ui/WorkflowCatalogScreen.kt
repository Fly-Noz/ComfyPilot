package com.comfypilot.app.ui

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
import com.comfypilot.app.ui.theme.Dot
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.ShimmerBox
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.WorkflowCatalog
import com.comfypilot.core.workflow.WorkflowEntry
import kotlinx.coroutines.launch

@Composable
fun WorkflowCatalogScreen(
    state: SessionState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onTasks: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = Spectrum.colors
    val connected = state.lease != null
    val context = LocalContext.current
    val pinnedStore = remember(context) { PinnedWorkflowStore(context.applicationContext) }
    var pinnedPaths by remember(context) { mutableStateOf(pinnedStore.load()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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

            when (val catalog = state.catalog) {
                is WorkflowCatalog.Idle -> HintCard(
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
                    if (catalog.entries.isEmpty()) {
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
                            text = "共 ${catalog.entries.size} 个，点一个打开。",
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
                                        // 置顶后条目会移到列表最前；把视口带回去，避免像"消失"了。
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

@Composable
private fun HintCard(text: String) {
    val c = Spectrum.colors
    GlassCard {
        Text(text = text, color = c.textMuted, fontSize = 12.5.sp)
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