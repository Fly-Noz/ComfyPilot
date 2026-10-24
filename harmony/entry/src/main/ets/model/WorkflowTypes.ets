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
  /** 生效后的节点模式：0 正常、2 静音、4 旁路（文件 mode + 本次会话覆盖 + 图级强制）。 */
  mode: number;
  /**
   * 生效后是否执行。界面开关只读这个值：文件里保存为静音/旁路的节点同样显示为关闭，
   * 不能只看 `bypassed`（那只代表用户在本次会话里关掉的节点）。
   */
  enabled: boolean;
  /** 用户在本次会话里显式关闭了该节点。 */
  bypassed: boolean;
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
  graph: WorkflowGraphData;
  ok: boolean;
  state: string;
  message?: string;
  path: string;
  name: string;
  format: string;
  defaultGraphIds: string[];
  selectedGraphIds: string[];
  /** 共享层实际采用的关闭集合（请求里的覆盖，没带时取会话状态）。 */
  bypassedNodeIds: number[];
  /** 共享层实际采用的重新启用集合；关闭优先，两个集合互斥。 */
  enabledNodeIds: number[];
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

/**
 * `op=disable_impact` 的返回：关闭某个节点之前的影响探测。
 *
 * 只构建 prompt、不落盘、不发请求。`breaking` 为真时界面必须弹确认框，
 * 并把 `reason` 原样显示给用户——原因由共享层给出，宿主不自己判断。
 */
export interface DisableImpactResponse {
  ok: boolean;
  state: string;
  message?: string;
  nodeId: number;
  breaking: boolean;
  reason: string;
}

/** 节点图预览里的一枚参数槽位。 */
export interface GraphNodeInput {
  name: string;
  type: string;
  linkId: number;
}

export interface GraphNodeOutput {
  name: string;
  type: string;
}

/** 节点图预览的节点：坐标/尺寸直接用工作流文件里的画布坐标。 */
export interface GraphNodeView {
  id: number;
  type: string;
  title: string;
  mode: number;
  x: number;
  y: number;
  w: number;
  h: number;
  inputs: GraphNodeInput[];
  outputs: GraphNodeOutput[];
}

export interface GraphLinkView {
  id: number;
  originId: number;
  originSlot: number;
  targetId: number;
  targetSlot: number;
  type: string;
}

export interface GraphGroupView {
  id: number;
  title: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 节点图预览数据：拓扑已叠加本次会话的断线/改接。 */
export interface WorkflowGraphData {
  nodes: GraphNodeView[];
  links: GraphLinkView[];
  groups: GraphGroupView[];
  activeNodeIds: number[];
  disabledNodeIds: number[];
}

export interface GraphLinkOverride {
  nodeId: number;
  inputName: string;
  originNodeId: number;
  originSlot: number;
  type: string;
}