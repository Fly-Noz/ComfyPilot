package com.comfypilot.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.app.R
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumBackdrop
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.TopBarAction

private data class TutorialStep(
    val title: String,
    val body: String,
    @DrawableRes val image: Int,
)

private val CONNECT_STEPS = listOf(
    TutorialStep(
        title = "1. 下载启动脚本",
        body = "打开 GitHub Release 的 Assets，下载 run_remote.bat。不要用 run_local.bat：它只监听 127.0.0.1，手机连不上。",
        image = R.drawable.tutorial_connect_download,
    ),
    TutorialStep(
        title = "2. 放进 ComfyUI 根目录",
        body = "和 ComfyUI、python_embedded 两个文件夹放在同一层，不是放进 ComfyUI 文件夹里面。放错会提示找不到 Python 或 main.py。",
        image = R.drawable.tutorial_connect_place,
    ),
    TutorialStep(
        title = "3. 双击运行",
        body = "首次运行 Windows 防火墙弹窗时点「允许访问」，建议勾选专用网络。黑色控制台窗口不要关，关了 ComfyUI 就停了。",
        image = R.drawable.tutorial_connect_run,
    ),
    TutorialStep(
        title = "4. 手机填写地址",
        body = "在控制台找到 http://IP:8188，填回本页并点连接。同一 Wi-Fi 选 192.168.x.x；用 Tailscale 就选 100.x，手机也要装 Tailscale。",
        image = R.drawable.tutorial_connect_address,
    ),
    TutorialStep(
        title = "5. 看到工作流列表",
        body = "手机显示「已连接」，并且能刷新服务器工作流，就成功了。连不上先检查：同一网络、防火墙、地址是否包含 http:// 和 :8188。",
        image = R.drawable.tutorial_connect_success,
    ),
)

@Composable
fun ConnectTutorialDialog(onDismiss: () -> Unit) {
    val c = Spectrum.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "连接 ComfyUI 教程",
                        color = c.text,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "主机端 4 步，约 2 分钟",
                        color = c.textDim,
                        fontSize = 11.sp,
                        fontFamily = SpectrumMono,
                    )
                }
                TopBarAction(text = "关闭", onClick = onDismiss, emphasized = true)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    GlassCard(padding = PaddingValues(14.dp)) {
                        Text(
                            text = "不用改 ComfyUI 配置，也不用装插件。先确认手机和电脑在同一 Wi-Fi，或者都连上 Tailscale。",
                            color = c.textMuted,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
                items(CONNECT_STEPS) { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = step.title,
                            color = c.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = step.body,
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        Image(
                            painter = painterResource(step.image),
                            contentDescription = step.title,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, c.border, RoundedCornerShape(14.dp)),
                        )
                    }
                }
                item {
                    GlassCard(padding = PaddingValues(14.dp)) {
                        Text(
                            text = "连不上时按顺序检查",
                            color = c.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "① 手机和主机是否在同一 Wi-Fi 或同一 Tailscale 网络；\n" +
                                "② 控制台列出的 IP 是否抄全，末尾是否有 :8188；\n" +
                                "③ Windows 防火墙是否允许了 run_remote.bat；\n" +
                                "④ 是否误用了 run_local.bat。",
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
}
