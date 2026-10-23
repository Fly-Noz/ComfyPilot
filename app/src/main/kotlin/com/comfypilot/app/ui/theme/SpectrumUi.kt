package com.comfypilot.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 全屏背景：底色 + 三层色晕。所有页面共用同一层，切换屏幕不闪。 */
@Composable
fun SpectrumBackdrop(modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(c.background)
        val w = size.width
        val h = size.height

        fun glow(color: Color, cx: Float, cy: Float, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }

        glow(c.glowTop, cx = w * 0.86f, cy = -h * 0.01f, radius = w * 1.05f)
        glow(c.glowSide, cx = w * 0.02f, cy = h * 0.13f, radius = w * 0.95f)
        glow(c.glowBottom, cx = w * 0.55f, cy = h * 1.02f, radius = w * 1.1f)
    }
}

/**
 * 页面骨架：自带 [SpectrumBackdrop] 背景，顶栏/底栏各自吃掉状态栏与导航栏的 inset。
 * 每个页面都是自包含的一层，覆盖层（任务、产物）不会漏出下层的颜色。
 */
@Composable
fun SpectrumScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SpectrumBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) { topBar() }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding(),
                ) { bottomBar() }
            },
            content = content,
        )
    }
}

@Composable
fun SpectrumTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    titleOverride: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Spectrum.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onBack != null) {
            TopBarAction(text = "返回", onClick = onBack)
            Spacer(Modifier.width(2.dp))
        }
        if (titleOverride != null) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                titleOverride()
            }
        } else {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = c.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions,
        )
    }
}

@Composable
fun TopBarAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val c = Spectrum.colors
    val color = when {
        !enabled -> c.textDim.copy(alpha = 0.5f)
        emphasized -> c.text
        else -> c.textMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 7.dp, vertical = 7.dp),
    ) {
        Text(text = text, color = color, fontSize = 12.sp)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Spectrum.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 46.dp,
) {
    val c = Spectrum.colors
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(RoundedCornerShape(14.dp))
            .background(c.gradientBrush())
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = c.accentInk,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    height: Dp = 42.dp,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(if (selected) c.cardStrong else c.card)
            .border(1.dp, if (selected) c.accentBorder else c.borderStrong, shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) c.text else c.textMuted,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
fun SpectrumChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    child: Boolean = false,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(50)
    val base = if (child) modifier.padding(start = 16.dp) else modifier
    // 选中态用一层渐变盖上去做淡入淡出，避免颜色"跳"。
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(170),
        label = "chip-fill",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) c.accentInk else c.textMuted,
        animationSpec = tween(170),
        label = "chip-text",
    )
    Box(
        modifier = base
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(c.gradientBrush())
                .graphicsLayer { alpha = fill },
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun SpectrumTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) c.cardStrong else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) c.text else c.textMuted,
                    fontSize = 12.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SpectrumProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 7.dp,
) {
    val c = Spectrum.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(c.barTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(c.gradientBrush()),
        )
    }
}

enum class PillTone { Neutral, Ok, Warn, Err }

@Composable
fun SpectrumPill(text: String, tone: PillTone = PillTone.Neutral) {
    val c = Spectrum.colors
    val color = when (tone) {
        PillTone.Neutral -> c.textMuted
        PillTone.Ok -> c.ok
        PillTone.Warn -> c.warn
        PillTone.Err -> c.err
    }
    val bg = when (tone) {
        PillTone.Neutral -> c.cardStrong
        PillTone.Ok -> c.okSoft
        PillTone.Warn -> c.warnSoft
        PillTone.Err -> c.errSoft
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(modifier = Modifier.height(5.dp).width(5.dp)) {
            drawCircle(color = color)
        }
        Text(text = text, color = color, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun SpectrumTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    keyboardType: KeyboardType? = null,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = if (keyboardType != null) {
            KeyboardOptions(keyboardType = keyboardType)
        } else {
            KeyboardOptions.Default
        },
        textStyle = TextStyle(
            color = c.text,
            fontSize = 12.5.sp,
            fontFamily = if (mono) SpectrumMono else SpectrumSans,
        ),
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.card)
                    .border(1.dp, c.border, shape)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = c.textDim,
                        fontSize = 12.5.sp,
                        fontFamily = if (mono) SpectrumMono else SpectrumSans,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
fun SpectrumDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = Spectrum.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.dialog)
                .border(1.dp, c.borderStrong, RoundedCornerShape(22.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dismissText != null) {
                    GhostButton(text = dismissText, onClick = onDismiss, height = 40.dp)
                }
                GradientButton(text = confirmText, onClick = onConfirm, height = 40.dp)
            }
        }
    }
}

/** 产物占位图：没有真图时也不留一块死黑，沿用光谱的暖色采样质感。 */
@Composable
fun SpectrumArt(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(Color(0xFF12161C))
        fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        blob(Color(0xFFFF9A63), size.width * 0.30f, size.height * 0.22f, size.width * 0.58f)
        blob(Color(0xFF7B5CFF), size.width * 0.76f, size.height * 0.28f, size.width * 0.46f)
        blob(Color(0xFF24C79A), size.width * 0.54f, size.height * 0.84f, size.width * 0.66f)
        blob(Color(0xFFFFD166), size.width * 0.18f, size.height * 0.78f, size.width * 0.40f)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Canvas(modifier = Modifier.height(size).width(size)) {
        drawCircle(color = color)
    }
}
