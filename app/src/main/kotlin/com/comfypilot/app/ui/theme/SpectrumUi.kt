package com.comfypilot.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
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

/**
 * 全屏背景：底色 + 三层色晕。所有页面共用同一层，切换屏幕不闪。
 * 光晕用 14 秒循环做极小幅度的呼吸（半径 ±4%、透明度 0.92~1），肉眼几乎察觉不到，
 * 但静态背景会显得“死”，动起来更像一块活的玻璃。
 */
@Composable
fun SpectrumBackdrop(modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    val breath = rememberInfiniteTransition(label = "backdrop")
    val breathe by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "backdrop-breathe",
    )
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

        // 呼吸幅度刻意收着：半径 ±6% 左右、亮度 0.82~1.0 倍、中心漂移 2-3% 屏宽。
        // 目标是"放着不动也能看出在缓缓流动"，但不会让人一会儿觉得背景发青、一会儿发紫。
        val t1 = breathe
        val t2 = 1f - breathe
        glow(
            color = c.glowTop.copy(alpha = (c.glowTop.alpha * (0.82f + 0.18f * t1)).coerceIn(0f, 1f)),
            cx = w * (0.86f - 0.02f * t1),
            cy = -h * 0.01f + h * 0.015f * t1,
            radius = w * (1.0f + 0.06f * t1),
        )
        glow(
            color = c.glowSide.copy(alpha = (c.glowSide.alpha * (0.82f + 0.18f * t2)).coerceIn(0f, 1f)),
            cx = w * (0.02f + 0.025f * t2),
            cy = h * (0.13f + 0.02f * t2),
            radius = w * (0.95f + 0.06f * t2),
        )
        glow(
            color = c.glowBottom.copy(alpha = (c.glowBottom.alpha * (0.86f + 0.14f * t1)).coerceIn(0f, 1f)),
            cx = w * (0.55f + 0.02f * t2),
            cy = h * (1.02f - 0.015f * t1),
            radius = w * (1.1f + 0.05f * t2),
        )
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
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.42f)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(14.dp))
            .background(c.gradientBrush())
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
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
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .pressFeedback(interaction)
            .clip(shape)
            .background(if (selected) c.cardStrong else c.card)
            .border(1.dp, if (selected) c.accentBorder else c.borderStrong, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
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
    val chipInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = base
            .pressFeedback(chipInteraction, pressedScale = 0.95f)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .clickable(
                interactionSource = chipInteraction,
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

/**
 * 按压反馈：按下时轻微缩放 + 降低不透明度。给所有自绘按钮共用，
 * 比默认 ripple 更符合光谱主题的“轻”质感。
 */
fun Modifier.pressFeedback(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.965f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 900f),
        label = "press-scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 分段页签：一块会滑动的底板跟着选中项走，而不是每项各自闪一下。
 * 底板颜色用 cardStrong（深色主题下接近白），切换时带一点回弹。
 */
@Composable
fun SpectrumTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(14.dp)
    val gap = 3.dp
    val inner = RoundedCornerShape(11.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .padding(3.dp),
    ) {
        val count = labels.size.coerceAtLeast(1)
        val itemWidth = ((maxWidth - gap * (count - 1)) / count).coerceAtLeast(0.dp)
        val targetX = (itemWidth + gap) * selectedIndex.coerceIn(0, count - 1)
        val slide by animateFloatAsState(
            targetValue = targetX.value,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 700f),
            label = "tab-slide",
        )
        val widthSlide by animateFloatAsState(
            targetValue = itemWidth.value,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 700f),
            label = "tab-width",
        )
        Box(modifier = Modifier.fillMaxWidth().height(38.dp).clipToBounds()) {
            Box(
                modifier = Modifier
                    .offset(x = slide.dp)
                    .width(widthSlide.dp)
                    .fillMaxHeight()
                    .clip(inner)
                    .background(c.cardStrong),
            )
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                labels.forEachIndexed { index, label ->
                    val active = index == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = if (index < labels.lastIndex) gap else 0.dp)
                            .clip(inner)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) { onSelect(index) }
                            .pressFeedback(interaction, pressedScale = 0.94f),
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
    }
}

/** 骨架块：加载态用它占位，来回扫一道高光，比转圈更贴合卡片布局。 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val c = Spectrum.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(c.card),

    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val band = w * 0.42f
            val start = -band + (w + band) * progress
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, c.borderStrong.copy(alpha = 0.55f), Color.Transparent),
                    start = Offset(start, 0f),
                    end = Offset(start + band, size.height),
                ),
                size = size,
            )
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
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(c.barTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
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
    /** 长文本（提示词等）关掉单行：按 `minLines` 起步，超过 12 行内部滚动。 */
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 12,
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
/**
 * 空状态用的轻浮动：内容缓慢上下 3dp，透明度在 0.82~1 之间呼吸。
 * 只在"没内容"的页面上用，避免满屏都在动。
 */
@Composable
fun GentleFloat(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "gentle-float")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gentle-float-phase",
    )
    Box(
        modifier = modifier.graphicsLayer {
            translationY = -3.dp.toPx() * phase
            alpha = 0.82f + 0.18f * phase
        },
    ) {
        content()
    }
}
