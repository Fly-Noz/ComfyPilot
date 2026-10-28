# ComfyPilot 缺陷分析报告

日期：2026-10-28
代码基线：`100f7c7`（报告生成前工作区干净）
版本：0.2.0（versionCode 2）
分析范围：Android 详情页/提交链路、共享工作流转换层、鸿蒙 NAPI 桥
测试基线（最近一次构建结果）：`:shared:jvmTest` 264 通过 / 0 失败；`:app:testDebugUnitTest` 22 通过 / 0 失败

---

## 一、摘要

本轮共定位 8 个缺陷/设计缺口，其中 1 个会直接阻断提交，3 个会导致“界面看起来已启用、实际未生效”。

| 编号 | 标题 | 严重度 | 状态 | 直接影响 |
|---|---|---|---|---|
| BUG-01 | Reroute / PrimitiveNode 未被穿透，连线经过即阻断提交 | P0 | 代码可证 + 真机现象 | 含 Reroute 的工作流完全无法提交 |
| BUG-02 | 手动启用的节点仍被判为“未启用”，置顶也不生效 | P1 | 代码可证 + 用户复现 | 开关误导、置顶无效、可能漏提交 |
| BUG-03 | 辅助图组按“节点中心点”判组，组开关漏节点 | P1 | 用户确认“不在组里” | 组开关对漏掉的节点完全无效 |
| BUG-04 | 辅助图组开关语义不对称：只能强制启用，不能强制关闭 | P2 | 代码可证 | 组内节点本就正常时，开关是空操作 |
| BUG-05 | 前端专用节点被误报“服务端没有定义，提交会被拒绝” | P2 | 真机截图 | 红色错误提示误导，且多发无效请求 |
| BUG-06 | 节点开关与节点图选择都不持久化 | P2 | 设计缺口 | 重启后“昨天正常、今天失效” |
| BUG-07 | 断线窗口内 `hostScope` 退化为空，置顶/备注/草稿存到另一个键 | P3 | 代码可证 | 置顶、备注偶发丢失 |
| BUG-08 | `napi_init.cpp` 三处 GBK 乱码，其中一处兜底 JSON 非法 | P3 | 代码可证 | 鸿蒙自检失败时返回乱码/非法 JSON |

---

## 二、缺陷详情

### BUG-01 Reroute / PrimitiveNode 未被穿透，连线经过即阻断提交

**严重度**：P0

**现象**

工作流详情底部显示：

```
不可提交：#29 CLIPTextEncode：输入 clip 来自节点 #42 Reroute，但服务端没有该节点的定义
```

提交按钮禁用。卡片上 `#64 Reroute` 同时显示“服务端没有该节点的定义，不能调参；提交会被拒绝”。

**复现条件**

任意网页格式工作流，只要某个输出节点的可达路径上存在一个 `Reroute`（或 `PrimitiveNode`），且该节点处于正常模式。

**根因链**

1. `WorkflowCodec.kt:21` 把 `Reroute`、`PrimitiveNode`、`Note`、`MarkdownNote` 列入 `FRONTEND_ONLY`；`parseDocument` 第 79 行把它们从 `nodeClasses` 中过滤掉，因此客户端**从未请求** `/object_info/Reroute`，`definitions["Reroute"]` 永远是 null。
2. `PromptBuilder.kt:384-412` 的 `UiGraphResolver.resolveOutput` 只有一条穿透分支：`node.mode == MODE_BYPASS`（4）时顺着输入继续往上游找。`Reroute` 是普通模式（0），于是直接返回 `ResolvedLink(rerouteId, slot)`，把 Reroute 自己当成真实来源。
3. `PromptBuilder.kt:252-262` 检查来源节点定义，`originDefinition == null` 即生成 `BuildProblem`。
4. `PromptBuilder.kt:194-195` 只要 problems 非空就返回 `PromptBuild.Blocked`；`WorkflowDecision.kt:94` 的 `canSubmit` 随之为 false。

