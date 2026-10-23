package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onCommit: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = Spectrum.colors
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val items = remember(history, value) {
        // 当前输入值不重复出现在历史里。
        history.filter { it != value.trim() }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                            menuOpen = items.isNotEmpty()
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

        DropdownMenu(
            expanded = menuOpen && items.isNotEmpty(),
            onDismissRequest = { menuOpen = false },
            containerColor = c.dialog,
            properties = PopupProperties(focusable = false),
        ) {
            Text(
                text = "最近使用",
                color = c.textDim,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item,
                            color = c.text,
                            fontSize = 11.5.sp,
                            fontFamily = SpectrumMono,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onPickHistory(item)
                    },
                )
            }
        }
    }
}