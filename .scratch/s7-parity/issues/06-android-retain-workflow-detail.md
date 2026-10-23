# 06 · Android 从详情进入任务后保留详情状态

Status: ready-for-human
Type: task

## 问题

Android 在 `ComfyPilotApp` 里把“任务”当成独立 `Screen.Tasks`。从工作流详情点“任务”后：

- `DetailBody` 离开组合，`overrides`、节点图选择、当前页签、展开节点、上传错误等 `remember` 状态全部丢失；
- 返回时固定回 `Screen.Catalog`，不是回到详情；
- 用户只是去看一眼进度，回来就得重新选工作流、重新调参。

鸿蒙的编辑状态在 `Index` 页面级保存，切到任务页签再回来不会丢。这是当前最影响切换设备体验的导航差异。

## 范围

- 从工作流详情打开任务页时，详情页保持挂载/保留状态；
- 任务页返回或系统返回时回到详情，而不是工作流目录；
- 从工作流目录打开任务页时，返回仍回目录；
- 任务页的取消、产物、诊断、断开行为不变；
- 不改变 `TaskScreen` 的公开参数（尽量复用现有组件）。

## 建议实现

在 `ComfyPilotApp` 增加“任务从详情打开”的来源状态，把 `TaskScreen` 作为覆盖层渲染在 `Detail` 之上，详情继续留在组合中；`BackHandler` 优先关闭覆盖层。这样不需要把 `DetailBody` 的所有局部状态上提，也不会引入新的路由库。

## 验收

- 在详情改一个字段、切一个节点图、展开/收起节点，再点“任务”，返回后这些状态都还在；
- 从目录点“任务”，返回仍是目录；
- 任务页里取消任务、打开产物、进诊断、断开连接都正常；
- `:app:testDebugUnitTest` 和 `:app:assembleDebug` 通过，Android 真机回归通过。

## Comments

- 2026-10-23：由 `05-navigation-parity` 拆出。代码位置：
  `app/src/main/kotlin/com/comfypilot/app/ui/ComfyPilotApp.kt`、
  `WorkflowDetailScreen.kt`。
- 2026-10-23：实现完成，等待真机回归。`ComfyPilotApp` 增加 `tasksOverlay`，
  从详情打开任务时把 `TaskScreen` 覆盖在仍挂载的详情之上；系统返回和任务页
  “返回”先关闭覆盖层回到详情，从目录进入任务仍回目录。
- 同时把详情预览页签的任务过滤到当前 `workflowPath`，避免显示别的工作流的
  采样图/产物。
- 验证：`:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过；最新 APK
  已安装到 `1575924193008T5` 并启动。
