package com.comfypilot.core.ai

import com.comfypilot.core.api.SystemStats
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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

    /** 主机状态：ComfyUI `/system_stats` 的原文（显存、内存、版本）。 */
    fun hostStatus(): AiToolOutcome

    /**
     * 某个 loader 类的可用模型。
     * [loaderClass] 为空串表示 `/embeddings`（文本反演），那类没有对应的 loader 类。
     */
    fun modelOptions(loaderClass: String): AiToolOutcome
}

/**
 * `list_models` 的 kind → ComfyUI 的 loader 类。
 *
 * 用 `/object_info/<类名>` 而不是 `/models`：前者老版本就有，而且返回的正是节点下拉框里
 * 的候选值——跟真正能用的完全一致，不会出现"列得出来但提交被拒"。
 */
object AiModelKinds {

    private val classes = linkedMapOf(
        "checkpoint" to "CheckpointLoaderSimple",
        "lora" to "LoraLoader",
        "unet" to "UNETLoader",
        "vae" to "VAELoader",
        "clip" to "CLIPLoader",
        "clip_vision" to "CLIPVisionLoader",
        "controlnet" to "ControlNetLoader",
        "upscale" to "UpscaleModelLoader",
        "embedding" to "",
    )

    val kinds: List<String> get() = classes.keys.toList()

    /** loader 类名；空串表示 embeddings；null 表示不认识的 kind。 */
    fun loaderFor(kind: String): String? = classes[kind.trim().lowercase()]
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

        "host_status" -> env.hostStatus()

        "list_models" -> {
            val kind = args(argsJson)["kind"].orEmpty().trim().lowercase()
            val loader = AiModelKinds.loaderFor(kind)
            if (loader == null) {
                value(
                    buildJsonObject {
                        put("ok", false)
                        put("error", if (kind.isEmpty()) "kind 为空" else "不认识的 kind：$kind")
                        putJsonArray("kinds") { AiModelKinds.kinds.forEach { add(JsonPrimitive(it)) } }
                    }.toString(),
                )
            } else {
                env.modelOptions(loader)
            }
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
        "submit" -> formatSubmit(rawJson)
        "host_status" -> formatHostStatus(rawJson)
        "list_models" -> formatModels(rawJson)
        "upload_image" -> formatUpload(rawJson)
        else -> rawJson
    }

