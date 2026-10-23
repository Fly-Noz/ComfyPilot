# 阶段 S7 · 导航对齐（Android 保留详情 / 鸿蒙详情预览）

日期：2026-10-23

关联：

- `docs/design/navigation-parity.md`
- `.scratch/s7-parity/issues/05-navigation-parity.md`
- `.scratch/s7-parity/issues/06-android-retain-workflow-detail.md`
- `.scratch/s7-parity/issues/07-harmony-detail-preview.md`

## 结论

| 层级 | 结果 |
|---|---|
| Android 单测 | **13 / 0 / 0** |
| Android 构建 | `:app:assembleDebug` 成功 |
| Android 安装 | 导航版本已安装到 `1575924193008T5`；最新 APK 安装被系统权限拦截 |
| 鸿蒙构建 | `harmony/build.ps1` 成功 |
| 鸿蒙安装 | 已安装到 `33Z0224613077036` 并启动 |
| Android 真机交互 | **待用户验证**：最新 APK 安装被系统权限拦截，当前设备仍是导航版本 |
| 鸿蒙真机交互 | **通过**：提交后停留详情、进度/采样图、产物入口均已确认 |

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `harmony/entry/build/default/outputs/default/entry-default-signed.hap`

## 本次改动

### Android：任务覆盖层保留详情

`ComfyPilotApp` 原先把“任务”当成独立 `Screen.Tasks`，从详情进入后
`WorkflowDetailScreen` 离开组合，字段 override、节点图选择、页签、展开状态
全部丢失；返回也固定回目录。

现在：

- 从详情点“任务”时设置 `tasksOverlay`，把 `TaskScreen` 覆盖在仍挂载的详情上；
- 覆盖层“返回”和系统返回先关闭覆盖层，回到原详情；
- 从工作流目录进入“任务”仍是独立页面，返回目录；
- 覆盖层里的取消、产物、诊断、断开连接行为保持原样。

同时，详情“预览”页签只显示当前 `workflowPath` 的任务，避免把别的工作流的
采样图或产物显示在当前工作流下。

### 鸿蒙：详情输入 / 预览

鸿蒙原先只在“任务”页签显示实时预览，提交后执行 `this.tab = 1` 直接跳到
任务页；详情没有产物入口。

现在：

- 工作流详情增加 `输入 / 预览` 切换；
- 提交/确认提交成功后留在当前详情，并切到预览；
- 预览按 `workflowPath === selectedPath` 找当前工作流任务：
  - 活动任务显示状态、采样进度、`#节点 · 当前步/总步数` 和采样图；
  - 完成后显示最近产物图片和“查看产物”；
  - 没有任务时显示空状态；
- `LivePreviewCard` 改为无参数 builder，直接读取 `this.tasks` 的当前任务，
  按 promptId 匹配预览帧，避免任务页/详情页串图，同时修复任务快照不刷新；
- 任务页签原有采样预览和产物面板保持不变。

收尾状态：鸿蒙详情预览已真机通过（issue 07 `resolved`）；Android 保留详情
（issue 06）仍待真机回归。

## 请用户验证

### Android

1. 连接 ComfyUI，打开一个工作流，修改一个字段、切换一个节点图、切到“预览”
   再切回“输入”。
2. 点顶部“任务”，确认任务页打开；用顶部“返回”或系统返回。
3. 确认回到同一个工作流详情，字段、节点图开关、当前页签和展开状态都还在。
4. 提交任务，确认停留在详情的“预览”页签；任务完成后能打开产物。
5. 从工作流目录点“任务”，返回应回到目录。

### 鸿蒙

1. 连接 ComfyUI，打开一个工作流，确认详情出现“输入 / 预览”。
2. 修改字段后提交，确认仍停留在当前工作流详情，并自动切到“预览”。
3. 运行中确认能看到进度/采样图；完成后确认能看到产物图并打开产物面板。
4. 切到“任务”页签，再切回“工作流”页签，确认仍停在原工作流详情和预览页签。
5. 从详情“查看全部任务”进入任务页，返回工作流页签时详情状态仍在。

## 已知限制

- 两端都只在任务带 `workflowPath` 时按工作流过滤；升级前的旧任务没有该字段，
  不会出现在详情预览中，但仍可在任务页查看。
- 鸿蒙日志/诊断仍是第三个主入口，是否降为次级入口留给
  `08-harmony-diagnostics-entry.md`。