**为什么必须特殊处理**

Reroute 是 LiteGraph 画布上的纯视觉转接点，1 进 1 出、类型直通，API 图里不存在。桌面端 `graphToPrompt` 会把下游输入直接折到 Reroute 的上游。代码里已经为旁路节点实现了同构逻辑（`bypassSlot` + `resolveInput` 递归），但 Reroute 没有接入。

`PrimitiveNode` 属于同一类问题：它应把本身携带的字面量写进下游输入，当前既不产出节点，也不会在解析连线时被跳过，因此同样会把下游判成“来源缺失”。

**建议修复**

- 在 `resolveOutput` 的 `when` 中增加 `Reroute` 分支：直接 `resolveInput(node, 0, seen)`；类型按输入透传。
- `PrimitiveNode` 需要单独处理：解析其 `widgets_values[0]` 作为字面量写入下游输入，而不是解析连线。
- 补两条 `PromptBuilderTest`：`reroute between source and consumer`、`primitive node supplies literal value`。

**当前测试缺口**

`WorkflowCodecTest` 只验证了“Reroute 不进入 nodeClasses”，没有任何测试覆盖“连线经过 Reroute 的转换”。

---

### BUG-02 手动启用的节点仍被判为“未启用”，置顶也不生效

**严重度**：P1

**现象**

- 节点在文件里是旁路/静音，用户手动点开后，卡片仍显示“节点图未启用，不会被提交”；
- 置顶该节点没有反应，不会排到输入区前面；
- 用户描述：“手动启用的节点还算未启用”。

**根因：三处判断用了三份不同的图**

| 判断 | 使用的图 | 是否包含 `enabledNodeIds` |
|---|---|---|
| 图是否启用、哪些节点算“未启用” | `WorkflowGraphs.analyze(document.raw)` | 否 |
| `orderReachable`（排序/置顶） | 文件 + `forcedNormalNodeIds` | 否（刻意） |
| 最终 prompt 的节点 mode | 文件 + `forcedNormalNodeIds` + `enabledNodeIds` | 是 |

具体代码：

1. `WorkflowDecision.kt:124-128` 调用 `WorkflowGraphs.analyze(document, definitions)`，函数签名里没有 `enabledNodeIds`；`enabled` 由文件里的 `mode` 决定（`WorkflowGraphs.kt:75/96`）。
2. `WorkflowDecision.kt:131` 的 `selectedGraphIds` 仍取“文件里默认启用的图”；`WorkflowDecision.kt:45-46` 的 `disabledNodeIds` 是“未选中图的全部节点”，手动启用不会把它移出。
3. `WorkflowDetailScreen.kt:1864-1869` 的提示顺序是 `missing → !enabled → graphDisabled → !reachable`，因此手动启用的节点仍然命中 `graphDisabled`，显示“节点图未启用”。
4. `WorkflowFields.kt:150-159` 的排序图只叠加 `forcedNormalNodeIds`，刻意不含 `enabledNodeIds`；`orderReachable` 因此保持 false。
5. `WorkflowDecision.kt:245-250` 的 `orderNodes` 第一排序键要求 `orderReachable && pinned`，手动启用的节点永远进不了置顶区。

**提交侧的真实风险**

`enabledNodeIds` 在 `PromptBuilder` 里确实会改 mode，但 roots 只来自“已选中图的输出节点”（`PromptBuilder.kt:166-172`）。如果该节点服务的输出图今天没被选中，它仍然不可达，会被 `included` 过滤，只留一条 warning：`已忽略 N 个未连接到输出节点的节点`（`PromptBuilder.kt:188-191`）。提交照样成功，生成结果里却没有这个节点。

**建议修复**

- 把 `enabledNodeIds` / `bypassedNodeIds` 传入图级分析，或在 `WorkflowAnalysis` 中提供“effective enabled”视图。
- 为 `orderReachable` 增加“用户手动启用”这一维：排序应忽略“用户手动关闭”，但不能忽略“用户手动启用”。
- UI 提示改为基于 `reachable` 与“图是否选中”两个事实，不要用一个 `graphDisabled` 覆盖。

