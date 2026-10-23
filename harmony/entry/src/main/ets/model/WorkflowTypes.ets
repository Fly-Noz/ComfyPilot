/**
 * 共享层 `cp_session_workflow` 返回的视图模型。
 *
 * 字段值同时给 `value` 和 `valueText`：前者保留 JSON 类型，后者用于文本框显示。
 * ArkTS 只需要渲染，不在本地判断字段是否可编辑、节点是否可达或节点图归属。
 */

export interface WorkflowGraphView {
  id: string;
  title: string;
  enabled: boolean;
  selected: boolean;
  nodeIds: number[];
  outputNodeIds: number[];
  /** 辅助图组所属的输出图 ID；输出图自身为空字符串。 */
  parentId: string;
}

export interface WorkflowFieldView {
  nodeId: number;
  nodeType: string;
  nodeTitle: string | null;
  inputName: string;
  label: string;
  type: string;
  options: string[];
  value: string | number | boolean | null;
  valueText: string;
  valueKind: string;
  linked: boolean;
  widget: boolean;
  required: boolean;
  common: boolean;
  needsInput: boolean;
}

export interface WorkflowNodeView {
  id: number;
  type: string;
  displayName: string;
  title: string | null;
  mode: number;
  missing: boolean;
  reachable: boolean;
  needsInput: boolean;
  pinned: boolean;
  fields: WorkflowFieldView[];
}

export interface BuildProblemView {
  nodeId: number | null;
  nodeType: string | null;
  message: string;
  display: string;
}

export interface WorkflowViewResponse {
  ok: boolean;
  state: string;
  message?: string;
  path: string;
  name: string;
  format: string;
  defaultGraphIds: string[];
  selectedGraphIds: string[];
  graphs: WorkflowGraphView[];
  nodes: WorkflowNodeView[];
  commonFields: WorkflowFieldView[];
  problems: BuildProblemView[];
  warnings: string[];
  pendingNodeClasses: string[];
  missingNodeClasses: string[];
  canSubmit: boolean;
  /** 共享层给出的阻断原因；可提交时为空字符串。 */
  blockReason: string;
  prompt: null;
}

export interface PromptResponse {
  ok: boolean;
  state: string;
  message?: string;
  warnings: string[];
  problems: BuildProblemView[];
  prompt: object | null;
}
