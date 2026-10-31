package com.comfypilot.app.ai

/**
 * AI 助手的对外开关。
 *
 * 0.2.1 时整体关过一版（体验没打磨完）；现在打开。这里是唯一的收口点：
 * 要再关回去只改 [available]，别在别处零散地加判断。
 */
object AiFeature {
    /** false：入口全部关闭（悬浮球隐藏、开关不可点、发送直接拒绝）。 */
    const val available: Boolean = true

    /** 关闭时设置页上的说明；打开后不再显示。 */
    const val notice: String = "该功能尚在开发中，尚不可用，敬请期待"

    /** 把「不可用」这件事压到配置上：老用户库里存的 enabled=true 也不生效。 */
    fun gate(config: AiConfig): AiConfig = if (available) config else config.copy(enabled = false)
}