---

### BUG-03 辅助图组按“节点中心点”判组，组开关漏节点

**严重度**：P1

**现象**

用户确认：参考图2 的 LoadImage “不在组里”。辅助图组的开关对它没有任何影响。

**根因**

`UiWorkflow.kt:162-166`：

```kotlin
fun contains(node: UiNode): Boolean {
    val centerX = node.x + node.width / 2.0
    val centerY = node.y + node.height / 2.0
    return centerX >= x && centerX <= x + width && centerY >= y && centerY <= y + height
}
```

App 用**节点中心点**判断是否属于画布分组；桌面端 LiteGraph 用的是**包围盒相交**。带图片预览的 LoadImage 通常很高，中心点容易落到组框外。结果是桌面上看它在组里，App 里它成为“未归属节点图”。

由于 `WorkflowGraphs.kt:88-101` 的辅助图组只收集 `group.contains(node)` 的节点：

- 该节点不在任何 `WorkflowGraph.nodeIds` 里；
- 组开关的 `forcedNormalNodeIds` 永远不会包含它；
- `disabledNodeIds` 也不会包含它（它不属于任何未启用的图），所以连“未启用”提示都没有；
- 它落在输入区的“其他节点”里，只能靠节点自己的开关。

**边界说明**

如果桌面端也显示该节点不在组内，则属于工作流本身的分组方式，不是客户端缺陷。判断方法：对比组框矩形与节点的 `pos/size`，看中心点是否落在框外。

**建议修复**

- 把 `contains` 改成包围盒相交，与 LiteGraph 对齐；
- 补一个“大节点只部分落在组内”的 `WorkflowGraphsTest`。

---

### BUG-04 辅助图组开关语义不对称

**严重度**：P2

**现象**

辅助图组的勾选/取消对工作流运行没有可观察影响。

**根因**

辅助图组 `outputNodeIds` 为空，选中时只贡献 `forcedNormalNodeIds`（`WorkflowDecision.kt:139-143`）：

- 勾选 = 把组内文件里 mode 为 2/4 的节点临时改回 0；
- 取消 = 撤掉临时覆盖，回到文件里的 mode。

它**不会**在取消时把正常节点改成旁路。因此：

- 组内节点本来就全是 mode 0 时，勾选/取消都是空操作；
- 组内节点本来是旁路时，勾选有效，取消会回到旁路；
- 如果组内节点在本次会话被手动关闭（`bypassedNodeIds`），`withEffectiveModes` 的优先级是 `bypassedNodeIds > enabledNodeIds > forcedNormalNodeIds`（`UiWorkflow.kt:119-127`），组开关无法把它拉回来。

**建议**

- 明确产品语义：要么增加 `forcedBypassNodeIds` 让“取消”真正关闭整组，要么把 UI 文案从“开关”改成“启用组内被旁路的节点”；
- 手动关闭优先于图级启用这条规则本身是合理的，但需要在 UI 上说明，否则看起来就是开关失效。

---

### BUG-05 前端专用节点被误报“提交会被拒绝”

**严重度**：P2

**现象**

`Label (rgthree)`、`Fast Groups Bypasser (rgthree)` 等节点整卡标红：

```
服务端没有该节点的定义，不能调参；提交会被拒绝。
```

但底栏仍显示“22 个会提交”，按钮可点，实际提交也不会被拒。

**根因**

