import Foundation

/// 一个可编辑输入项。字段来自共享层的 `workflowView` 响应，Swift 不重算任何规则。
struct WorkflowField: Identifiable {
    let nodeId: Int
    let inputName: String
    let label: String
    let type: String
    let options: [String]
    let valueText: String
    let valueKind: String
    let linked: Bool
    let sourceLabel: String
    let required: Bool

    var id: String { "\(nodeId).\(inputName)" }

    var isSelect: Bool { !options.isEmpty }
    var isBool: Bool { valueKind == "boolean" }
    var isNumber: Bool { valueKind == "number" }

    /// 由连线提供值的字段不给编辑：真正的值在上游节点，改了也不会生效。
    var editable: Bool { !linked }
}

struct WorkflowNode: Identifiable {
    let id: Int
    let type: String
    let title: String
    let enabled: Bool
    let missing: Bool
    let fields: [WorkflowField]
}

struct WorkflowGraphChip: Identifiable {
    let id: String
    let title: String
    let enabled: Bool
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
                fields: (node["fields"] as? [[String: Any]] ?? []).map { field($0) }
            )
        }
        view.commonFields = (object["commonFields"] as? [[String: Any]] ?? []).map { field($0) }
        return view
    }

    private static func field(_ raw: [String: Any]) -> WorkflowField {
        WorkflowField(
            nodeId: Int(int64(raw["nodeId"])),
            inputName: string(raw["inputName"]),
            label: string(raw["label"]),
            type: string(raw["type"]),
            options: (raw["options"] as? [Any] ?? []).compactMap { $0 as? String },
            valueText: string(raw["valueText"]),
            valueKind: string(raw["valueKind"]),
            linked: bool(raw["linked"]),
            sourceLabel: string(raw["fromNodeLabel"]),
            required: bool(raw["required"])
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
