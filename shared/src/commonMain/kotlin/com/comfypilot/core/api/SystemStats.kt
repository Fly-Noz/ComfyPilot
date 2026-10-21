package com.comfypilot.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ComfyUI `/system_stats` 的子集。
 *
 * 官方路由只提供内存/显存总量与可用量等，没有 CPU 利用率；界面不要假装有。
 */
@Serializable
data class SystemStats(
    val system: SystemInfo = SystemInfo(),
    val devices: List<DeviceStats> = emptyList(),
)

@Serializable
data class SystemInfo(
    val os: String? = null,
    @SerialName("ram_total") val ramTotal: Long = 0L,
    @SerialName("ram_free") val ramFree: Long = 0L,
    @SerialName("comfyui_version") val comfyuiVersion: String? = null,
    @SerialName("python_version") val pythonVersion: String? = null,
    @SerialName("pytorch_version") val pytorchVersion: String? = null,
)

@Serializable
data class DeviceStats(
    val name: String = "",
    val type: String = "",
    val index: Int? = null,
    @SerialName("vram_total") val vramTotal: Long = 0L,
    @SerialName("vram_free") val vramFree: Long = 0L,
)
