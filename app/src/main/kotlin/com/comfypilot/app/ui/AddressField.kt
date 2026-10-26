package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono

/**
 * ComfyUI 地址输入：默认填上次用过的地址，点输入框（或右侧箭头）展开历史列表。
 *
 * 历史只在本机保存；选中或失焦时把当前值写回默认地址。
 */
@Composable
fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    history: List<String>,
    onPickHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onCommit: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = Spectrum.colors
    var menuOpen by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(12.dp)
    val items = history

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { fieldSize = it }
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.border, shape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            menuOpen = true
                        } else {
                            menuOpen = false
                            onCommit(value)
                        }
                    },
                singleLine = true,
                textStyle = TextStyle(
                    color = c.text,
                    fontSize = 12.5.sp,
                    fontFamily = SpectrumMono,
                ),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = c.textDim,
                                fontSize = 12.5.sp,
                                fontFamily = SpectrumMono,
                            )
                        }
                        inner()
                    }
                },
            )
            Text(
                text = "▾",
                color = c.textDim,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { menuOpen = !menuOpen }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }

        if (menuOpen) {
            val density = LocalDensity.current
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldSize.height + with(density) { 6.dp.roundToPx() }),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(with(density) { fieldSize.width.coerceAtLeast(1).toDp() })
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.dialog)
                        .border(1.dp, c.border, RoundedCornerShape(18.dp))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "最近的地址",
                        color = c.textDim,
                        fontSize = 9.sp,
                        fontFamily = SpectrumMono,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    if (items.isEmpty()) {
                        Text(
                            text = "还没有保存过的地址",
                            color = c.textMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    items.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                thickness = 1.dp,
                                color = c.border,
                            )
                        }
                        val current = item == value.trim()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    menuOpen = false
                                    onPickHistory(item)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = item,
                                color = c.text,
                                fontSize = 11.5.sp,
                                fontFamily = SpectrumMono,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (current) {
                                Text(
                                    text = "当前",
                                    color = c.accent,
                                    fontSize = 9.sp,
                                    fontFamily = SpectrumMono,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(c.accentSoft)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = "✕",
                                color = c.textDim,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        onDeleteHistory(item)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}