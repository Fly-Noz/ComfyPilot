package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 ComfyUI 前端的新「子图（subgraph）」展开成平铺节点。
 *
 * 子图在文件里是两段结构：`definitions.subgraphs[]` 存内部节点与连线，
 * 实例节点（`type` 等于子图 id）只带边界输入/输出。服务端 /prompt 只认平铺图，
 * 所以提交前必须把实例替换成内部节点，并把边界连线接到外层节点上。
 *
 * 展开规则（与前端行为一致）：
 * - 内部节点 id 重新分配，避免与外层冲突（外层 id 不变）；
 * - `origin_id == -10` 的连线表示「从子图输入进来」：接到实例对应输入的外层连线，
 *   若实例给的是控件值（没有连线），直接写进内部节点的 `widgets_values`；
 * - `target_id == -20` 的连线表示「从子图输出出去」：接到实例对应输出的外层连线上；
 * - 内部节点之间的连线保留，只是两端 id 换成新 id；
 * - 展开完删除 `definitions`，后续解析按普通工作流处理。
 */
object SubgraphExpander {

    private data class Link(
        val id: Int,
        val origin: Int,
        val originSlot: Int,
        val target: Int,
        val targetSlot: Int,
        val type: String?,
    )

    private const val INPUT_NODE = -10
    private const val OUTPUT_NODE = -20

