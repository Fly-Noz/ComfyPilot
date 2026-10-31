package com.comfypilot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * AI 图标：四角星光晕。
 *
 * 为什么不用「AI」两个字：20dp 的头像里两个字母要么糊成一团，要么得描边才看得清；
 * 星光在不同尺寸下都成立，也是现在大家认「AI」的通用符号。
 *
 * 路径画在 24×24 的坐标系里，靠 scale 缩到目标尺寸，所有调用点共用同一条曲线——
 * 鸿蒙那边 `AI_GLYPH` 常量是同一组数，改形状要两边一起改。
 *
 * @param single 小尺寸（≤14dp）时只画主星。两颗星缩到 20dp 头像里，小的那颗会退化成
 *   一个点，反而显得脏。
 */
@Composable
fun AiGlyph(
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    single: Boolean = false,
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        drawPath(glyphPath(scale, single), tint)
    }
}

/** 24×24 坐标系下的图标路径，按 [scale] 缩放。 */
private fun glyphPath(scale: Float, single: Boolean): Path = Path().apply {
    if (single) {
        // 主星居中放大：小尺寸下要占满画布才够看。
        moveTo(12f * scale, 2f * scale)
        quadraticTo(15.6f * scale, 8.4f * scale, 22f * scale, 12f * scale)
        quadraticTo(15.6f * scale, 15.6f * scale, 12f * scale, 22f * scale)
        quadraticTo(8.4f * scale, 15.6f * scale, 2f * scale, 12f * scale)
        quadraticTo(8.4f * scale, 8.4f * scale, 12f * scale, 2f * scale)
        close()
    } else {
        // 主星偏左下，右上留一颗小星：两颗一起才读得出「闪」的意思。
        moveTo(9.6f * scale, 5f * scale)
        quadraticTo(12.5f * scale, 10.5f * scale, 18f * scale, 13.4f * scale)
        quadraticTo(12.5f * scale, 16.3f * scale, 9.6f * scale, 21.8f * scale)
        quadraticTo(6.7f * scale, 16.3f * scale, 1.2f * scale, 13.4f * scale)
        quadraticTo(6.7f * scale, 10.5f * scale, 9.6f * scale, 5f * scale)
        close()
        moveTo(19.8f * scale, 1.2f * scale)
        quadraticTo(21f * scale, 3.4f * scale, 23.2f * scale, 4.6f * scale)
        quadraticTo(21f * scale, 5.8f * scale, 19.8f * scale, 8f * scale)
        quadraticTo(18.6f * scale, 5.8f * scale, 16.4f * scale, 4.6f * scale)
        quadraticTo(18.6f * scale, 3.4f * scale, 19.8f * scale, 1.2f * scale)
        close()
    }
}

/**
 * 附图按钮的图标：一张画（外框 + 太阳 + 山）。
 *
 * 原来这里是个「图」字，和旁边「↑」的图形按钮并排时，一个是字一个是图，视觉上不齐；
 * 而且中文单字在圆形按钮里要么偏上要么偏右，得靠 padding 硬调。
 *
 * 全部用描边画（不填充），和 Feather 那类图标的粗细接近，16dp 下也不糊。
 */
@Composable
fun AiImageGlyph(
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)

        drawRoundRect(
            color = tint,
            topLeft = Offset(3.2f * s, 5.2f * s),
            size = Size(17.6f * s, 13.6f * s),
            cornerRadius = CornerRadius(3f * s, 3f * s),
            style = stroke,
        )
        drawCircle(tint, radius = 1.5f * s, center = Offset(8.5f * s, 9.3f * s))
        val hill = Path().apply {
            moveTo(4.8f * s, 17.2f * s)
            lineTo(9.6f * s, 11.8f * s)
            lineTo(13.3f * s, 15.6f * s)
            lineTo(15.7f * s, 13.2f * s)
            lineTo(19.2f * s, 16.9f * s)
        }
        drawPath(hill, tint, style = stroke)
    }
}