1. `SessionReducer.kt:601-604`：`/object_info` 返回 `{}` 时记为 `NodeDefinition(missing = true)`。
2. `WorkflowFields.kt:216-229`：`missing` 节点被建成 `WorkflowNodeView(missing = true, fields = emptyList())`，所以显示“0 个输入”。
3. `WorkflowDetailScreen.kt:1864-1866`：只要 `missing` 就输出“提交会被拒绝”，没有区分“真缺后端节点”和“前端专用节点”。
4. `PromptBuilder.kt:51-58` 的 `UI_ONLY_NODE_TYPES` 已经包含 `Label (rgthree)`、`Fast Groups Bypasser (rgthree)`、`Note`、`MarkdownNote`、`Reroute`、`PrimitiveNode`，转换时第 134 行会直接跳过；`WorkflowDecision.canSubmit` 也不看 `missingNodeClasses`。

**附带问题：两份白名单不一致**

`WorkflowCodec.kt:21` 的 `FRONTEND_ONLY` 只有 4 项，缺 `Label (rgthree)` 和 `Fast Groups Bypasser (rgthree)`。这两类因此仍会各发一次 `/object_info` 请求，拿到 `{}` 后才归入 missing，属于无意义往返。

**建议**

- 统一两份白名单，并抽成共享常量；
- `WorkflowNodeView` 增加 `uiOnly` 标记，卡片文案改成“前端标注节点，不参与执行”；
- 只有“后端节点缺失且位于可达路径”时才显示“提交会被拒绝”。

---

### BUG-06 节点开关与节点图选择都不持久化

**严重度**：P2（设计缺口）

**现象**

昨天可用的参考图，今天重启后行为不一致；用户需要重新开启节点。

**根因**

- `bypassedNodeIds` / `enabledNodeIds` 只存在 `SessionState`，`onWorkflowOpenRequested` 会清空（`SessionReducer.kt:502-508`），进程重启也归零；
- 节点图选择 `enabledGraphOverride` 只是 `remember(document.path)`（`WorkflowDetailScreen.kt:272`），连磁盘都不写；
- 持久化的只有参数草稿（`WorkflowDraftStore` 只有 `loadOverrides/saveOverrides`）。

这解释了“昨天正常、今天失效”：昨天在同一个进程里选中过某个图或某个节点，选择一直有效；今天重启回到文件默认。

**建议**

- 把“节点开关 + 图选择”按 `host + workflow` 持久化，和参数草稿使用同一套作用域；
- 或者在打开工作流时明确提示“本次会话的开关状态不会保存”。

---

### BUG-07 断线窗口内 `hostScope` 退化为空

**严重度**：P3

**现象**

置顶、备注、参数草稿偶发“没保存”。

**根因**

`WorkflowDetailScreen.kt:271`：

```kotlin
val hostScope = hostScopeOf(state.lease?.baseUrl ?: "")
```

重连过程中 `lease` 为空，`hostScope` 变成空串。此时 `PinnedNodeStore.save` / `NodeNoteStore.save` / `WorkflowDraftStore.save` 会写到 legacy key（`workflow:<path>`），而恢复连接后读取用的是 `host|workflow:<path>`，两边对不上。

**建议**

- 用“最后一次已知的 baseUrl”（`SessionHost.baseUrl` 或本地记录）而不是当前 lease；
- 或者在 `hostScope` 为空时拒绝写入，避免污染 legacy key。

---

### BUG-08 `napi_init.cpp` 三处 GBK 乱码，其中一处兜底 JSON 非法

**严重度**：P3

**现象**

`harmony/entry/src/main/cpp/napi_init.cpp` 第 33、60、63 行是 GBK 被当 UTF-8 写入的产物：

- 第 33 行：注释乱码；
- 第 60 行：`cp_self_test_json` 的兜底消息乱码；
- 第 63 行：兜底 JSON 的结尾引号被替换成 `?`，返回的内容不是合法 JSON。

第 63 行原文：

```cpp
return MakeString(env, R"({"ok":false,"message":"鑷 JSON 瓒呭嚭缂撳啿鍖?})");
```

只在自检缓冲区不足时触发，但一旦触发，ArkTS 侧 `JSON.parse` 会失败。

**建议**

