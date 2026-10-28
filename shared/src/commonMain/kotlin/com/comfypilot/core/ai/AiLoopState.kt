package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 工具循环的下一步。宿主只认三种动作：发请求、执行一个工具、收工。
 *
 * 为什么不在这里直接调宿主：鸿蒙那边 ArkTS 的能力没法在单次 C 调用里同步回填，
 * 循环必须「吐一步、等一次结果」。所以循环只描述**下一步做什么**，执行留给宿主
 * （Android 直接执行，鸿蒙按 `cp_ai_step` / `cp_ai_send` 往返）。
 */
sealed interface AiLoopStep {

    /** 发一次 chat/completions；宿主拿到响应里的 message 后交给 [AiLoopState.acceptReply]。 */
    data class Http(val body: String) : AiLoopStep

    /** 执行一个工具调用，把结果交给 [AiLoopState.acceptToolResult]（ABI 里的 `call_host`）。 */
    data class CallTool(val callId: String, val name: String, val args: String) : AiLoopStep

    /** 本轮结束；[text] 对 ANSWER 是模型答复，对 ROUND_LIMIT / ERROR 是给用户看的说明。 */
    data class Done(val reason: AiDoneReason, val text: String? = null) : AiLoopStep
}

/** 循环停下来的原因；[wire] 是跨 ABI 用的字符串，别随手改。 */
enum class AiDoneReason(val wire: String) {
    /** 模型给出了最终答复。 */
    ANSWER("answer"),

    /** 生成了待确认的修改，等用户点确认。 */
    PENDING("pending"),

    /** 在等用户回答问题（ask_user）。 */
    QUESTION("question"),

    /** 到了单轮工具往返上限。 */
    ROUND_LIMIT("round_limit"),

    /** 宿主主动停止（用户点了停止）。 */
    STOPPED("stopped"),

    /** 调用顺序用错了，例如还没发请求就收到回复。 */
    ERROR("error"),
}

/**
 * 每一轮请求的上下文：模型、系统提示、采样温度。
 *
 * 系统提示由宿主拼（它知道当前工作流、digest、可切换路径），循环只负责把它们
 * 按顺序放到会话最前面。宿主每轮都可以重新给一份——模型刚切过工作流时，
 * digest 必须跟着变，不能沿用上一轮的。
 */
data class AiLoopContext(
    val model: String,
    /** 按顺序拼在会话前；空白行会被丢掉。 */
    val system: List<String> = emptyList(),
    val temperature: Double = 0.2,
)

/**
 * AI 工具循环的状态机（纯逻辑，无 IO）。
 *
 * 一次用户输入对应一个状态机：[start] 塞入用户消息，然后
 * `nextRequest` → `acceptReply` → `CallTool` → `acceptToolResult` … 直到 [AiLoopStep.Done]。
 *
 * 刻意不做的事：
 * - 不认识 [AiLoopStep.CallTool] 的工具名，也不执行它们——分派在 [AiToolRouter] 与宿主；
 * - 不维护 UI 的聊天记录（那是宿主的 [messages] 副本），这里只有发给模型的会话；
 * - 不做「自动提交」这类策略判断：宿主执行完 propose_edits 后自己决定要不要
 *   [markPending]，循环只认「暂停」这个事实。
 *
 * 暂停语义与两端现有实现保持一致：一批工具调用会**全部**拿到结果后才检查暂停标志，
 * 否则 assistant 消息里的 tool_calls 会缺结果，下一轮请求直接非法。
 */
