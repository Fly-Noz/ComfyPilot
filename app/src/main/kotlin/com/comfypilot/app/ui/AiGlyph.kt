package com.comfypilot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
