package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GradientButton
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.update.UpdatePrompt

/**
 * 发现新版本。
 *
 * 两个下载入口都给出来，让用户自己选：Gitee 国内直连快，GitHub 是原始发布页。
 * 「稍后」记下这个版本号，下一次发版前不再打扰。
 */
@Composable
fun UpdateDialog(
    prompt: UpdatePrompt,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val c = Spectrum.colors
    val gitee = prompt.links.gitee
    val github = prompt.links.github

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.dialog)
                .border(1.dp, c.borderStrong, RoundedCornerShape(22.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "发现新版本 ${prompt.versionName}",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            )
            Text(
                text = "当前 ${prompt.currentVersion} · 最新 ${prompt.versionName}",
                color = c.textDim,
                fontSize = 11.sp,
                fontFamily = SpectrumMono,
            )

            val notes = prompt.notes
            if (!notes.isNullOrBlank()) {
                // 更新说明可能很长，限高滚动，别把按钮挤出屏幕。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = notes,
                        color = c.textMuted,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(text = "稍后", onClick = onSkip, height = 40.dp)
                Spacer(Modifier.weight(1f))
                if (!github.isNullOrBlank()) {
                    GhostButton(
                        text = "GitHub",
                        onClick = { onOpen(github) },
                        height = 40.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (!gitee.isNullOrBlank()) {
                    GradientButton(
                        text = "Gitee",
                        onClick = { onOpen(gitee) },
                        height = 40.dp,
                    )
                }
            }

            // 一句话说明为什么有两个按钮，省得用户以为哪个是「备用」。
            Text(
                text = "国内建议走 Gitee，直连更快",
                color = c.textDim,
                fontSize = 10.5.sp,
            )
        }
    }
}
