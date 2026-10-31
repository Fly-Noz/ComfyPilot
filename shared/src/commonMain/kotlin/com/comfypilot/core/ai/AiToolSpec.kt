package com.comfypilot.core.ai

/**
 * 工具声明的唯一来源。
 *
 * 为什么放在共享层：Android 与鸿蒙以前各写一份 JSON，鸿蒙那份一度少了
 * list_graphs / list_nodes / get_node，模型在鸿蒙上根本不会去查节点——这是
 * 真实发生过的协议漂移。现在两端都读这一份，改工具只改这里。
 *
 * 注意：这里只管「声明」。循环在 [AiLoopState]（Android 已接入），
 * 鸿蒙那份等 `cp_ai_step` / `cp_ai_send` 落地后删除。
 */
object AiToolSpec {
    val json: String = """
[
 {"type":"function","function":{"name":"propose_edits","description":"对当前工作流提交一组声明式修改","parameters":{"type":"object","properties":{"summary":{"type":"string","description":"一句话说明这次改了什么"},"ops":{"type":"array","items":{"type":"object","properties":{"op":{"type":"string"},"node":{"type":"integer"},"input":{"type":"string"},"enabled":{"type":"boolean"},"graphs":{"type":"array","items":{"type":"string"}},"path":{"type":"string"},"value":{}},"required":["op"]}}},"required":["summary","ops"]}}},
 {"type":"function","function":{"name":"submit","description":"提交当前工作流","parameters":{"type":"object","properties":{}}}},
 {"type":"function","function":{"name":"task_status","description":"查看最近任务的状态/进度/产物；wait_seconds 可等待任务跑完（最多 120 秒）","parameters":{"type":"object","properties":{"wait_seconds":{"type":"integer"}}}}},
 {"type":"function","function":{"name":"open_workflow","description":"切换到另一个工作流","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
 {"type":"function","function":{"name":"ask_user","description":"需要用户确认或选择时提问","parameters":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}},"required":["question","options"]}}},
 {"type":"function","function":{"name":"list_graphs","description":"列出工作流里的节点图及选中状态","parameters":{"type":"object","properties":{}}}},
 {"type":"function","function":{"name":"list_nodes","description":"按标题/类型/id 搜索节点，返回轻量列表","parameters":{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}}}}},
 {"type":"function","function":{"name":"get_node","description":"读取单个节点的字段、当前值、COMBO 候选","parameters":{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}}},
 {"type":"function","function":{"name":"lookup_prompt_guide","description":"搜索某个生成模型的提示词规范，返回标题/链接/摘要；挑一个再用 fetch_doc 读正文","parameters":{"type":"object","properties":{"model":{"type":"string","description":"模型名或文件名，例如 flux1-dev、Qwen-Image"}},"required":["model"]}}},
 {"type":"function","function":{"name":"fetch_doc","description":"抓取一个 https 文档地址的正文；用于读官方 README/提示词指南","parameters":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}},
 {"type":"function","function":{"name":"host_status","description":"查看跑 ComfyUI 的那台机器：显卡型号与显存、内存、ComfyUI / Python / PyTorch 版本。判断模型放不放得下、为什么 OOM、该不该降分辨率时先看它，别猜","parameters":{"type":"object","properties":{}}}},
 {"type":"function","function":{"name":"list_models","description":"列出服务器上实际可用的模型文件。换模型前必须先查，不要凭记忆编文件名——服务器上没有的名字会直接提交失败","parameters":{"type":"object","properties":{"kind":{"type":"string","enum":["checkpoint","lora","unet","vae","clip","clip_vision","controlnet","upscale","embedding"],"description":"要找哪一类；embedding 是文本反演"}},"required":["kind"]}}},
 {"type":"function","function":{"name":"upload_image","description":"把用户随消息附上的图片上传到 ComfyUI，返回服务器文件名。只有要把这张图真正用进工作流时（通常是写进 LoadImage 的 image 字段）才调用；只想看图、回答关于图片的问题时不需要上传。index 从 1 开始，对应消息里「附图」的序号；也可以改用 name 指定原文件名","parameters":{"type":"object","properties":{"index":{"type":"integer","description":"第几张附图，从 1 开始"},"name":{"type":"string","description":"图的原文件名，跨轮次引用时用"}}}}}
]
""".trimIndent()
}
