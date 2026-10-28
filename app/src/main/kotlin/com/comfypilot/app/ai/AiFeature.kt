package com.comfypilot.app.ai

/**
 * AI 助手的对外开关。
 *
 * 链路（循环、工具往返、diff 确认）已经跑通，但体验和稳定性还没到能交给用户的程度，
 * 所以这次发布先整体关掉：设置页保留，看得见但开不了。
 *
 * 为什么不直接把代码删掉：这些代码还要继续迭代，删了就得重写；用一处常量收口，
 * 打开时只改这里，别在多处零散地加判断。
 */
object AiFeature {
    /** false：入口全部关闭（悬浮球隐藏、开关不可点、发送直接拒绝）。 */
    const val available: Boolean = false

    /** 设置页上的说明。 */
    const val notice: String = "该功能尚在开发中，尚不可用，敬请期待"

    /** 把「不可用」这件事压到配置上：老用户库里存的 enabled=true 也不生效。 */
    fun gate(config: AiConfig): AiConfig = if (available) config else config.copy(enabled = false)
}