- 重写这三处字符串；
- 在 CI 或脚本里加一次“源文件必须为合法 UTF-8”的检查。

---

## 三、共性根因

这批缺陷背后有两个重复出现的模式。

### 1. “三份图”没有统一

同一个工作流在三个地方被重新解释：

- 图级分析（`WorkflowGraphs.analyze`）只看文件原文；
- 字段/排序（`WorkflowFields.build`）看文件 + 图选择；
- 提交（`PromptBuilder.build`）看文件 + 图选择 + 节点开关。

三份图的输入不同，导致同一节点在界面上、排序上、prompt 里可能得到三种结论。BUG-02、BUG-04、BUG-06 都是这个模式的产物。

### 2. 前端虚拟节点只处理了“不产出”，没处理“穿透”

`Reroute`、`PrimitiveNode`、`Note` 这类节点在 `PromptBuilder` 里被跳过产出，但连线解析没有同步跳过/解引用。BUG-01 和 BUG-05 都源于此。

---

## 四、修复优先级建议

1. **BUG-01（P0）**：先补 Reroute 穿透和对应单测；这是唯一会让整张工作流完全不可提交的缺陷。
2. **BUG-02 + BUG-04（P1/P2）**：把 effective state 统一进图级分析，明确“节点开关/图开关”的优先级，并在 UI 上如实反映。
3. **BUG-03（P1）**：改判组规则为包围盒相交，补大节点用例。
4. **BUG-05（P2）**：统一白名单，区分 `uiOnly` 与 `missing`。
5. **BUG-06（P2）**：持久化开关与图选择，或明确不持久化的提示。
6. **BUG-07、BUG-08（P3）**：随手修，适合作为独立小提交。

---

## 五、建议补充的回归测试

| 测试 | 覆盖缺陷 |
|---|---|
| `PromptBuilderTest.reroute between source and consumer` | BUG-01 |
| `PromptBuilderTest.primitive node supplies literal value` | BUG-01 |
| `WorkflowFieldsTest`：手动启用文件旁路节点后 `orderReachable` 为 true | BUG-02 |
| `WorkflowDecisionTest`：手动启用节点后 `disabledNodeIds` 不再包含它 | BUG-02 |
| `WorkflowGraphsTest`：大节点部分落在组内仍被识别 | BUG-03 |
| `WorkflowDecisionTest`：取消辅助图组不改变正常节点的 prompt | BUG-04 |
| `PromptBuilderTest`：`Label (rgthree)` 不产生 BuildProblem | BUG-05 |
| `PinnedNodeStoreTest`：空 hostScope 不写 legacy key | BUG-07 |
| 源文件 UTF-8 校验脚本 | BUG-08 |

---

## 六、附录：代码索引

| 文件 | 相关位置 |
|---|---|
| `shared/.../workflow/PromptBuilder.kt` | 51-58 白名单；133-146 跳过/缺失；166-207 roots/可达/Blocked；252-262 来源缺失；357-374 可达性；382-442 解析器 |
| `shared/.../workflow/WorkflowDecision.kt` | 45-46 disabled；94 canSubmit；124-143 图分析；245-250 排序 |
| `shared/.../workflow/WorkflowFields.kt` | 150-159 排序图；216-229 missing 节点 |
| `shared/.../workflow/WorkflowGraphs.kt` | 75/96 enabled；88-113 辅助图组 |
| `shared/.../workflow/UiWorkflow.kt` | 119-127 模式优先级；162-166 中心点判组 |
| `shared/.../workflow/WorkflowCodec.kt` | 21 FRONTEND_ONLY；72-95 文档解析 |
| `shared/.../net/SessionReducer.kt` | 502-508 清空开关；601-604 缺失定义 |
| `app/.../ui/WorkflowDetailScreen.kt` | 271-276 hostScope/置顶初始化；338-360 图分组；1864-1869 卡片提示 |
| `harmony/entry/src/main/cpp/napi_init.cpp` | 33、60、63 乱码 |
