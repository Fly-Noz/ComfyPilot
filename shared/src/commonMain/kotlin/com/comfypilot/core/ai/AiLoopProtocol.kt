package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 循环的 JSON 协议：鸿蒙的 `cp_ai_step` / `cp_ai_send` 就是它的两个入口。
 *
 * 为什么单独一层：ArkTS 那边只认字符串进、字符串出，而跨语言边界最难查的就是
 * 「请求少了个字段、动作名字对不上」。把协议放在 commonMain，就能在 JVM 上
 * 按 ArkTS 的真实报文把整条链路跑一遍，不用上真机。
 *
 * 工具调用也要过 [AiToolRouter]：参数校验、`open_workflow` 的工作区白名单都在那里。
 * 校验不过就直接把错误回填给模型（不打扰宿主），过了才发 `call_host` 让 ArkTS 执行。
 */
class AiLoopProtocol {

    private var loop: AiLoopState? = null

    /** 最近一次交给宿主的工具名：回填结果时要按它补措辞（submit 的核实提示）。 */
    private var currentTool: String = ""

    /** 同一个请求重放时直接给上次的动作，见 [replay]。 */
    private var lastRequest: String? = null
    private var lastAction: String? = null

    /**
     * 开一轮：`op=start` 带上下文，或者 `op=resume` 表示用户回答了提问/确认了提案。
     *
     * 请求：
     * ```json
     * {"op":"start","context":{"model":"...","system":["..."],"temperature":0.2,
     *   "paths":["a.json"]},"history":[],"userTurn":{"role":"user","content":"..."}}
     * ```
     */
    fun step(requestJson: String): String = replay(requestJson) {
        runCatching {
            val root = Json.parseToJsonElement(requestJson) as? JsonObject
                ?: return@runCatching errorAction("请求不是 JSON 对象")
            val context = contextOf(root["context"])
            when (root.text("op").ifBlank { "start" }) {
                "start" -> {
                    val userTurn = root["userTurn"] as? JsonObject
                        ?: return@runCatching errorAction("userTurn 为空")
                    val history = (root["history"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    val state = AiLoopState()
                    state.start(userTurn, history)
                    loop = state
                    currentTool = ""
                    advance(state, state.nextRequest(context), context)
                }

                "resume" -> {
                    val state = loop ?: return@runCatching errorAction("AI 循环还没有开始")
                    state.resume(root["userTurn"] as? JsonObject)
                    advance(state, state.nextRequest(context), context)
                }

                else -> errorAction("未知的 op：${root.text("op")}")
            }
        }.getOrElse { errorAction(it.message ?: "AI 循环启动失败") }
    }

    /**
     * 推进循环：`reply` 交回模型响应，`tool` 交回工具结果，`resume` 也可以走这里。
     *
     * 工具结果按**原始**形态送（例如 submit 的 `{"ok":true}`），措辞由
     * [AiToolRouter.formatResult] 统一收尾；等用户确认或回答时带
     * `"pause":"pending"` / `"pause":"question","question":"..."`。
     */
    fun send(requestJson: String): String = replay(requestJson) {
        runCatching {
            val root = Json.parseToJsonElement(requestJson) as? JsonObject
                ?: return@runCatching errorAction("请求不是 JSON 对象")
            val state = loop ?: return@runCatching errorAction("AI 循环还没有开始")
            val context = contextOf(root["context"])
            when (root.text("op")) {
                "reply" -> {
                    val message = root["message"] as? JsonObject
                        ?: return@runCatching errorAction("message 为空")
                    advance(state, state.acceptReply(message), context)
                }

                "tool" -> {
                    when (root.text("pause")) {
                        "pending" -> state.markPending()
                        "question" -> state.markQuestion(root.text("question").ifBlank { "AI 需要你确认" })
                    }
                    val result = AiToolRouter.formatResult(currentTool, root.text("result"))
                    advance(state, state.acceptToolResult(root.text("callId"), result, context), context)
                }

                "resume" -> {
                    state.resume(root["userTurn"] as? JsonObject)
                    advance(state, state.nextRequest(context), context)
                }

                else -> errorAction("未知的 op：${root.text("op")}")
            }
        }.getOrElse { errorAction(it.message ?: "AI 循环推进失败") }
    }

    /**
     * 把一步推到底：需要宿主做的工具原样交给宿主；参数被路由器判掉的就回填错误继续走。
     *
     * 「回填错误继续走」很重要——`open_workflow` 指到工作区外时，模型该拿到那句
     * 「不在当前 AI 工作区内」再去纠正，而不是让 ArkTS 执行一次越界的切换。
     */
    private fun advance(state: AiLoopState, step: AiLoopStep, context: AiLoopContext): String {
        var current = step
        while (true) {
            if (current !is AiLoopStep.CallTool) return render(current)
            val outcome = AiToolRouter.execute(current.name, current.args, AbiEnv(context.paths))
            if (outcome is AiToolOutcome.Value) {
                val result = AiToolRouter.formatResult(current.name, outcome.json)
                current = state.acceptToolResult(current.callId, result, context)
                continue
            }
            return render(current)
        }
    }

    /**
     * 同一个请求重放时返回上次的动作。
     *
     * NAPI 层在动作超过缓冲区时会扩容再调一次；那次调用必须得到**同一个**动作，
     * 不能把 acceptReply / acceptToolResult 再算一遍（工具结果会被判成过期）。
     */
    private fun replay(request: String, advance: (String) -> String): String {
        val cached = lastAction
        if (cached != null && lastRequest == request) return cached
        val action = advance(request)
        lastRequest = request
        lastAction = action
        return action
    }

    /** 把状态机的下一步翻成动作 JSON。 */
    private fun render(step: AiLoopStep): String = when (step) {
        is AiLoopStep.Http -> buildJsonObject {
            put("action", "http")
            put("body", step.body)
        }.toString()

        is AiLoopStep.CallTool -> {
            currentTool = step.name
            buildJsonObject {
                put("action", "call_host")
                put("callId", step.callId)
                put("name", step.name)
                put("args", step.args)
            }.toString()
        }

        is AiLoopStep.Done -> doneAction(step.reason, step.text)
    }

    private fun doneAction(reason: AiDoneReason, text: String?): String = buildJsonObject {
        put("action", "done")
        put("reason", reason.wire)
        if (text != null) put("text", text)
    }.toString()

    private fun errorAction(message: String): String = doneAction(AiDoneReason.ERROR, message)

    private fun contextOf(element: JsonElement?): AiLoopContext {
        val obj = element as? JsonObject ?: return AiLoopContext(model = "")
        val system = (obj["system"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val paths = (obj["paths"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return AiLoopContext(
            model = obj.text("model"),
            system = system,
            temperature = obj.text("temperature").toDoubleOrNull() ?: 0.2,
            paths = paths,
        )
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /**
     * 鸿蒙的宿主能力全在 ArkTS 里，所以这里的每个方法都只说「请宿主做」。
     * 动作里的 name / args 用模型原始给的，这样 ArkTS 的 `executeTool(name, args)` 不用改。
     */
    private class AbiEnv(private val paths: List<String>) : AiToolEnv {
        override fun allowedPaths(): List<String> = paths
        override fun submit(): AiToolOutcome = AiToolOutcome.CallHost("submit")
        override fun openWorkflow(path: String): AiToolOutcome = AiToolOutcome.CallHost("open_workflow")
        override fun queryGraphs(): AiToolOutcome = AiToolOutcome.CallHost("list_graphs")
        override fun queryNodes(argsJson: String): AiToolOutcome = AiToolOutcome.CallHost("list_nodes")
        override fun queryNode(argsJson: String): AiToolOutcome = AiToolOutcome.CallHost("get_node")
        override fun taskSummary(waitSeconds: Int): AiToolOutcome = AiToolOutcome.CallHost("task_status")
        override fun lookupPromptGuide(model: String): AiToolOutcome =
            AiToolOutcome.CallHost("lookup_prompt_guide")

        override fun fetchDoc(url: String): AiToolOutcome = AiToolOutcome.CallHost("fetch_doc")
    }
}

/** 单例：一个进程一个 AI 会话（一个页面一个助手），ABI 层直接用它。 */
val sharedAiLoop: AiLoopProtocol by lazy { AiLoopProtocol() }
