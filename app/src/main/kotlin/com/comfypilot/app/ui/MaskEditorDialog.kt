package com.comfypilot.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayOutputStream
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

private data class MaskStroke(
    val points: List<Offset>,
    val size: Float,
    val erase: Boolean,
)

/**
 * 手机端蒙版编辑：在原图上涂抹，保存成带 alpha 的 PNG。
 *
 * ComfyUI 的 LoadImage 把 MASK 定义为 1 - alpha，所以这里把涂抹区写成透明，
 * 未涂抹区保留原图不透明度；JPEG 原图也能得到正确结果。
 */
@Composable
fun MaskEditorDialog(
    image: Bitmap,
    onDismiss: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    val c = Spectrum.colors
    var strokes by remember(image) { mutableStateOf<List<MaskStroke>>(emptyList()) }
    var active by remember(image) { mutableStateOf<MaskStroke?>(null) }
    var erasing by remember(image) { mutableStateOf(false) }
    var brushScreen by remember(image) { mutableFloatStateOf(34f) }
    var version by remember(image) { mutableIntStateOf(0) }
    var boxSize by remember(image) { mutableStateOf(IntSize.Zero) }
    val mask = remember(image) {
        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    fun drawStroke(stroke: MaskStroke, canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke.size
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = if (stroke.erase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        }
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            canvas.drawCircle(points[0].x, points[0].y, max(1f, stroke.size / 2f), paint.apply { style = Paint.Style.FILL })
            return
        }
        for (i in 1 until points.size) {
            canvas.drawLine(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, paint)
        }
    }

    fun rebuild() {
        mask.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(mask)
        strokes.forEach { drawStroke(it, canvas) }
        version++
    }

    fun addPoint(point: Offset) {
        val current = active
        if (current == null) {
            active = MaskStroke(listOf(point), brushScreen, erasing)
        } else {
            active = current.copy(points = current.points + point)
        }
    }

    fun imagePoint(screen: Offset, scale: Float, dx: Float, dy: Float): Offset = Offset(
        ((screen.x - dx) / scale).coerceIn(0f, image.width.toFloat()),
        ((screen.y - dy) / scale).coerceIn(0f, image.height.toFloat()),
    )

    fun scaleAndOffset(): Triple<Float, Float, Float> {
        val box = boxSize
        if (box.width <= 0 || box.height <= 0) return Triple(1f, 0f, 0f)
        val scale = min(box.width.toFloat() / image.width, box.height.toFloat() / image.height)
        return Triple(scale, (box.width - image.width * scale) / 2f, (box.height - image.height * scale) / 2f)
    }

    fun begin(point: Offset) {
        val (scale, dx, dy) = scaleAndOffset()
        val p = imagePoint(point, scale, dx, dy)
        active = MaskStroke(listOf(p), brushScreen / scale, erasing)
        drawStroke(active!!, Canvas(mask))
        version++
    }

    fun move(point: Offset) {
        val current = active ?: return
        val (scale, dx, dy) = scaleAndOffset()
        val p = imagePoint(point, scale, dx, dy)
        val last = current.points.last()
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = current.size
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = if (current.erase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        }
        canvas.drawLine(last.x, last.y, p.x, p.y, paint)
        active = current.copy(points = current.points + p)
        version++
    }

    fun end() {
        val current = active ?: return
        strokes = strokes + current
        active = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MaskIconButton(glyph = "‹", onClick = onDismiss)
                    Text(
                        text = "编辑蒙版",
                        color = c.text,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    MaskIconButton(glyph = "✓", emphasized = true, onClick = {
                        if (strokes.isEmpty() && active == null) {
                            onSave(image)
                        } else {
                            onSave(composeMask(image, mask))
                        }
                    })
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onSizeChanged { boxSize = it }
                        .background(ComposeColor.Black.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                        .then(
                            Modifier.pointerInput(image, boxSize, brushScreen, erasing) {
                                detectTapGestures { p -> begin(p); end() }
                            },
                        )
                        .then(
                            Modifier.pointerInput(image, boxSize, brushScreen, erasing) {
                                detectDragGestures(
                                    onDragStart = { p -> begin(p) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        move(change.position)
                                    },
                                    onDragEnd = { end() },
                                )
                            },
                        ),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        version // read state so mutations repaint
                        val (scale, dx, dy) = scaleAndOffset()
                        withTransform({
                            translate(dx, dy)
                            scale(scale, scale, pivot = Offset.Zero)
                        }) {
                            drawImage(image.asImageBitmap())
                            drawImage(mask.asImageBitmap(), alpha = 0.62f)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MaskIconButton(glyph = "✎", active = !erasing, onClick = { erasing = false })
                    MaskIconButton(glyph = "⌫", active = erasing, onClick = { erasing = true })
                    MaskIconButton(glyph = "↺", onClick = {
                        if (strokes.isNotEmpty()) {
                            strokes = strokes.dropLast(1)
                            rebuild()
                        }
                    })
                    MaskIconButton(glyph = "✕", onClick = {
                        mask.eraseColor(Color.TRANSPARENT)
                        strokes = emptyList()
                        active = null
                        version++
                    })
                    Slider(
                        value = brushScreen,
                        onValueChange = { brushScreen = it },
                        valueRange = 8f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = c.accent,
                            activeTrackColor = c.accent,
                            inactiveTrackColor = c.cardStrong,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MaskIconButton(
    glyph: String,
    active: Boolean = false,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Spectrum.colors
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                if (active || emphasized) c.accentSoft else c.card,
                shape,
            )
            .border(1.dp, if (active || emphasized) c.accentBorder else c.border, shape)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (active || emphasized) c.accent else c.textMuted,
            fontSize = 20.sp,
            fontFamily = SpectrumMono,
        )
    }
}

private fun composeMask(image: Bitmap, mask: Bitmap): Bitmap {
    val out = image.copy(Bitmap.Config.ARGB_8888, true)
    val src = IntArray(image.width * image.height)
    out.getPixels(src, 0, image.width, 0, 0, image.width, image.height)
    val maskPixels = IntArray(mask.width * mask.height)
    mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)
    for (i in src.indices) {
        val originalAlpha = (src[i] ushr 24) and 0xFF
        val maskAlpha = (maskPixels[i] ushr 24) and 0xFF
        val alpha = originalAlpha * (255 - maskAlpha) / 255
        src[i] = (src[i] and 0x00FFFFFF) or (alpha shl 24)
    }
    out.setPixels(src, 0, image.width, 0, 0, image.width, image.height)
    return out
}

/** 从 ComfyUI /view(input) 拉原图；供重启后没有本地缩略图时编辑蒙版。 */
fun loadMaskSource(url: String): Bitmap? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 15_000
        requestMethod = "GET"
    }
    connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
}.getOrNull()
