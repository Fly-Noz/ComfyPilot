# 阶段 S7-01 · 收敛共享工作流决策层

日期：2026-10-23

## 结论

| 层级 | 结果 |
|---|---|
| 共享层单测 | **通过**：`:shared:jvmTest` **203 / 0 / 0**，其中新增 7 个决策用例 + 1 个桥视图用例 |
| 安卓单测 | **通过**：`:app:testDebugUnitTest` **11 / 0 / 0** |
| 安卓构建 | **通过**：`:app:assembleDebug`，APK 已安装到 `1575924193008T5` |
| 鸿蒙构建 | **通过**：`harmony/build.ps1`，`entry-default-signed.hap` 已生成 |
| 鸿蒙真机 | **待验证**：当前 `hdc list targets` 为空，设备连上后安装并回归 |
| 安卓真机 | **待用户回归**：已安装，等待提交/阻断/置顶/图启停/产物文件名验证 |

## 实现

新增 `shared/src/commonMain/kotlin/com/comfypilot/core/workflow/WorkflowDecision.kt`：

- `WorkflowDecisions.analyze(...)`：节点图选择、字段提取、待加载/缺失节点定义；
- `WorkflowDecisions.decide(...)`：叠加 overrides，生成排序后的节点、`PromptBuild`、
  `canSubmit`、阻断原因和警告；
- `WorkflowDecisions.orderNodes(...)`：置顶且可达最前，其余可达按缺值优先，
  未启用/旁路节点最后；
- `WorkflowDecision`：两端渲染和提交只读这一份结果；
- 唯一文件名后缀仍然只在 `decide(..., runToken = ...)` 时追加，视图请求不传 token。

两端接线：

- 安卓 `WorkflowDetailScreen` 不再调用 `WorkflowFields.build`、`WorkflowGraphs.analyze`、
  `PromptBuilder.build` 或本地 `orderInputNodes`；只缓存 `WorkflowAnalysis`，
  输入变化后延迟重算 `WorkflowDecision`；
- 鸿蒙 `cp_session_workflow` 的 `view` / `prompt` 都改为调用共享 `WorkflowDecisions`；
  `view` 新增 `blockReason`，ArkTS 不再本地拼接阻断原因；
- 删除安卓 `PinnedNodeStore.kt` 里的本地排序实现，`InputOrderTest` 改为验证共享排序。

## 行为保持

- 同一工作流、同一组图开关和 overrides 在共享层得到相同的 `canSubmit`、
  `problems`、`warnings` 和 prompt；
- 图关闭时置顶状态保留，但置顶节点不会提前；
- 字段编辑不把提交 token 写回视图；
- 提交按钮的可用性和按钮上方的阻断原因都来自同一个 `WorkflowDecision`；
- 提交函数再次构建带 token 的决策，避免在 120ms 防抖窗口内提交旧值。

## 测试命令

```powershell
.\gradlew.bat :shared:jvmTest --no-daemon
# 干净 Path 下运行，避免本机 Path 中未转义引号被测试 JVM 当成主类
$env:Path='D:\Tools\Java\jdk-21.0.6\bin;C:\Windows\system32;C:\Windows'
$env:CLASSPATH=''
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon

powershell -NoProfile -ExecutionPolicy Bypass -File .\harmony\build.ps1
```

## 真机回归清单

- 正常提交一次，确认产物文件名仍带唯一后缀；
- 关闭当前节点图，确认按钮置灰且阻断原因来自共享层；
- 置顶一个可达节点和一个未启用图里的节点，确认前者提前、后者留在最后；
- 切换节点图，确认字段可达性、警告和按钮状态同步变化；
- 鸿蒙与安卓对同一工作流、同一组图开关给出相同的阻断原因/警告。