class AiLoopState(
    /** 单轮最多几次模型往返；默认 6，与 Android 原来的 `repeat(6)` 对齐。 */
    val maxRounds: Int = 6,
) {

    private val turns = mutableListOf<JsonObject>()
    private val queue = ArrayDeque<AiToolCall>()

    /** 已经发出请求、还在等回复。防止重复取同一轮时把轮数算掉。 */
    private var awaiting = false

    /** 已经发出的请求数。 */
    var round: Int = 0
        private set

    /** 有提案在等用户确认。由宿主设置。 */
    var pending: Boolean = false
        private set

    /** 待回答的问题；null 表示没有。由宿主设置。 */
    var question: String? = null
        private set

    var finished: Boolean = false
        private set

    /** 模型的最终文本答复。 */
    var answer: String? = null
        private set

    /** 发给模型的会话（不含 system）；工具调用与结果都在里面。 */
    val messages: List<JsonObject> get() = turns.toList()

    /** 暂停中：用户在确认提案或回答问题，循环不该自己往下走。 */
    val paused: Boolean get() = pending || question != null

    /** 开始新的一轮用户输入；清空上一轮的会话与计数。 */
    fun start(userTurn: JsonObject) {
        turns.clear()
        queue.clear()
        awaiting = false
        round = 0
        pending = false
        question = null
        finished = false
        answer = null
        turns += userTurn
    }

    /**
     * 取下一条请求。暂停中返回 [AiDoneReason.PENDING] / [AiDoneReason.QUESTION]，
     * 轮数用尽返回 [AiDoneReason.ROUND_LIMIT]；还没回填的工具调用优先发出去。
     */
    fun nextRequest(context: AiLoopContext): AiLoopStep {
        if (finished) return AiLoopStep.Done(AiDoneReason.STOPPED, answer)
        pausedStep()?.let { return it }
        queue.firstOrNull()?.let { return it.toStep() }
        return request(context)
    }

    /**
     * 交回模型的响应 message（`choices[0].message` 原样）。
     * 有 tool_calls 就先发第一个工具；没有就收工。
     */
    fun acceptReply(message: JsonObject): AiLoopStep {
        if (finished) return AiLoopStep.Done(AiDoneReason.STOPPED, answer)
        if (!awaiting) return AiLoopStep.Done(AiDoneReason.ERROR, "还没有发出请求，收到了意外的回复")
        awaiting = false
        turns += message

        val calls = parseCalls(message)
        if (calls.isEmpty()) {
            val text = message["content"].text()
            answer = text
            finished = true
            return AiLoopStep.Done(AiDoneReason.ANSWER, text)
        }
        queue.addAll(calls)
        return queue.first().toStep()
    }

    /**
     * 回填一个工具结果。队列空了就发下一次请求；中途出现暂停则先停下，
     * 剩余调用留到 [resume] 之后继续发。
     */
    fun acceptToolResult(callId: String, resultJson: String, context: AiLoopContext): AiLoopStep {
        if (finished) return AiLoopStep.Done(AiDoneReason.STOPPED, answer)
        val call = queue.firstOrNull()
            ?: return AiLoopStep.Done(AiDoneReason.ERROR, "没有等待结果的工具调用")
        // id 对不上说明宿主送回了过期的结果：宁可报错，也别把结果配给错的调用。
        if (callId.isNotBlank() && call.id.isNotBlank() && callId != call.id) {
            return AiLoopStep.Done(
                AiDoneReason.ERROR,
                "工具结果与等待中的调用不对应：$callId ≠ ${call.id}",
            )
        }
        queue.removeFirst()

        turns += buildJsonObject {
            put("role", "tool")
            put("tool_call_id", call.id.ifBlank { callId })
            put("content", resultJson)
        }

        pausedStep()?.let { return it }
        queue.firstOrNull()?.let { return it.toStep() }
        return request(context)
    }

    /** 宿主生成了待确认提案（propose_edits 的非自动提交分支）。 */
    fun markPending() {
        pending = true
    }

    /**
     * 就地替换最后一条用户消息。给重试用：模型不认图片时，把带图的消息换成纯文本，
     * 轮数不重来——[acceptReply] 还没发生过，下一次 [nextRequest] 会用同一轮重新拼请求。
     */
    fun replaceUserTurn(turn: JsonObject): Boolean {
        val index = turns.indexOfLast { it["role"].text() == "user" }
        if (index < 0) return false
        turns[index] = turn
        return true
    }

    /** 宿主在等用户回答（ask_user）；[text] 是已经展示给用户的问题。 */
    fun markQuestion(text: String) {
        question = text
    }

    /**
     * 用户回应了暂停（回答了问题，或确认/取消了提案）后继续。
     * 轮数重新计数——这是新的一次用户输入。[userTurn] 非空时写进会话。
     */
    fun resume(userTurn: JsonObject? = null): Boolean {
        if (finished) return false
        pending = false
        question = null
        round = 0
        awaiting = false
        if (userTurn != null) turns += userTurn
        return true
    }

    /** 用户点了停止；之后的任何调用都只会得到 STOPPED。 */
    fun finish() {
        finished = true
        awaiting = false
        queue.clear()
    }

    private fun request(context: AiLoopContext): AiLoopStep {
        // 同一轮重复取（鸿蒙缓冲区扩容会重试），不能重复计数。
        if (awaiting) return AiLoopStep.Http(body(context))
        if (round >= maxRounds) {
            return AiLoopStep.Done(AiDoneReason.ROUND_LIMIT, "工具调用轮数过多，已停止")
        }
        round++
        awaiting = true
        return AiLoopStep.Http(body(context))
    }

    private fun pausedStep(): AiLoopStep? = when {
        question != null -> AiLoopStep.Done(AiDoneReason.QUESTION, question)
        pending -> AiLoopStep.Done(AiDoneReason.PENDING, null)
        else -> null
    }

    /** 拼请求体；工具声明来自 [AiToolSpec]，两端不再各写一份。 */
    private fun body(context: AiLoopContext): String = buildJsonObject {
        put("model", context.model)
        put("temperature", context.temperature)
        putJsonArray("messages") {
            context.system.forEach { line ->
                if (line.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", line)
                        },
                    )
                }
            }
            turns.forEach { add(it) }
        }
        put("tools", tools)
        put("tool_choice", "auto")
    }.toString()

    private fun parseCalls(message: JsonObject): List<AiToolCall> {
        val array = message["tool_calls"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            val name = function["name"].text()
            if (name.isBlank()) return@mapNotNull null
            AiToolCall(id = call["id"].text(), name = name, args = argumentsText(function["arguments"]))
        }
    }

    private data class AiToolCall(val id: String, val name: String, val args: String) {
        fun toStep(): AiLoopStep.CallTool = AiLoopStep.CallTool(id, name, args)
    }

    private companion object {
        /** 解析一次即可；工具声明是常量。 */
        val tools: JsonArray by lazy {
            Json.parseToJsonElement(AiToolSpec.json) as JsonArray
        }
    }
}

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * 参数取原样的 JSON 文本。正常是字符串 `"{\"id\":1}"`；有的服务端直接给对象，
 * 也照样转成字符串，别在这里抛异常把整轮对话打断。
 */
private fun argumentsText(element: JsonElement?): String = when (element) {
    null -> "{}"
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}"
    else -> element.toString()
}
