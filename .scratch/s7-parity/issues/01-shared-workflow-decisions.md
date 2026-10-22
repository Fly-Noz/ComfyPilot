# 01 · 收敛共享工作流决策层

Status: needs-triage
Type: task

## 问题

安卓的 `WorkflowDetailScreen` 仍在 UI 层直接调用 `WorkflowFields.build`、`WorkflowGraphs.analyze`、`PromptBuilder.build`，并本地实现 `orderInputNodes`。鸿蒙使用共享的 `WorkflowUi`。

结果是同一个规则要写两遍。S6 收尾加唯一文件名后缀时，安卓和鸿蒙就各改了一次；后续的 `canSubmit`、图选择、置顶排序、警告策略也会继续漂移。

## 范围

- 抽出两端都能用的工作流编辑决策：
  - 选中的节点图与强制正常执行的节点；
  - 字段排序与置顶排序；
  - `canSubmit`、阻断原因、警告集合；
  - 提交前的最终 prompt 构建。
- 安卓和鸿蒙只负责渲染同一份决策结果；不要求两端使用同一个 UI 框架。
- 保留现有行为：图关闭时置顶状态保留但不提前；唯一文件名后缀只在实际提交时追加。

## 验收

- 同一份工作流、同一组图开关和 overrides，在共享层得到相同的 `canSubmit`、阻断原因、警告和 prompt；
- 安卓/鸿蒙不再各自实现排序或可提交判断；
- 共享层测试覆盖上述决策；
- 两端真机回归：提交、阻断提示、置顶、图启停、产物文件名。
