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
        val inputs = buildList {
            required.forEach { (name, spec) -> parseInput(name, spec, required = true)?.let(::add) }
            optional.forEach { (name, spec) -> parseInput(name, spec, required = false)?.let(::add) }
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
        )
    }

    /**
     * 输入声明有两种写法：
     * - 旧：`["INT", {default,min,max}]`，下拉框是 `[["a","b"], {}]`；
     * - 新：`{"type":"COMBO","options":[...]}`。
     * 两种都要认，认不出类型就丢弃这一项（少一个控件好过认错一个）。
     */
    private fun parseInput(name: String, spec: JsonElement, required: Boolean): NodeInputDef? {
        val typeElement: JsonElement
        val config: JsonObject?
        when (spec) {
            is JsonArray -> {
                typeElement = spec.getOrNull(0) ?: return null
                config = spec.getOrNull(1) as? JsonObject
            }

            is JsonObject -> {
                typeElement = spec["type"] ?: return null
                config = spec
            }

            else -> return null
        }

        val options = when (typeElement) {
            is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> if (typeElement.content == "COMBO") {
                (config?.get("options") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            } else {
                emptyList()
            }

            else -> return null
        }
        val type = when (typeElement) {
            is JsonArray -> "COMBO"
            is JsonPrimitive -> typeElement.content
            else -> return null
        }

        val forceInput = config.boolOrNull("forceInput")
        val isWidget = !forceInput && (type in WIDGET_TYPES || options.isNotEmpty())
        return NodeInputDef(
            name = name,
            type = type,
            options = options,
            defaultValue = config?.get("default")?.takeIf { it !is JsonNull },
            isWidget = isWidget,
            required = required,
            controlAfterGenerate = config.boolOrNull("control_after_generate"),
            tooltip = config?.stringOrNull("tooltip"),
        )
    }

    private fun JsonObject?.boolOrNull(key: String): Boolean =
        this?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }?.toBooleanStrictOrNull() ?: false
}

/** `sub/dir/my_flow.json` -> `my_flow`；没有扩展名就原样返回。 */
fun workflowNameOf(path: String): String {
    val file = path.substringAfterLast('/')
    return file.removeSuffix(".json").ifBlank { file.ifBlank { path } }
}
