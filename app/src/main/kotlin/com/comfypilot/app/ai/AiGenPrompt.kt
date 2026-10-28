package com.comfypilot.app.ai

/**
 * 按工作流的生成模型给出提示词写法。
 *
 * 为什么不用模型名精确匹配：ComfyUI 里同一模型的文件名千奇百怪
 * （qwen_image_2512_fp8.safetensors、flux1-dev-kontext、ltxv-13b-0.9.8 等），
 * 关键词命中比白名单更稳。参考的官方文档：
 * - Qwen-Image README：整段自然语言，官方正向后缀「超清，4K，电影级构图」，附负向基线；
 * - BFL FLUX Prompting Guide：主体/场景/风格/光线/构图，不堆 tag；
 * - LTX-Video README：按时间顺序的单段、≤200 词、含镜头运动。
 *
 * 规则要和鸿蒙的 AiPrompt.ets 保持一致，别只改一边。
 */
object AiGenPrompt {

    fun detect(text: String): String {
        val id = text.lowercase()
        return when {
            id.contains("qwen") && id.contains("image") -> "qwen-image"
            id.contains("flux") -> "flux"
            id.contains("ltx") -> "ltx-video"
            id.contains("wan2") || id.contains("wan-") -> "wan-video"
            id.contains("hunyuan") -> "hunyuan-video"
            id.contains("minimax") -> "minimax-video"
            id.contains("ace") && id.contains("step") -> "ace-step"
            id.contains("index") && id.contains("tts") -> "tts"
            id.contains("ernie") -> "ernie-image"
            id.contains("krea") -> "krea"
            id.contains("sdxl") || id.contains("sd_xl") -> "sdxl"
            id.contains("pony") || id.contains("v1-5") || id.contains("sd15") -> "sd"
            else -> "generic"
        }
    }

    fun guide(key: String): String = when (key) {
        "qwen-image" -> "这是 Qwen-Image 系列：用一整段自然语言，按「主体与外貌 → 动作/表情 → " +
            "服装材质 → 环境背景 → 构图镜头 → 光线色调」写，不要逗号堆 tag。" +
            "画面里要出现的文字直接写原文（中文也可以，它擅长文字渲染）。" +
            "官方做法：正向结尾追加「，超清，4K，电影级构图」；负向可用「低分辨率，低画质，肢体畸形，" +
            "手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感，构图混乱，文字模糊，扭曲」。"

        "flux" -> "这是 FLUX 系列：用自然语言写清主体、场景、风格、光线与构图，不要用 SD 风格的 " +
            "tag 堆叠；用参考图时说明是「图 1 / 图 2」以及各自的位置。"

        "ltx-video" -> "这是 LTX-Video：写成一条按时间顺序推进的段落（≤200 词）——主要动作 → " +
            "具体动作/手势 → 主体外观 → 背景环境 → 镜头角度与运动 → 光线色彩 → 变化或意外。" +
            "开头直接写动作，用词字面、具体，像在写分镜。" +
            "★用户给了图时：不要复述画面，图已经喂给模型了；你要写「这张图怎么动起来」——" +
            "主体做什么动作、往哪动、速度快慢、镜头推拉摇移、光线氛围如何变化，必要时写首末帧关系。" +
            "只描述静物是错的。"

        "wan-video", "hunyuan-video", "minimax-video" -> "这是视频模型：一条段落写清主体动作、场景、" +
            "镜头运动与光线；强调「怎么动、往哪动、镜头怎么走」，不要只写静态画面。" +
            "★用户给了图时：不要复述画面，写这张图如何动起来——动作过程、运动方向、镜头运动、时间上的变化。"

        "ace-step" -> "这是音乐生成：写风格、主要乐器、情绪、速度（BPM）与结构（前奏/主歌/副歌），" +
            "避免「好听的歌」这类空泛描述。"

        "tts" -> "这是语音合成：直接写要朗读的文本，用标点控制停顿；语速、情绪等要求单独说明。"

        "sd", "sdxl" -> "这是 SD / SDXL 系：用英文逗号分隔的标签（主体、风格、画质词），" +
            "负向提示词单独写，不要把正向词重复进负向。"

        else -> "按当前工作流的模型习惯写提示词：具体描述主体、动作、环境、构图与光线，别用空泛形容词。"
    }

    /** 组合成注入给模型的系统消息；[promptFields] 形如 `#571.text`。 */
    fun build(tokens: List<String>, promptFields: List<String>): String {
        val guide = guide(detect(tokens.joinToString(" ")))
        if (promptFields.isEmpty()) return guide
        return guide + "\n本工作流的提示词字段：" +
            promptFields.take(6).joinToString("、") + "（直接改这些，不要新建节点）。"
    }
}
