# 阶段 A · 多节点图、输入排序与错误展示验证

日期：2026-10-21

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **179 通过 / 0 跳过 / 0 失败** |
| Android 真机级 | 多节点图 UI、输入排序、mock 错误展示通过；真实 ComfyUI 只完成到"打开工作流"，主机随后关机 |

真实主机 `100.x.x.x` 在联调中途关机（Tailscale 节点仍显示 active，但
`tailscale ping`/TCP 8188 都不通），因此**没有提交真实任务**，本文不声称生成成功。

## 1. 多节点图

用真实 Qwen-image21 工作流（73 节点、11 个 group）做验证：

- `WorkflowGraphs` 识别出 **2 个独立图**：
  - `Qwen-image21-图像编辑`：16 个分组成员，单独提交 17 个节点；
  - `Qwen-image21-生图`：11 个分组成员，单独提交 11 个节点。
- 默认按保存状态只启用「图像编辑」；打开文件后详情页顶部出现两个可切换的图标签。
- 生图分支的节点保存为 `mode=4`；启用时只把该图分组内的节点临时当作正常，
  分组外的可选 LoadImage（image-2…image-8）和 4 步 Lora 不会被一起打开。
- 合成测试 `WorkflowGraphsTest` 覆盖：两个分组、启用被旁路图、全部禁用时拒绝提交。

## 2. 输入排序

大部分节点的控件都有默认值；输入区现在把**缺值/需要连线的节点**排在前面，
其余节点保持原 `order`。节点标题右侧显示“待填写”；判定规则：

- 控件字段没有值（`value == null`）；
- 或必填的非控件输入没有连线。

`WorkflowFieldOrderTest` 覆盖上述三种情况（缺值、连线、可选连线）。

## 3. ComfyUI 报错

- 提交校验：`/prompt` 的 `error` + `node_errors` 现在会摊平成
  `#节点 类名：message / details`，写进失败任务并提示。
- 执行错误：WebSocket `execution_error` 立即置失败，保留 `exception_message`。
- 历史错误：`/history` 的 `status_str=error` 走对账失败。
- 详情页顶部新增“ComfyUI 报错”卡片（可关闭），显示最近一次失败任务的原始错误；
  预览页和任务页保留完整文本。

真机 mock `error` 场景验证：任务失败后显示
`ComfyUI 报错 / CUDA out of memory (mock)`，预览页同时显示
`失败 · 0% / 任务失败：CUDA out of memory (mock)`。

## 4. 真实 `/userdata` 路径修正

首次用真实服务端打开工作流返回 404。实测真实路由要求：

```
GET /userdata/workflows%2F<相对路径>.json
```

即 `workflows/<path>` 整体 `encodeURIComponent`，斜杠也编码；之前按段编码
（保留 `/`）是错的。修正后真实服务端返回 200（Qwen 文件 84227 字节），
App 能打开并解析该工作流。mock 与测试同步更新。

## 未验证

- 真实 ComfyUI 提交与生成（主机中途关机）。
- 真实执行错误/节点校验错误的端到端展示（只用 mock 与单测覆盖）。
- 鸿蒙对齐（S6）。

## 证据

截图在本地 `artifacts/`（已 gitignore，不入库）：

- `real-qwen-detail.png`：真实 Qwen 的两个图标签、输入列表（主机稍后关机）
- `android-error-banner.png`：mock 执行错误在详情页/预览页的展示

复现代理：

```powershell
node tools\comfy-proxy.mjs 18188 100.x.x.x 8188
adb reverse tcp:18188 tcp:18188
```
