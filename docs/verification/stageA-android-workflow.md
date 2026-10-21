# 阶段 A · S3/S4 Android 工作流与图片上传验证（mock）

日期：2026-10-21

## 范围

这一轮只验证 **Android 端**的「连接 → 工作流列表 → 详情/调参 → 提交 → 任务」
与相册图片上传，对端是 `tools/mock-comfyui`，**不是真实 ComfyUI**。真实实例
（`100.x.x.x:8188`）的提交仍受手机 Tailscale 可达性阻塞，未在本文档中声称。

## 结论

| 层级 | 结果 |
|---|---|
| 构建级 | `:app:assembleDebug` 成功 |
| JVM 测试级 | `:shared:jvmTest` **163 通过 / 0 跳过 / 0 失败**（新增 3 个覆盖值与 5 个上传用例） |
| Android 真机级（mock） | 通过：列表、详情、调参、提交、任务完成、相册上传 |

## 设备与环境

- 设备：iQOO 7（`V2049A`），Android 14 / API 34，序列号 `1575924193008T5`
- 应用：`:app` debug，`adb install -r`
- 服务端：`tools/mock-comfyui`，`normal` 场景；`adb reverse tcp:18188 tcp:8188`
- 启动参数：`--es baseUrl http://127.0.0.1:18188 --ez autoConnect true`

## 逐项观察

1. **连接后自动进入列表**：界面显示 `已连接 · WIFI · 第 1 代`，列出
   `demo_api`、`demo_ui` 与 `demo_upload`；`GET /mock/state` 的 `hits.userdata = 1`。
2. **详情页**：打开 `demo_ui` 后显示 `格式 UI · 6 个节点类 · 7 个会提交`，
   「常用输入」11 项；「全部节点」7 个，`clip` 等连线输入标为“由连线提供”。
3. **改参并提交**：把 KSampler 的 `seed` 从 `42` 改为 `777` 后提交。
   `GET /mock/state` 的 `lastPrompt["3"].inputs.seed` 为 `777`，
   提交图为 7 个节点、`client_id` 正常，前端专用的 `randomize` 没有进入图。
4. **任务闭环**：任务页从 `执行中 75%` 变为 `已完成 100%`，显示
   `输出：ComfyPilot_mock_<id>.png`；`hits.prompt = 1`、`hits.history = 1`。
5. **相册上传**：打开 `demo_upload`，点「从相册选择」，在系统相册里选一张图。
   控件值从 `example.png` 变为上传返回的文件名（本轮为 `1000198369.png`）；
   `lastUpload.bytes = 380870`，说明文件真的发到了 `/upload/image`。
   提交后 `lastPrompt["1"].inputs.image` 等于该文件名，任务正常完成。

## 未验证

- 真实 ComfyUI 提交（手机 Tailscale 可达性未确认：`ping` 与 TCP 8188 都不通；
  `/object_info` 6.4 MB 的真实实例只在 JVM 诊断里用过）。
- 取消按钮在真机上的行为（本轮没有点）。
- 产物预览（S5）、鸿蒙对齐（S6）。
- 进程被杀/重启后的任务恢复：Android 侧仍是 `InMemoryTaskStore`，阶段一设计如此。

## 证据

截图在本地 `artifacts/`（已 gitignore，不入库）：

- `android-catalog.png`：工作流列表
- `android-detail.png`：常用输入与提交栏
- `android-nodes.png`：全部节点与连线字段
- `android-picker.png`：系统相册选择器
- `android-task.png`：任务完成

复现命令：

```powershell
cd tools\mock-comfyui
node server.mjs
adb reverse tcp:18188 tcp:8188
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.comfypilot.app/.MainActivity `
  --es baseUrl "http://127.0.0.1:18188" --ez autoConnect true
adb shell uiautomator dump /sdcard/ui.xml
Invoke-RestMethod http://127.0.0.1:8188/mock/state
```
