# 07 · 鸿蒙详情页补预览并留在详情

Status: resolved
Type: task

## 问题

鸿蒙的实时预览和产物入口只在“任务”页签：

- `submitWorkflow()` / `confirmSubmit()` 成功后执行 `this.tab = 1`，直接跳到任务页；
- 工作流详情只有输入节点，没有“预览”页签；
- 完成后必须切到任务页才能打开产物。

Android 提交后留在详情页并切到“预览”页签。两端操作路径不一致。

## 范围

- 鸿蒙工作流详情增加“输入 / 预览”切换；
- 预览页显示当前工作流的活动任务：状态、进度、`#节点 · 当前/总步数`、采样图；
- 任务完成后显示最近一次产物缩略图和“查看产物”入口；
- 提交/确认提交后留在详情页并切到预览，不再自动跳任务页；
- 任务页签现有采样预览和产物行为保持不变；
- 不新增轮询；继续复用 `ComfyBridge.preview()` 和共享 `BridgeTask`。

## 验收

- 提交后仍停在当前工作流详情，预览页显示进度/采样图；
- 完成后预览页能打开该任务的产物面板；
- 从详情“查看全部任务”能切到任务页，返回工作流页签时详情和预览页签仍保留；
- 没有任务或没有预览帧时只显示等待/空状态，不报错；
- `harmony/build.ps1` 通过；设备连上后真机验证。

## Comments

- 2026-10-23：由 `05-navigation-parity` 拆出。代码位置：
  `harmony/entry/src/main/ets/pages/Index.ets` 的 `WorkflowDetail`、
  `submitWorkflow`、`confirmSubmit`、`LivePreviewCard`、`TasksTab`。
- 2026-10-23：实现完成，等待真机回归。详情增加 `detailTab`（输入/预览）；
  `submitWorkflow` / `confirmSubmit` 成功后不再 `this.tab = 1`，而是留在详情并
  切到预览。预览按 `workflowPath === selectedPath` 找当前工作流任务，显示
  进度/采样图；完成后显示最近产物图和“查看产物”。任务页签的全局预览保持
  不变，`LivePreviewCard` 增加任务参数并按 promptId 匹配帧，避免串图。
- 验证：`harmony/build.ps1` 通过；最新 HAP 已安装到 `33Z0224613077036` 并启动。
- 2026-10-23：提交时任务 `workflowPath` 改用 `selectedPath`，避免 `view.path` 为空
  导致详情预览按工作流过滤不到新任务；`LivePreviewCard` 增加进度条和百分比。
- 2026-10-23：真机验证通过。`LivePreviewCard` 从对象参数改为无参数 builder，
  直接读取 `this.tasks`，修复任务快照变化后卡片不刷新的问题；用户确认详情页
  提交后停留、进度/采样图和产物入口正常。状态改为 `resolved`。
