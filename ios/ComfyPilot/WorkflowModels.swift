import Foundation

/// 一个可编辑输入项。字段来自共享层的 `workflowView` 响应，Swift 不重算任何规则。
struct WorkflowField: Identifiable {
    let nodeId: Int
    /// 节点类型。判断「能不能选图上传」要靠它（LoadImage 的 image 控件）。
    let nodeType: String
    let inputName: String
    let label: String
    let type: String
    let options: [String]
    let valueText: String
    let valueKind: String
    let linked: Bool
    let sourceLabel: String
    let required: Bool
    /// 是不是前端控件（可调），以及是不是常用参数——决定节点卡默认展开与否。
    let widget: Bool
    let common: Bool

    var id: String { "\(nodeId).\(inputName)" }

    var isSelect: Bool { !options.isEmpty }
    var isBool: Bool { valueKind == "boolean" }
    var isNumber: Bool { valueKind == "number" }

    /// 由连线提供值的字段不给编辑：真正的值在上游节点，改了也不会生效。
    var editable: Bool { !linked }

    /// 和安卓一致：只有 LoadImage 的 image 控件走相册上传。
    var isImageUpload: Bool { nodeType == "LoadImage" && inputName == "image" }
}

struct WorkflowNode: Identifiable {
    let id: Int
    let type: String
    let title: String
    let enabled: Bool
    let missing: Bool
    /// 没连到输出节点：提交时会被跳过，卡片上要说明。
    let reachable: Bool
    /// 有必填项还没填。
    let needsInput: Bool
    /// 子图标签；同一标签的节点在列表里合成一簇。
    let subgraph: String
    let subgraphWidgets: String
    let fields: [WorkflowField]

    /// 安卓的默认展开规则：置顶，或含「常用且可调的控件字段」。
    var autoExpanded: Bool {
        fields.contains { $0.common && $0.widget && !$0.linked }
    }
}

struct WorkflowGraphChip: Identifiable {
    let id: String
    let title: String
    let enabled: Bool
}

/// 画布上的一个节点：坐标来自工作流文件，可能是任意尺度（ComfyUI 前端按 1x 存）。
struct GraphNode: Identifiable {
    let id: Int
    let type: String
    let title: String
    let mode: Int
    let x: Double
    let y: Double
    let width: Double
    let height: Double
    let inputs: [GraphSlot]
    let outputs: [GraphSlot]

    var label: String { title.isEmpty ? type : title }
}

struct GraphSlot: Identifiable {
    var id: String { "\(name)-\(index)" }
    let index: Int
    let name: String
    let type: String
    let linkId: Int
}

/// 一条连线：从 origin 节点的第 originSlot 个输出，到 target 节点的第 targetSlot 个输入。
struct GraphLink: Identifiable {
    let id: Int
    let originId: Int
    let originSlot: Int
    let targetId: Int
    let targetSlot: Int
    let type: String
}

/// 只读节点图。共享层已经算好每个节点的实际模式（含本次会话的开关），这里不重算规则。
struct WorkflowGraph {
    var nodes: [GraphNode] = []
    var links: [GraphLink] = []

    static func parse(_ object: [String: Any]) -> WorkflowGraph? {
        guard object["nodes"] is [[String: Any]] else { return nil }
        var graph = WorkflowGraph()
        graph.nodes = (object["nodes"] as? [[String: Any]] ?? []).map { raw in
            GraphNode(
                id: Int(int64(raw["id"])),
                type: string(raw["type"]),
                title: string(raw["title"]),
                mode: Int(int64(raw["mode"])),
                x: double(raw["x"]),
                y: double(raw["y"]),
                width: max(double(raw["w"]), 80),
                height: max(double(raw["h"]), 40),
                inputs: (raw["inputs"] as? [[String: Any]] ?? []).enumerated().map { index, slot in
                    GraphSlot(
                        index: index,
                        name: string(slot["name"]),
                        type: string(slot["type"]),
                        linkId: Int(int64(slot["linkId"]))
                    )
                },
                outputs: (raw["outputs"] as? [[String: Any]] ?? []).enumerated().map { index, slot in
                    GraphSlot(
                        index: index,
                        name: string(slot["name"]),
                        type: string(slot["type"]),
                        linkId: 0
                    )
                }
            )
        }
        graph.links = (object["links"] as? [[String: Any]] ?? []).map { raw in
            GraphLink(
                id: Int(int64(raw["id"])),
                originId: Int(int64(raw["originId"])),
                originSlot: Int(int64(raw["originSlot"])),
                targetId: Int(int64(raw["targetId"])),
                targetSlot: Int(int64(raw["targetSlot"])),
                type: string(raw["type"])
            )
        }
        return graph
    }

