# 阶段 A · 详情页改版验证（统计 / 节点分组 / 实时预览）

日期：2026-10-21

## 范围

只验证 **Android 详情页**的新排版，对端是 `tools/mock-comfyui`，**不是真实 ComfyUI**。
真实实例提交仍受手机 Tailscale 可达性阻塞；本文档不声称已完成。

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **172 通过 / 0 跳过 / 0 失败**（新增 6 个预览/统计解析用例、3 个预览状态机用例） |
| Android 真机级（mock） | 通过：统计条、输入分组、全部节点菜单、提交后实时预览、完成结果 |

## 设备与环境

- 设备：iQOO 7（`V2049A`），Android 14 / API 34
- 应用：`:app` debug，`adb install -r`
- 服务端：`tools/mock-comfyui`，`normal` 场景；`adb reverse tcp:18188 tcp:8188`

## 逐项观察

1. **统计条**：显示 `RAM 可用 12.0 GB / 32.0 GB`、`VRAM 可用 8.0 GB / 24.0 GB`、
   `队列 运行 1 / 等待 0`。任务执行时队列从 0 变 1，完成后回到 0；数据来自
   `/system_stats` + `/queue`，每 2s 轮询。
2. **输入/预览切换**：统计条下方是固定两个按钮；默认「输入」，提交成功后自动切到
   「预览」。顶栏与原先一致（返回、工作流名、任务）。
3. **输入按节点分组**：`#3 KSampler`、`#6/#7 CLIPTextEncode` 等卡片可折叠；
   常用节点默认展开，`clip`/`model` 这类连线输入显示“由连线提供”。
4. **全部节点菜单**：底栏「全部节点」向上弹出列表；点 `#3 KSampler` 后菜单关闭、
   切回「输入」并滚动到该节点展开。
5. **实时采样预览**：提交后停留在详情页的「预览」，执行中显示 mock 发来的红色
   1x1 二进制采样帧；进度条与百分比同步。
6. **完成结果**：任务完成后预览自动切到 `/view` 的最终产物，显示
   `已完成 · 100%` 和「在任务页查看全部」。

## 数据限制

- 官方 `/system_stats` 只有内存/显存总量与可用量，**没有 CPU/GPU 利用率**；
  界面只显示 RAM/VRAM/队列，没有伪造 CPU。
- 预览帧是 ComfyUI 二进制 WS 帧（type=1/4），只存内存、不落库；完成或切换租约后清空。

## 未验证

- 真实 ComfyUI（手机 Tailscale 仍不通）。
- 页面不可见/切后台时暂停轮询：当前只在离开详情页时随 composition 停止；应用在后台
  仍会继续轮询，属于后续优化。
- 124 节点真实工作流下的滚动/重组性能；本轮只在 `demo_ui`（7 节点）上验证。
- 鸿蒙对齐（S6）。

## 证据

截图在本地 `artifacts/`（已 gitignore，不入库）：

- `android-detail-new.png`：统计条 + 输入分组
- `android-live-preview2.png`：执行中的采样预览
- `android-preview-done.png`：完成结果
- `android-node-sheet.png` / `android-node-jump.png`：全部节点菜单与跳转
