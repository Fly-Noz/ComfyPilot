package com.comfypilot.core.workflow

import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工作流相关的 JSON 解析。
 *
 * 原则：只认能确认的结构，认不出来就返回 UNKNOWN/空，不要"猜一个看起来合理的结果"。
 * 一份转错的图比一份明确不支持的工作流危险得多——服务器会照单执行。
 */
object WorkflowCodec {

    /** 前端自己的虚拟节点，服务端没有这些类，不去请求 `/object_info`。 */
    private val FRONTEND_ONLY = setOf("Reroute", "PrimitiveNode", "Note", "MarkdownNote")

    /** 控件类型：这些类型的输入在图里是字面量，其余类型必须来自连线。 */
    private val WIDGET_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO")

    /** 新版前端的动态下拉框：仍然是控件，但选项会带出附加输入。 */
    private const val DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3"

    /** 自动增长输入：在 API 里展开成 `name.sub` 的多个键，本身不是控件。 */
    private const val AUTOGROW = "COMFY_AUTOGROW_V3"

    // ------------------------------------------------------------ /userdata 列表

    /**
     * `full_info=true` 时返回 `[{path,size,modified,created}]`；也兼容只返回路径字符串的旧行为。
     * 只保留 `.json`：目录里可能混着预览图之类的东西，读进来也没用。
     */
    fun parseCatalog(text: String): List<WorkflowEntry> {
        val array = runCatching { ApiCodec.json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { WorkflowEntry(it, workflowNameOf(it)) }

                is JsonObject -> {
                    val path = item.stringOrNull("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    WorkflowEntry(
                        path = path,
                        name = workflowNameOf(path),
                        sizeBytes = item.stringOrNull("size")?.toLongOrNull(),
                        modifiedAtMs = item.stringOrNull("modified")?.toLongOrNull(),
                    )
                }

                else -> null
            }
        }
            .filter { it.path.endsWith(".json", ignoreCase = true) }
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase() }
    }

    // ------------------------------------------------------------ 工作流原文

    sealed interface Parsed {
        data class Ok(val format: WorkflowFormat, val nodeClasses: List<String>) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    fun parseDocument(raw: String): Parsed {
        val root = runCatching { ApiCodec.json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return Parsed.Bad("不是 JSON 对象")

        val nodes = root["nodes"] as? JsonArray
        if (nodes != null) {
            val classes = nodes.mapNotNull { (it as? JsonObject)?.stringOrNull("type") }
                .filterNot { it in FRONTEND_ONLY }
                .distinct()
                .sorted()
            return Parsed.Ok(WorkflowFormat.UI, classes)
        }

        if (root.isEmpty()) return Parsed.Bad("工作流是空的")
        val classes = root.values.mapNotNull { entry ->
            entry as? JsonObject ?: return@mapNotNull null
            entry.stringOrNull("class_type")?.takeIf { it.isNotBlank() }
        }
        // API 格式要求"每一项都是一个带 class_type 的节点"，缺一项就说明混进了别的东西。
        if (classes.size != root.size) {
            return Parsed.Bad("既不是 API 格式（缺 class_type），也不是网页格式（缺 nodes）")
        }
        return Parsed.Ok(WorkflowFormat.API, classes.distinct().sorted())
    }

    // ------------------------------------------------------------ /object_info/{class}

    fun parseNodeDefinition(text: String, expectedClass: String): NodeDefinition? {
        val root = runCatching { ApiCodec.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return null
        val entry = root[expectedClass] as? JsonObject
            ?: root.values.firstOrNull() as? JsonObject
            ?: return null
        val input = entry["input"] as? JsonObject
        val required = (input?.get("required") as? JsonObject).orEmpty()
        val optional = (input?.get("optional") as? JsonObject).orEmpty()

        // input_order 是前端控件顺序的权威来源；没有它时退化为"必填在前、可选在后"。
        val order = entry["input_order"] as? JsonObject
        val ordered = buildList {
            (order?.get("required") as? JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(::add) }
            (order?.get("optional") as? JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(::add) }
            required.keys.forEach { if (it !in this) add(it) }
            optional.keys.forEach { if (it !in this) add(it) }
        }

        val inputs = mutableListOf<NodeInputDef>()
        for (name in ordered) {
            val isRequired = required.containsKey(name)
            val spec = required[name] ?: optional[name] ?: continue
            parseInput(name, spec, required = isRequired)?.let(inputs::add)
        }

        // 动态下拉框会把当前选项下的附加输入展开出来；它们不在顶层 required 里，
        // 但工作流里可能确实有值。统一收集成"非必填"的附加控件。
        val seen = inputs.map { it.name }.toMutableSet()
        for (def in inputs.toList()) {
            val spec = required[def.name] ?: optional[def.name] ?: continue
            collectDynamicInputs(spec, inputs, seen, depth = 0)
        }

        val outputs = (entry["output"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val outputNames = (entry["output_name"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return NodeDefinition(
            className = expectedClass,
            displayName = entry.stringOrNull("display_name")
                ?: entry.stringOrNull("name")
                ?: expectedClass,
            description = entry.stringOrNull("description"),
            inputs = inputs,
            outputs = outputs,
            outputNames = outputNames,
            isOutputNode = entry.boolOrNull("output_node"),
        )
    }

    /**
     * 输入声明有两种写法：
     * - 旧：`["INT", {default,min,max}]`，下拉框是 `[["a","b"], {}]`；
     * - 新：`{"type":"COMBO","options":[...]}`。
     * 两种都要认，认不出类型就丢弃这一项（少一个控件好过认错一个）。
     */
    private fun parseInput(
        name: String,
        spec: JsonElement,
        required: Boolean,
        dynamic: Boolean = false,
    ): NodeInputDef? {
        val (typeElement, config) = specParts(spec) ?: return null
        val type = typeName(typeElement) ?: return null

        val options = when {
            typeElement is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            type == "COMBO" -> (config?.get("options") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            type == DYNAMIC_COMBO -> (config?.get("options") as? JsonArray).orEmpty().mapNotNull { option ->
                when (option) {
                    is JsonObject -> option.stringOrNull("key")
                    is JsonPrimitive -> option.contentOrNull
                    else -> null
                }
            }
            else -> emptyList()
        }

        val forceInput = config.boolOrNull("forceInput")
        val isWidget = !forceInput && (type in WIDGET_TYPES || type == DYNAMIC_COMBO || options.isNotEmpty())
        return NodeInputDef(
            name = name,
            type = type,
            options = options,
            defaultValue = config?.get("default")?.takeIf { it !is JsonNull },
            isWidget = isWidget,
            required = required,
            controlAfterGenerate = config.boolOrNull("control_after_generate"),
            tooltip = config?.stringOrNull("tooltip"),
            forceInput = forceInput,
            imageUpload = config.boolOrNull("image_upload"),
            autogrow = type == AUTOGROW,
            dynamic = dynamic,
        )
    }

    /**
     * `COMFY_DYNAMICCOMBO_V3` 的 `options[].inputs` 是当前选项下额外要求的输入。
     * 只展开名字与类型用于识别；是否必填取决于用户选了哪个选项，所以一律按非必填处理。
     */
    private fun collectDynamicInputs(
        spec: JsonElement,
        into: MutableList<NodeInputDef>,
        seen: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > 6) return
        val (typeElement, config) = specParts(spec) ?: return
        if (typeName(typeElement) != DYNAMIC_COMBO) return
        val options = config?.get("options") as? JsonArray ?: return
        for (option in options) {
            val optionInputs = ((option as? JsonObject)?.get("inputs") as? JsonObject) ?: continue
            for (bucket in listOf("required", "optional")) {
                val map = optionInputs[bucket] as? JsonObject ?: continue
                for ((name, childSpec) in map) {
                    if (seen.add(name)) {
                        parseInput(name, childSpec, required = false, dynamic = true)?.let(into::add)
                    }
                    collectDynamicInputs(childSpec, into, seen, depth + 1)
                }
            }
        }
    }

    private fun specParts(spec: JsonElement): Pair<JsonElement, JsonObject?>? = when (spec) {
        is JsonArray -> {
            val type = spec.getOrNull(0) ?: return null
            type to (spec.getOrNull(1) as? JsonObject)
        }

        is JsonObject -> {
            val type = spec["type"] ?: return null
            type to spec
        }

        else -> null
    }

    private fun typeName(element: JsonElement): String? = when (element) {
        is JsonArray -> "COMBO"
        is JsonPrimitive -> element.content
        else -> null
    }

    private fun JsonObject?.boolOrNull(key: String): Boolean =
        this?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }?.toBooleanStrictOrNull() ?: false
}

/** `sub/dir/my_flow.json` -> `my_flow`；没有扩展名就原样返回。 */
fun workflowNameOf(path: String): String {
    val file = path.substringAfterLast('/')
    return file.removeSuffix(".json").ifBlank { file.ifBlank { path } }
}
