package com.comfypilot.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.comfypilot.app.R
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar
import kotlinx.coroutines.delay

private const val PROJECT_URL = "https://github.com/Fly-Noz/ComfyPilot"
private const val LICENSE = "GPL-3.0"

private const val ABOUT_DESCRIPTION =
    "一个 ComfyUI 远程客户端，支持工作流浏览、参数调节、任务队列与产物预览。" +
        "共享核心由 Kotlin Multiplatform 实现，界面为 Compose 原生。"

private const val LICENSE_BODY =
    "本项目以 GNU General Public License v3.0 发布；" +
        "你可以自由使用、修改与分发，但衍生作品需同样以 GPL-3.0 开源；" +
        "完整协议文本见仓库根目录的 LICENSE 文件；Compose、OkHttp 等第三方组件的许可见 THIRD_PARTY_NOTICES.md。"

private const val AI_FOOTNOTE =
    "本项目的部分界面、文案与图标由 AI 辅助生成。"

/**
 * 关于页：居中图标 + 名称/版本 + 简介，然后项目地址、许可证两行。
 * 从设置页进入，返回键回到设置；沿用 [SpectrumScaffold] 的共享背景。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = Spectrum.colors
    val context = LocalContext.current
    val version = remember(context) { appVersion(context) }
    var licenseOpen by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    // 系统在 API 26+ 会把 ic_launcher 解析成 adaptive-icon XML，
    // painterResource 只吃 vector/raster，这里先把 Drawable 画成位图。
    val iconPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    val icon = remember(context, iconPx) {
        runCatching {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?.toBitmap(width = iconPx.coerceAtLeast(1), height = iconPx.coerceAtLeast(1))
                ?.asImageBitmap()
        }.getOrNull()
    }

    // 复制提示只停留一小会儿，不打断页面。
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    SpectrumScaffold(
        topBar = { SpectrumTopBar(title = "关于", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.cardStrong)
                    .border(1.dp, c.border, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = icon
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "ComfyPilot 应用图标",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "ComfyPilot",
                color = c.text,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
            )

            Spacer(Modifier.height(5.dp))

            Text(
                text = "版本 $version · Android 版",
                color = c.textDim,
                fontSize = 11.sp,
                fontFamily = SpectrumMono,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = ABOUT_DESCRIPTION,
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))

            GlassCard(padding = PaddingValues(vertical = 4.dp)) {
                SpectrumLinkRow(
                    label = "项目地址",
                    value = PROJECT_URL,
                    onClick = {
                        copyToClipboard(context, "项目地址", PROJECT_URL)
                        copied = true
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(c.border),
                )
                SpectrumLinkRow(
                    label = "开源许可证",
                    value = LICENSE,
                    onClick = { licenseOpen = true },
                )
            }

            // 固定高度：提示出现/消失时下面的脚注不跳动。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (copied) {
                    Text(
                        text = "已复制到剪贴板",
                        color = c.ok,
                        fontSize = 11.sp,
                        fontFamily = SpectrumMono,
                    )
                }
            }

            Text(
                text = AI_FOOTNOTE,
                color = c.textDim,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }

    if (licenseOpen) {
        SpectrumDialog(
            title = "开源许可证",
            onDismiss = { licenseOpen = false },
            confirmText = "关闭",
            onConfirm = { licenseOpen = false },
            body = {
                Text(
                    text = LICENSE_BODY,
                    color = c.textMuted,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                )
            },
        )
    }
}

/** 设置页与关于页共用的行：左侧标题 + 副标题，右侧箭头。 */
@Composable
internal fun SpectrumLinkRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val c = Spectrum.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                color = c.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value,
                color = c.textDim,
                fontSize = 12.sp,
            )
        }
        SpectrumChevron()
    }
}

/** 右向箭头：用画笔画，避免为一个小图标引额外的图标依赖。 */
@Composable
internal fun SpectrumChevron(modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    Canvas(modifier = modifier.size(13.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.30f, size.height * 0.18f)
            lineTo(size.width * 0.68f, size.height * 0.50f)
            lineTo(size.width * 0.30f, size.height * 0.82f)
        }
        drawPath(
            path = path,
            color = c.textDim,
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 版本号取自 PackageManager：app 模块没有开 buildConfig，读不到 BuildConfig.VERSION_NAME。
 * 取不到时退回 gradle 里的 versionName。
 */
private fun appVersion(context: Context): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull() ?: "0.2.0"