    private static func double(_ value: Any?) -> Double {
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) ?? 0 }
        return 0
    }

    private static func int64(_ value: Any?) -> Int64 {
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String { return Int64(text) ?? 0 }
        return 0
    }

    private static func string(_ value: Any?) -> String {
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }
}

/// `{"op":"view"}` 的响应。
struct WorkflowView {
    var ok = false
    var state = ""
    var path = ""
    var name = ""
    var canSubmit = false
    var blockReason = ""
    var message = ""
    var problems: [String] = []
    var warnings: [String] = []
    var graphs: [WorkflowGraphChip] = []
    var nodes: [WorkflowNode] = []
    var commonFields: [WorkflowField] = []
    /// 只读节点图（坐标 + 连线）。共享层没给就为空。
    var graph: WorkflowGraph?

    var visibleNodes: [WorkflowNode] { nodes.filter { !$0.fields.isEmpty } }

    static func parse(_ json: String) -> WorkflowView? {
        guard let data = json.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        var view = WorkflowView()
        view.ok = bool(object["ok"])
        view.state = string(object["state"])
        view.path = string(object["path"])
        view.name = string(object["name"])
        view.canSubmit = bool(object["canSubmit"])
        view.blockReason = string(object["blockReason"])
        view.message = string(object["message"])

        view.problems = (object["problems"] as? [[String: Any]] ?? []).compactMap { problem in
            let text = string(problem["message"])
            return text.isEmpty ? nil : text
        }
        view.warnings = (object["warnings"] as? [Any] ?? []).compactMap { $0 as? String }

        view.graphs = (object["graphs"] as? [[String: Any]] ?? []).map { graph in
            WorkflowGraphChip(
                id: string(graph["id"]),
                title: string(graph["title"]),
                enabled: bool(graph["enabled"])
            )
        }
        view.nodes = (object["nodes"] as? [[String: Any]] ?? []).map { node in
            WorkflowNode(
                id: Int(int64(node["id"])),
                type: string(node["type"]),
                title: string(node["title"]).isEmpty ? string(node["displayName"]) : string(node["title"]),
                enabled: bool(node["enabled"]),
                missing: bool(node["missing"]),
                reachable: bool(node["reachable"]),
                needsInput: bool(node["needsInput"]),
                subgraph: string(node["subgraph"]),
                subgraphWidgets: string(node["subgraphWidgets"]),
                fields: (node["fields"] as? [[String: Any]] ?? []).map { field($0) }
            )
        }
        view.commonFields = (object["commonFields"] as? [[String: Any]] ?? []).map { field($0) }
        if let raw = object["graph"] as? [String: Any] {
            view.graph = WorkflowGraph.parse(raw)
        }
        return view
    }

    private static func field(_ raw: [String: Any]) -> WorkflowField {
        WorkflowField(
            nodeId: Int(int64(raw["nodeId"])),
            nodeType: string(raw["nodeType"]),
            inputName: string(raw["inputName"]),
            label: string(raw["label"]),
            type: string(raw["type"]),
            options: (raw["options"] as? [Any] ?? []).compactMap { $0 as? String },
            valueText: string(raw["valueText"]),
            valueKind: string(raw["valueKind"]),
            linked: bool(raw["linked"]),
            sourceLabel: string(raw["fromNodeLabel"]),
            required: bool(raw["required"]),
            widget: bool(raw["widget"]),
            common: bool(raw["common"])
        )
    }

    private static func string(_ value: Any?) -> String {
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }

    private static func bool(_ value: Any?) -> Bool {
        if let flag = value as? Bool { return flag }
        if let number = value as? NSNumber { return number.boolValue }
        return false
    }

    private static func int64(_ value: Any?) -> Int64 {
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String { return Int64(text) ?? 0 }
        return 0
    }
}
