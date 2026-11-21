package com.comfypilot.core.workflow

/**
 * 前端虚拟节点：ComfyUI 的 /object_info 不会返回它们，但它们出现在
 * 保存下来的 UI 工作流里。不能简单按"缺失自定义节点"阻断，也不能
 * 一律跳过——每种节点在 graphToPrompt 里的语义不同。
 */
internal enum class VirtualNodeBehavior {
    /** 纯视觉/控制节点，不进 prompt，也没有上下游数据需要穿透。 */
    SKIP,

    /** 一个输入一个输出，值直接穿透；例如 Reroute / Mute-Bypass Repeater。 */
    PASSTHROUGH,

    /** 携带一个字面量，转换时写进下游，例如 PrimitiveNode。 */
    PRIMITIVE,

    /** 按名字写入的总线，例如 SetNode。 */
    SET_BUS,

    /** 按名字读取的总线，例如 GetNode。 */
    GET_BUS,
}

internal object VirtualNodes {

    private val HANDLERS: Map<String, VirtualNodeBehavior> = mapOf(
        // ComfyUI 自带前端节点
        "Note" to VirtualNodeBehavior.SKIP,
        "MarkdownNote" to VirtualNodeBehavior.SKIP,
        "Reroute" to VirtualNodeBehavior.PASSTHROUGH,
        "PrimitiveNode" to VirtualNodeBehavior.PRIMITIVE,

        // rgthree / KJNodes 常用前端节点
        "SetNode" to VirtualNodeBehavior.SET_BUS,
        "GetNode" to VirtualNodeBehavior.GET_BUS,

        // ComfyUI-Easy-Use 的 Set/Get 前端总线（基于 diffus3 的实现）
        "easy setNode" to VirtualNodeBehavior.SET_BUS,
        "easy getNode" to VirtualNodeBehavior.GET_BUS,
        "easy bookmark" to VirtualNodeBehavior.SKIP,
        "Label (rgthree)" to VirtualNodeBehavior.SKIP,
        "Fast Groups Bypasser (rgthree)" to VirtualNodeBehavior.SKIP,
        "Fast Groups Muter (rgthree)" to VirtualNodeBehavior.SKIP,
        "Image Comparer (rgthree)" to VirtualNodeBehavior.SKIP,
        "Bookmark (rgthree)" to VirtualNodeBehavior.SKIP,
        "Mute / Bypass Repeater (rgthree)" to VirtualNodeBehavior.PASSTHROUGH,
    )

    /** 所有已知前端虚拟节点，用于跳过 /object_info 请求和缺失校验。 */
    val types: Set<String> get() = HANDLERS.keys

    fun behaviorOf(type: String): VirtualNodeBehavior? = HANDLERS[type]

    fun isVirtual(type: String): Boolean = type in HANDLERS
}