    /**
     * 提交结果 → 连同刚等到的那次任务结果一起给模型。
     *
     * 只回「已排队」时，模型要么反复调 task_status，要么直接说「已完成」。带上 state/error，
     * 成功就能收尾，失败能当场看到原因（例如某个节点的值不在候选里）并去修。
     */
    private fun formatSubmit(rawJson: String): String {
        if (!ok(rawJson)) return SUBMIT_REJECTED
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return SUBMITTED
        val task = root["task"]?.let { runCatching { it.jsonArray.lastOrNull() }.getOrNull() } ?: return SUBMITTED
        val obj = task as? JsonObject ?: return SUBMITTED
        val state = (obj["state"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val done = state.lowercase() in setOf("completed", "success", "succeeded", "finished")
        return buildJsonObject {
            put("ok", true)
            put("queued", true)
            if (state.isNotBlank()) put("taskState", state)
            if (error.isNotBlank()) put("error", error)
            put(
                "hint",
                if (done) "任务已成功：把产物文件名报给用户，不要继续改"
                else "看 state/error 定位问题：能修就改完再 submit；修不了就把原因告诉用户，别反复重试",
            )
        }.toString()
    }

    /**
     * 上传结果 → 给模型看的一小段。
     *
     * 只回「传好了，文件名是 X」还不够：模型常接着去编一个文件名，或者忘了下一步。
     * 这里直接把「写进 image 字段」这一步写在旁边，少一轮猜。
     */
    private fun formatUpload(rawJson: String): String {
        if (!ok(rawJson)) return rawJson
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return rawJson
        val filename = (root["filename"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (filename.isBlank()) return rawJson
        return buildJsonObject {
            put("ok", true)
            put("filename", filename)
            put("hint", "图已在服务器上；要放进工作流就用 propose_edits 的 set_input 把这个文件名写到 image 字段")
        }.toString()
    }

    private val statsJson = Json { ignoreUnknownKeys = true }

    /**
     * `/system_stats` → 给模型看的一小段。
     *
     * 原始报文里字段名长、字节数大（`vram_total: 25769803776`），模型读起来既费 token
     * 又容易算错。这里换成 GB，并附一句中文摘要——绝大多数情况模型只读 summary 就够。
     */
    private fun formatHostStatus(rawJson: String): String {
        val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
        if (root != null && root["ok"] != null) return rawJson
        val stats = runCatching { statsJson.decodeFromString<SystemStats>(rawJson) }.getOrNull()
            ?: return """{"ok":false,"error":"读不懂 /system_stats 的返回"}"""

        val summary = buildString {
            if (stats.devices.isEmpty()) {
                append("未报告显卡")
            } else {
                stats.devices.forEachIndexed { index, device ->
                    if (index > 0) append("；")
                    append(device.name.ifBlank { "device $index" })
                    if (device.vramTotal > 0) {
                        append(" 显存 ${gb(device.vramTotal)}GB")
                        append("（空闲 ${gb(device.vramFree)}GB）")
                    }
                }
            }
            if (stats.system.ramTotal > 0) {
                append("｜内存 ${gb(stats.system.ramTotal)}GB（空闲 ${gb(stats.system.ramFree)}GB）")
            }
            val versions = listOfNotNull(
                stats.system.comfyuiVersion?.let { "ComfyUI $it" },
                stats.system.pytorchVersion?.let { "torch $it" },
                stats.system.pythonVersion?.let { "py $it" },
            )
            if (versions.isNotEmpty()) append("｜" + versions.joinToString(" / "))
        }

        return buildJsonObject {
            put("ok", true)
            put("summary", summary)
            stats.system.comfyuiVersion?.let { put("comfyui", it) }
            stats.system.pytorchVersion?.let { put("torch", it) }
            stats.system.pythonVersion?.let { put("python", it) }
            stats.system.os?.let { put("os", it) }
            if (stats.system.ramTotal > 0) {
                put("ram_total_gb", gb(stats.system.ramTotal))
                put("ram_free_gb", gb(stats.system.ramFree))
            }
            putJsonArray("devices") {
                stats.devices.forEach { device ->
                    add(
                        buildJsonObject {
                            if (device.name.isNotBlank()) put("name", device.name)
                            if (device.type.isNotBlank()) put("type", device.type)
                            put("vram_total_gb", gb(device.vramTotal))
                            put("vram_free_gb", gb(device.vramFree))
                        },
                    )
                }
            }
        }.toString()
    }

    /** `/object_info/<class>` 或 `/embeddings` → 只留下拉候选。 */
    private fun formatModels(rawJson: String): String {
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull()
        if (root is JsonObject && root["ok"] != null) return rawJson

        val options = linkedMapOf<String, List<String>>()
        if (root is JsonArray) {
            // /embeddings：["easynegative", "badhandv4", ...]
            options["embedding"] = root.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } else if (root is JsonObject) {
            // /object_info/<class>：{"<class>":{"input":{"required":{"ckpt_name":[["a",…],{…}]}}}}
            val body = (root.values.firstOrNull() as? JsonObject) ?: root
            val inputs = body["input"] as? JsonObject
            for (section in listOf("required", "optional")) {
                val group = inputs?.get(section) as? JsonObject ?: continue
                for ((inputName, value) in group) {
                    val array = value as? JsonArray ?: continue
                    val list = (array.firstOrNull() as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: continue
                    if (list.isNotEmpty()) options[inputName] = list
                }
            }
        }

        if (options.isEmpty()) {
            return """{"ok":false,"error":"没有找到下拉候选：类名不对，或这个节点没有可选模型"}"""
        }

        return buildJsonObject {
            put("ok", true)
            put("count", options.values.maxOf { it.size })
            putJsonObject("options") {
                for ((name, list) in options) {
                    putJsonArray(name) { list.take(MAX_MODELS).forEach { add(JsonPrimitive(it)) } }
                }
            }
            if (options.values.any { it.size > MAX_MODELS }) put("truncated", true)
        }.toString()
    }

    /** 一次最多列这么多：再多也是被截断的噪声，模型挑不过来。 */
    private const val MAX_MODELS = 80

    private fun gb(bytes: Long): Double = round(bytes / 1073741824.0 * 10) / 10

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
