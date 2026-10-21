package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonElement

/**
 * 服务器 `user_data/workflows` 目录里的一个文件。
 *
 * 路径是相对 `workflows` 的、用 `/` 分隔的形式（`/userdata` 返回的就是这样），
 * 读取时按路径分段编码，不做任何"先下载到本地再解析"的假设。
 */
data class WorkflowEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long? = null,
    val modifiedAtMs: Long? = null,
)

/** 列表状态。失败也要进状态，界面上不能停在"加载中"骗人。 */
sealed interface WorkflowCatalog {
    data object Idle : WorkflowCatalog
    data object Loading : WorkflowCatalog
    data class Ready(val entries: List<WorkflowEntry>) : WorkflowCatalog
    data class Failed(val message: String) : WorkflowCatalog
}

enum class WorkflowFormat {
    /** `/prompt` 能吃：[nodeId] -> {class_type, inputs} */
    API,

    /** 网页端保存的格式：nodes / links / widgets_values。要转换才能提交。 */
    UI,

    /** 两种都不像：不要猜，交给上层显示原因。 */
    UNKNOWN,
}

/** 已读取的工作流原文 + 粗略识别结果。原文保留，因为提交/转换都以它为准。 */
data class WorkflowDocument(
    val path: String,
    val name: String,
    val raw: String,
    val format: WorkflowFormat,
    val nodeClasses: List<String>,
)

/** 当前选中的工作流。加载中、失败、成功三态互斥，避免 UI 用布尔位拼。 */
data class WorkflowSelection(
    val loadingPath: String? = null,
    val document: WorkflowDocument? = null,
    val error: String? = null,
) {
    val isReady: Boolean get() = document != null
}

// ---------------------------------------------------------------- 节点定义

/**
 * `/object_info/{class}` 里一个输入的定义。
 *
 * [isWidget] 是"这个输入在图里是一个控件（字面量），还是必须由上游连线提供"。
 * 判断只依据服务端声明的类型与 `forceInput`，不按节点名猜。
 */
data class NodeInputDef(
    val name: String,
    val type: String,
    val options: List<String> = emptyList(),
    val defaultValue: JsonElement? = null,
    val isWidget: Boolean = false,
    val required: Boolean = true,
    /** 前端会在该控件后面多塞一个"生成后控制"值，转换时要跳过一格。 */
    val controlAfterGenerate: Boolean = false,
    val tooltip: String? = null,
    /** `forceInput`：类型本来是控件，但被强制改成连线输入。 */
    val forceInput: Boolean = false,
    /** 该控件后面还跟着一个前端专用的上传按钮值（LoadImage）。 */
    val imageUpload: Boolean = false,
    /** 自动增长输入（`COMFY_AUTOGROW_V3`），在 API 里是 `name.sub` 形式的多个键。 */
    val autogrow: Boolean = false,
    /** 动态下拉框按当前选项展开出来的附加输入；是否必填取决于选项，不能一概而论。 */
    val dynamic: Boolean = false,
)

data class NodeDefinition(
    val className: String,
    val displayName: String = className,
    val description: String? = null,
    val inputs: List<NodeInputDef> = emptyList(),
    val outputs: List<String> = emptyList(),
    val outputNames: List<String> = emptyList(),
    /** 服务端标记的输出节点：提交的图必须至少有一个。 */
    val isOutputNode: Boolean = false,
    /** 服务端没有这个类：多半缺自定义节点。不要假装能调参。 */
    val missing: Boolean = false,
)
