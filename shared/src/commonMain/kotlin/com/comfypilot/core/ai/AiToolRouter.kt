package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 工具执行的结果：要么现在就有，要么得请宿主去做。
 *
 * 为什么要分：鸿蒙的宿主能力在 ArkTS 里，Kotlin 没法在一次 C 调用里同步拿到
 * 结果（`taskSummary` 甚至是 Promise）。所以环境层不再「直接调宿主」，而是描述
 * 要宿主做什么，由循环把这条指令交给宿主执行。
 */
sealed interface AiToolOutcome {

    /** 原始结果已经有了（Android 本进程内就能查）。交给 [AiToolRouter.formatResult] 收尾。 */
    data class Value(val json: String) : AiToolOutcome

    /**
     * 请宿主执行一个动作，返回的 JSON 交回 [AiToolRouter.formatResult]。
     *
     * [method] 取值：`submit` / `open_workflow` / `list_graphs` / `list_nodes` /
     * `get_node` / `task_status` / `lookup_prompt_guide` / `fetch_doc`；
     * [args] 是原始参数 JSON，宿主按方法自行解析。
     */
    data class CallHost(val method: String, val args: String = "{}") : AiToolOutcome

    /** 请宿主代发一次 GET（网络在宿主侧），返回正文 JSON。 */
    data class Fetch(val url: String) : AiToolOutcome
}

/**
 * 工具执行的宿主能力：两端各自实现，路由器只负责参数校验与结果格式。
 *
 * 每个方法都返回 [AiToolOutcome]——能立刻算的就 [AiToolOutcome.Value]，
 * 需要宿主出手的就 [AiToolOutcome.CallHost] / [AiToolOutcome.Fetch]。
 * 唯一同步的是 [allowedPaths]：它是纯数据，调用方（鸿蒙的泵帧）手上本来就有。
 *
 * 注意：`propose_edits` 与 `ask_user` 依赖运行时状态（待确认提案、待回答
 * 问题、自动提交开关），仍由两端处理；[AiToolRouter.execute] 对它们返回 null。
 */
interface AiToolEnv {
    /** 当前 AI 工作区里允许切换的工作流。 */
    fun allowedPaths(): List<String>

    fun submit(): AiToolOutcome
    fun openWorkflow(path: String): AiToolOutcome

    fun queryGraphs(): AiToolOutcome
    fun queryNodes(argsJson: String): AiToolOutcome
    fun queryNode(argsJson: String): AiToolOutcome

    /** 最近任务摘要；[waitSeconds] > 0 时等到没有活动任务或超时。 */
    fun taskSummary(waitSeconds: Int): AiToolOutcome

    /** 查模型提示词规范。 */
    fun lookupPromptGuide(model: String): AiToolOutcome

    /** 抓文档正文。 */
    fun fetchDoc(url: String): AiToolOutcome
}

/**
 * 无状态工具的分派器：校验参数，然后交给宿主能力。
 *
 * 返回值是「结果已就绪」或「请宿主执行」；不认识或需要运行时状态的工具返回 null，
 * 由调用方继续处理。[formatResult] 是唯一给模型写措辞的地方——两端共用，
 * 免得鸿蒙那边再抄一份。
 */
object AiToolRouter {

    /** 提交成功后的固定说法：逼模型去核实，别直接宣称完成。 */
    const val SUBMITTED: String =
        """{"ok":true,"queued":true,"hint":"用 task_status（wait_seconds 例如 30）确认真实结果，别直接说已完成"}"""

    const val SUBMIT_REJECTED: String = """{"ok":false,"error":"工作流还有未填字段或未就绪"}"""

    fun execute(name: String, argsJson: String, env: AiToolEnv): AiToolOutcome? = when (name) {
        "submit" -> env.submit()

        "task_status" -> {
            val wait = args(argsJson)["wait_seconds"]?.trim()?.toIntOrNull() ?: 0
            env.taskSummary(wait.coerceIn(0, 120))
        }

        "open_workflow" -> {
            val path = args(argsJson)["path"].orEmpty()
            when {
                path.isBlank() -> value("""{"ok":false,"error":"path 为空"}""")
                path !in env.allowedPaths() -> value("""{"ok":false,"error":"该工作流不在当前 AI 工作区内"}""")
                else -> env.openWorkflow(path)
            }
        }

        "list_graphs" -> env.queryGraphs()
        "list_nodes" -> env.queryNodes(argsJson)
        "get_node" -> env.queryNode(argsJson)

        "lookup_prompt_guide" -> {
            val model = args(argsJson)["model"].orEmpty()
            if (model.isBlank()) {
                value("""{"ok":false,"error":"model 为空"}""")
            } else {
                env.lookupPromptGuide(model)
            }
        }

        "fetch_doc" -> {
            val url = args(argsJson)["url"].orEmpty()
            if (url.isBlank()) value("""{"ok":false,"error":"url 为空"}""") else env.fetchDoc(url)
        }

        else -> null
    }

    /**
     * 宿主回填的原始结果 → 给模型的最终 JSON。
     *
     * Android 走 [AiToolOutcome.Value] 时也走这里，所以措辞只有这一份；
     * 鸿蒙在 `cp_ai_send` 之后调用它，不需要在 ArkTS 里抄文案。
     */
    fun formatResult(name: String, rawJson: String): String = when (name) {
        "submit" -> if (ok(rawJson)) SUBMITTED else SUBMIT_REJECTED
        else -> rawJson
    }

    private fun value(json: String): AiToolOutcome = AiToolOutcome.Value(json)

    private fun ok(rawJson: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(rawJson) as? JsonObject ?: return false
        (root["ok"] as? JsonPrimitive)?.booleanOrNull == true
    }.getOrDefault(false)

    /**
     * 解析工具参数：只取顶层标量，够 router 用；复杂结构仍交给原实现。
     * 解析失败返回空表，而不是抛异常——一条坏参数不该打断整个工具循环。
     */
    private fun args(json: String): Map<String, String> {
        val element = runCatching {
            Json.parseToJsonElement(json)
        }.getOrNull() as? JsonObject ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (entry in element.entries) {
            val primitive = entry.value as? JsonPrimitive ?: continue
            val text = primitive.contentOrNull ?: continue
            result[entry.key] = text
        }
        return result
    }
}
