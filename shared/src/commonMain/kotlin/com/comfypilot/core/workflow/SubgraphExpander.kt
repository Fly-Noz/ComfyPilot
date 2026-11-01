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

        // 展开后的节点要占画布位置：原坐标可能已经压着别的节点/图组，
        // 节点图预览上会叠成一团。先记下已有占位，冲突时把整簇挪到空处。
        val obstacles = mutableListOf<Rect>()
        val nodeRects = mutableListOf<Rect>()
        nodes.forEach { node ->
            val obj = node as? JsonObject ?: return@forEach
            val rect = nodeRect(obj)
            nodeRects += rect
            obstacles += rect
        }
        val groupObjs = root.array("groups").mapNotNull { it as? JsonObject }.toMutableList()
        groupObjs.forEach { group -> groupRect(group)?.let { obstacles += it } }
        var nextGroupId = (groupObjs.mapNotNull { it.int("id") }.maxOrNull() ?: 0) + 1
        val syntheticGroups = mutableListOf<JsonObject>()

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
            // 子图内部节点用的是画布上的绝对坐标（定义里保留着创建时的位置），
            // 不要再叠加实例位置，否则会整体飞出去、脱离原来的图组。

            val idMap = innerNodes.mapNotNull { it as? JsonObject }
                .mapNotNull { inner -> inner.int("id")?.let { it to allocate(instanceId.toString(), it) } }
                .toMap()

            // 展开簇占位。优先看**实例**落在哪个图组里：子图定义坐标跟实例位置无关，
            // 按定义坐标判断归属会把「生图里的子图」判丢。实例有组时直接把展开节点
            // 记进那个组的显式成员，组标题/开关/nodeIds 都不变。
            val cluster = clusterRect(innerNodes)
            val needsMove = obstacles.any { intersects(it, cluster) }
            val instanceRect = nodeRect(instance)
            val instanceHome = groupObjs
                .mapNotNull { group -> groupRect(group)?.let { group to it } }
                .filter { (_, rect) -> intersects(rect, instanceRect) }
                .maxByOrNull { (_, rect) -> rect.width * rect.height }

            var placed = cluster
            var synthetic = false
            if (instanceHome != null) {
                placed = if (needsMove) freeSpot(cluster, obstacles) else cluster
                val index = groupObjs.indexOf(instanceHome.first)
                if (index >= 0) groupObjs[index] = withMembers(instanceHome.first, idMap.values)
            } else if (needsMove && groupObjs.isNotEmpty()) {
                // 实例不在任何组里，但展开簇原坐标落在某个组：贴着该组右侧停放并扩组，
                // 前提是夹缝里没有别的节点，避免把无关节点圈进来。
                val home = groupObjs
                    .mapNotNull { group -> groupRect(group)?.let { group to it } }
                    .filter { (_, rect) -> intersects(rect, cluster) }
                    .maxByOrNull { (_, rect) -> rect.width * rect.height }
                if (home != null) {
                    val homeObj = home.first
                    val homeRect = home.second
                    val candidate = Rect(homeRect.x + homeRect.width + 60.0, cluster.y, cluster.width, cluster.height)
                    val spot = freeSpot(candidate, obstacles)
                    val gap = Rect(
                        x = homeRect.x + homeRect.width,
                        y = spot.y,
                        width = spot.x - (homeRect.x + homeRect.width),
                        height = spot.height,
                    )
                    placed = spot
                    if (nodeRects.none { intersects(it, gap) }) {
                        val index = groupObjs.indexOf(homeObj)
                        if (index >= 0) groupObjs[index] = withBounding(homeObj, union(homeRect, spot.padded(12.0)))
                    } else {
                        synthetic = true
                    }
                } else {
                    placed = freeSpot(cluster, obstacles)
                    synthetic = true
                }
            } else if (needsMove) {
                placed = freeSpot(cluster, obstacles)
                synthetic = true
            } else {
                // 不挪、实例也不在组里：给它一个合成组，图组下拉里能整簇选中。
                synthetic = true
            }
            val shiftX = placed.x - cluster.x
            val shiftY = placed.y - cluster.y
            obstacles += placed
            val subgraphName = definition.string("name")?.takeIf { it.isNotBlank() } ?: "子图"
            if (synthetic) {
                val groupTitle = "子图 $subgraphName (#$instanceId)"
                syntheticGroups += buildJsonObject {
                    put("id", JsonPrimitive(nextGroupId++))
                    put("title", JsonPrimitive(groupTitle))
                    put(
                        "bounding",
                        buildJsonArray {
                            add(JsonPrimitive(placed.x - 24))
                            add(JsonPrimitive(placed.y - 24))
                            add(JsonPrimitive(placed.width + 48))
                            add(JsonPrimitive(placed.height + 48))
                        },
                    )
                }
            }

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
                val innerTitle = inner.string("title")
                buildJsonObject {
                    inner.forEach { (key, value) -> if (key !in setOf("id", "pos", "inputs", "outputs", "widgets_values", "title")) put(key, value) }
                    put("id", JsonPrimitive(newId))
                    // 标题带子图前缀：列表和节点图里都能看出这节点是从哪个子图展开的。
                    put("title", JsonPrimitive("$subgraphName · ${innerTitle ?: inner.string("type") ?: "节点"}"))
                    if (pos.isNotEmpty()) {
                        put(
                            "pos",
                            buildJsonArray {
                                add(JsonPrimitive(pos.getOrNull(0).doubleOrZero() + shiftX))
                                add(JsonPrimitive(pos.getOrNull(1).doubleOrZero() + shiftY))
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
            root.forEach { (key, value) -> if (key != "nodes" && key != "links" && key != "definitions" && key != "groups") put(key, value) }
            put("nodes", JsonArray(nodes))
            put("links", JsonArray(links))
            put(
                "groups",
                JsonArray(groupObjs + syntheticGroups),
            )
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

    /** 画布上的矩形；展开簇需要挪位置时用。 */
    private data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)

    private fun nodeRect(node: JsonObject): Rect {
        val pos = (node["pos"] as? JsonArray).orEmpty()
        val size = (node["size"] as? JsonArray).orEmpty()
        return Rect(
            x = pos.getOrNull(0).doubleOrZero(),
            y = pos.getOrNull(1).doubleOrZero(),
            width = size.getOrNull(0).doubleOrZero().takeIf { it > 0 } ?: 220.0,
            height = size.getOrNull(1).doubleOrZero().takeIf { it > 0 } ?: 140.0,
        )
    }

    private fun clusterRect(nodes: JsonArray): Rect {
        val rects = nodes.mapNotNull { (it as? JsonObject)?.let(::nodeRect) }
        if (rects.isEmpty()) return Rect(0.0, 0.0, 0.0, 0.0)
        val left = rects.minOf { it.x }
        val top = rects.minOf { it.y }
        val right = rects.maxOf { it.x + it.width }
        val bottom = rects.maxOf { it.y + it.height }
        return Rect(left, top, right - left, bottom - top)
    }

    private fun intersects(a: Rect, b: Rect): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    private fun groupRect(group: JsonObject): Rect? {
        val bounding = group["bounding"] as? JsonArray ?: return null
        if (bounding.size < 4) return null
        return Rect(
            x = bounding[0].doubleOrZero(),
            y = bounding[1].doubleOrZero(),
            width = bounding[2].doubleOrZero(),
            height = bounding[3].doubleOrZero(),
        )
    }

    private fun withBounding(group: JsonObject, rect: Rect): JsonObject = buildJsonObject {
        group.forEach { (key, value) -> if (key != "bounding") put(key, value) }
        put(
            "bounding",
            buildJsonArray {
                add(JsonPrimitive(rect.x))
                add(JsonPrimitive(rect.y))
                add(JsonPrimitive(rect.width))
                add(JsonPrimitive(rect.height))
            },
        )
    }

    /** 把展开出来的节点 id 记进组的显式成员（合并已有），组框可以不动。 */
    private fun withMembers(group: JsonObject, ids: Collection<Int>): JsonObject = buildJsonObject {
        group.forEach { (key, value) -> if (key != "nodeIds") put(key, value) }
        val existing = (group["nodeIds"] as? JsonArray).orEmpty()
            .mapNotNull { element -> (element as? JsonPrimitive)?.intOrNull }
        put(
            "nodeIds",
            buildJsonArray { (existing + ids).distinct().forEach { add(JsonPrimitive(it)) } },
        )
    }

    private fun union(a: Rect, b: Rect): Rect {
        val left = minOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        val right = maxOf(a.x + a.width, b.x + b.width)
        val bottom = maxOf(a.y + a.height, b.y + b.height)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun Rect.padded(by: Double): Rect = Rect(x - by, y - by, width + by * 2, height + by * 2)

    /**
     * 找一个不和已有节点/图组重叠的位置。
     *
     * 原坐标通常还能用；撞上了就整簇挪到所有障碍物右侧。宁可排远一点，
     * 也别让节点图预览上两簇叠在一起看不清。
     */
    private fun freeSpot(cluster: Rect, obstacles: List<Rect>): Rect {
        var rect = cluster
        var guard = 0
        while (obstacles.any { intersects(it, rect) } && guard++ < 200) {
            val right = obstacles.maxOf { it.x + it.width }
            rect = Rect(right + 240.0, rect.y, rect.width, rect.height)
        }
        return rect
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.doubleOrZero(): Double =
        (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
}