    fun expand(root: JsonObject): JsonObject {
        val definitions = root["definitions"] as? JsonObject ?: return root
        val subgraphs = definitions["subgraphs"] as? JsonArray ?: return root
        val byId = subgraphs.mapNotNull { it as? JsonObject }
            .mapNotNull { sub -> sub.string("id")?.let { it to sub } }
            .toMap()
        if (byId.isEmpty()) return root

        // 没有实例节点时不要动原文：定义里可能只是暂存着一个没用上的子图，
        // 贸然删掉 definitions 会让「包含子图」的提示消失得莫名其妙。
        val hasInstance = (root["nodes"] as? JsonArray).orEmpty().any { element ->
            ((element as? JsonObject)?.string("type")) in byId
        }
        if (!hasInstance) return root

        var nodes = root.array("nodes").toMutableList()
        var links = root.array("links").toMutableList()
        var maxNodeId = maxOf(
            root.int("last_node_id") ?: 0,
            nodes.mapNotNull { (it as? JsonObject)?.int("id") }.maxOrNull() ?: 0,
        )
        var maxLinkId = maxOf(
            root.int("last_link_id") ?: 0,
            links.mapNotNull { parseLink(it)?.id }.maxOrNull() ?: 0,
        )

        // 同一实例的内部节点每次展开都要拿到稳定 id，草稿里的覆盖值才不会乱跳。
        val allocated = mutableMapOf<String, Int>()
        fun allocate(scope: String, innerId: Int): Int =
            allocated.getOrPut("$scope/$innerId") { ++maxNodeId }

        var guard = 0
        while (guard++ < 200) {
            val instanceIndex = nodes.indexOfFirst { node ->
                ((node as? JsonObject)?.string("type")) in byId
            }
            if (instanceIndex < 0) break
            val instance = nodes[instanceIndex] as? JsonObject ?: break
            val instanceId = instance.int("id") ?: break
            val definition = byId[instance.string("type")] ?: break

            val innerNodes = definition.array("nodes")
            val innerLinks = definition.array("links").mapNotNull { parseLink(it) }
            val inputDefs = definition.array("inputs")
            val outputDefs = definition.array("outputs")
            val instanceInputs = instance.array("inputs")
            val outerLinks = links.mapNotNull { parseLink(it) }.associateBy { it.id }
            val instancePos = (instance["pos"] as? JsonArray).orEmpty()
            val offsetX = instancePos.getOrNull(0).doubleOrZero()
            val offsetY = instancePos.getOrNull(1).doubleOrZero()

            val idMap = innerNodes.mapNotNull { it as? JsonObject }
                .mapNotNull { inner -> inner.int("id")?.let { it to allocate(instanceId.toString(), it) } }
                .toMap()

            // 控件注入：子图输入在实例上如果是控件值（没有连线），要写进内部节点的 widgets_values。
            val injected = mutableMapOf<Int, MutableMap<Int, JsonElement>>()
            val rewritten = mutableListOf<JsonArray>()
            val oldToNewLink = mutableMapOf<Int, Int>()

            for (link in innerLinks) {
                when {
                    link.origin == INPUT_NODE -> {
                        val instanceInput = instanceInputs.getOrNull(link.originSlot) as? JsonObject
                        val outerLinkId = instanceInput?.int("link")
                        if (outerLinkId != null) {
                            val outer = outerLinks[outerLinkId] ?: continue
                            val target = idMap[link.target] ?: continue
                            val newId = ++maxLinkId
                            rewritten += linkArray(newId, outer.origin, outer.originSlot, target, link.targetSlot, link.type)
                            oldToNewLink[link.id] = newId
                        } else {
                            // 没有外层连线：把实例上的控件值塞给内部节点的对应控件。
                            val target = idMap[link.target] ?: continue
                            val value = instanceWidgetValue(instance, instanceInputs, inputDefs, link.originSlot)
                            if (value != null) {
                                val innerNode = innerNodes.mapNotNull { it as? JsonObject }
                                    .firstOrNull { it.int("id") == link.target }
                                val widgetIndex = widgetIndex(innerNode, link.targetSlot)
                                if (widgetIndex != null) {
                                    injected.getOrPut(target) { mutableMapOf() }[widgetIndex] = value
                                }
                            }
                            oldToNewLink[link.id] = -1
                        }
                    }
                    link.target == OUTPUT_NODE -> oldToNewLink[link.id] = -2
                    else -> {
                        val origin = idMap[link.origin] ?: continue
                        val target = idMap[link.target] ?: continue
                        val newId = ++maxLinkId
                        rewritten += linkArray(newId, origin, link.originSlot, target, link.targetSlot, link.type)
                        oldToNewLink[link.id] = newId
                    }
                }
            }

            // 实例输出：每个外链都对应内部某个节点（经 target == -20 的连线）的输出槽。
            val outputExtra = mutableMapOf<Int, MutableList<Pair<Int, Int>>>() // 内部节点 -> (槽位, 新链 id)
            for (outer in links.mapNotNull { parseLink(it) }) {
                if (outer.origin != instanceId) continue
                val outputDef = outputDefs.getOrNull(outer.originSlot) as? JsonObject ?: continue
                val boundaryIds = outputDef.array("linkIds")
                    .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                for (boundaryId in boundaryIds) {
                    val boundary = innerLinks.firstOrNull { it.id == boundaryId } ?: continue
                    val origin = idMap[boundary.origin] ?: continue
                    rewritten += linkArray(outer.id, origin, boundary.originSlot, outer.target, outer.targetSlot, outer.type)
                    outputExtra.getOrPut(origin) { mutableListOf() } += outer.originSlot to outer.id
                }
            }

            val expandedInner = innerNodes.mapNotNull { element ->
                val inner = element as? JsonObject ?: return@mapNotNull null
                val innerId = inner.int("id") ?: return@mapNotNull null
                val newId = idMap[innerId] ?: return@mapNotNull null

                val newInputs = inner.array("inputs").map { inputElement ->
                    val input = inputElement as? JsonObject ?: return@map inputElement
                    val oldLink = input.int("link")
                    val newLink = oldLink?.let { oldToNewLink[it] }
                    buildJsonObject {
                        input.forEach { (key, value) -> if (key != "link") put(key, value) }
                        when {
                            newLink == null -> Unit
                            newLink <= 0 -> put("link", JsonNull)
                            else -> put("link", JsonPrimitive(newLink))
                        }
                    }
                }
                val newOutputs = inner.array("outputs").map { outputElement ->
                    val output = outputElement as? JsonObject ?: return@map outputElement
                    val linkIds = output.array("links")
                        .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                        .mapNotNull { oldToNewLink[it]?.takeIf { id -> id > 0 } }
                    buildJsonObject {
                        output.forEach { (key, value) -> if (key != "links") put(key, value) }
                        put("links", buildJsonArray { linkIds.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                val widgets = (inner["widgets_values"] as? JsonArray)?.toMutableList()
                injected[newId]?.forEach { (index, value) ->
                    if (widgets == null) return@forEach
                    while (widgets.size <= index) widgets += JsonNull
                    widgets[index] = value
                }
                val pos = (inner["pos"] as? JsonArray).orEmpty()
                buildJsonObject {
                    inner.forEach { (key, value) -> if (key !in setOf("id", "pos", "inputs", "outputs", "widgets_values")) put(key, value) }
                    put("id", JsonPrimitive(newId))
                    if (pos.isNotEmpty()) {
                        put(
                            "pos",
                            buildJsonArray {
                                add(JsonPrimitive(pos.getOrNull(0).doubleOrZero() + offsetX))
                                add(JsonPrimitive(pos.getOrNull(1).doubleOrZero() + offsetY))
                            },
                        )
                    }
                    put("inputs", JsonArray(newInputs))
                    put("outputs", JsonArray(newOutputs))
                    if (widgets != null) put("widgets_values", JsonArray(widgets))
                }
            }

            // 输出侧：内部节点的 outputs.links 里补上实例外链的 id，服务端不读，但前端/校验会用。
            val outputPatched = expandedInner.map { element ->
                val node = element as? JsonObject ?: return@map element
                val extra = outputExtra[node.int("id") ?: 0] ?: return@map element
                val outputs = (node["outputs"] as? JsonArray)?.toMutableList() ?: return@map element
                for ((slot, linkId) in extra) {
                    val output = outputs.getOrNull(slot) as? JsonObject ?: continue
                    val ids = output.array("links").toMutableList()
                    if (ids.none { (it as? JsonPrimitive)?.intOrNull == linkId }) ids.add(JsonPrimitive(linkId))
                    outputs[slot] = buildJsonObject {
                        output.forEach { (key, value) -> if (key != "links") put(key, value) }
                        put("links", JsonArray(ids))
                    }
                }
                buildJsonObject {
                    node.forEach { (key, value) -> if (key != "outputs") put(key, value) }
                    put("outputs", JsonArray(outputs))
                }
            }

            nodes = nodes.toMutableList().apply {
                removeAt(instanceIndex)
                addAll(outputPatched)
            }
            links = links.filterNot { element ->
                val link = parseLink(element) ?: return@filterNot false
                link.origin == instanceId || link.target == instanceId
            }.toMutableList().apply { addAll(rewritten) }
        }

        return buildJsonObject {
            root.forEach { (key, value) -> if (key != "nodes" && key != "links" && key != "definitions") put(key, value) }
            put("nodes", JsonArray(nodes))
            put("links", JsonArray(links))
            put("last_node_id", JsonPrimitive(maxNodeId))
            put("last_link_id", JsonPrimitive(maxLinkId))
        }
    }

    /** 实例上这个输入对应的控件值：优先用命名表，其次按控件输入顺序取 widgets_values。 */
    private fun instanceWidgetValue(
        instance: JsonObject,
        instanceInputs: JsonArray,
        inputDefs: JsonArray,
        inputIndex: Int,
    ): JsonElement? {
        val name = (inputDefs.getOrNull(inputIndex) as? JsonObject)?.string("name")
        if (name != null) {
            val named = (instance["widgets_values_named"] as? JsonObject)?.get(name)
            if (named != null) return named
        }
        val values = instance["widgets_values"] as? JsonArray ?: return null
        var widgetIndex = 0
        for (i in 0 until inputIndex) {
            val input = instanceInputs.getOrNull(i) as? JsonObject ?: continue
            if (input["widget"] is JsonObject) widgetIndex++
        }
        return values.getOrNull(widgetIndex)
    }

    /** 内部节点第 [slot] 个输入对应的 widgets_values 下标；不是控件输入返回 null。 */
    private fun widgetIndex(node: JsonObject?, slot: Int): Int? {
        val inputs = (node?.get("inputs") as? JsonArray) ?: return null
        val target = inputs.getOrNull(slot) as? JsonObject ?: return null
        if (target["widget"] !is JsonObject) return null
        var index = 0
        for (i in 0 until slot) {
            val input = inputs.getOrNull(i) as? JsonObject ?: continue
            if (input["widget"] is JsonObject) index++
        }
        return index
    }

    private fun linkArray(
        id: Int,
        origin: Int,
        originSlot: Int,
        target: Int,
        targetSlot: Int,
        type: String?,
    ): JsonArray = buildJsonArray {
        add(JsonPrimitive(id))
        add(JsonPrimitive(origin))
        add(JsonPrimitive(originSlot))
        add(JsonPrimitive(target))
        add(JsonPrimitive(targetSlot))
        add(if (type == null) JsonNull else JsonPrimitive(type))
    }

    private fun parseLink(element: JsonElement): Link? {
        // 子图内部的 links 是对象（{id, origin_id, ...}），顶层是数组；两种都要认。
        if (element is JsonObject) {
            val id = element.int("id") ?: return null
            val origin = element.int("origin_id") ?: return null
            val target = element.int("target_id") ?: return null
            return Link(
                id = id,
                origin = origin,
                originSlot = element.int("origin_slot") ?: 0,
                target = target,
                targetSlot = element.int("target_slot") ?: 0,
                type = element.string("type"),
            )
        }
        val array = element as? JsonArray ?: return null
        if (array.size < 5) return null
        val id = (array[0] as? JsonPrimitive)?.intOrNull ?: return null
        val origin = (array[1] as? JsonPrimitive)?.intOrNull ?: return null
        val originSlot = (array[2] as? JsonPrimitive)?.intOrNull ?: 0
        val target = (array[3] as? JsonPrimitive)?.intOrNull ?: return null
        val targetSlot = (array[4] as? JsonPrimitive)?.intOrNull ?: 0
        val type = (array.getOrNull(5) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        return Link(id, origin, originSlot, target, targetSlot, type)
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.doubleOrZero(): Double =
        (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
}
