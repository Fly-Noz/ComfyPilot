# ComfyPilot 项目交接文档

更新：2026-10-28 · 最新提交 `42c6ef0`

## 1. 项目概览

ComfyUI 的移动客户端，Android（Jetpack Compose）+ HarmonyOS NEXT（ArkTS/ArkUI），共享业务核心用 Kotlin Multiplatform，鸿蒙通过 `.so` + napi 调用。

- Android 包名 `com.fly.comfypilot.app`，namespace `com.comfypilot.app`
- 鸿蒙 bundle `com.fly.comfypilot`
- 版本 0.2.0（versionCode 2），GPL-3.0
- 非官方项目，与 ComfyUI 官方无关

## 2. 仓库结构

```
app/                    Android 客户端（Compose）
shared/                 KMP 共享核心（commonMain / jvmTest / ohosArm64Main）
ohos/                   Kotlin/Native 产出 libcomfypilot_core.so
harmony/                鸿蒙客户端（ArkTS + napi cpp）
tools/comfyui/          服务端启动脚本 + IP 列表（GBK/CRLF）
tools/mock-comfyui/     本地 mock 服务端
docs/                   设计、发布、交接文档
```

## 3. 架构

```
UI（Compose / ArkUI）
  → 宿主桥（Android 直接调用 / 鸿蒙 napi）
    → shared：SessionHost、UiWorkflowParser、PromptBuilder、ComfyUI HTTP/WS
      → ComfyUI 服务端（/prompt、/history、/queue、/view、/object_info、WS）
```

- 网络、工作流解析、字段提取、提交构建都在 `shared`
- Android 用 OkHttp（shared 的 jvm 实现），鸿蒙用 Kotlin/Native 的 curl/平台实现
- 鸿蒙侧另有纯 ArkTS 的下载、相册保存、图片压缩逻辑

## 4. 已实现功能

- 连接：地址历史、自动重连、后台保活、Tailscale/USB 转发可用
- 工作流：列表、图分组、节点参数、旁路开关、节点图选择、连线改接
- 参数草稿：按「主机 + 工作流」存本机，250ms 防抖，退出恢复
- 采样预览：VHS 动画（循环、逐轮变清晰），断线保留最后一帧
- 任务：进度、取消、排队、完成通知、60 秒无进度提示
- 产物：图片/视频/GIF 预览与保存（鸿蒙保存转 8-bit RGB PNG）
- 监控：/system_stats + Crystools
- 主题：深色/浅色/跟随系统
- AI 助手：悬浮球、对话、工作区选择、查询/改参/开关/选图/切工作流、diff 确认/自动提交、ask_user、操作记录、停止、日志

## 5. 关键模块

| 模块 | 位置 |
|---|---|
| 会话状态机 | `shared/.../net/SessionHost.kt`、`SessionState.kt` |
| 工作流解析 | `shared/.../workflow/UiWorkflow.kt`、`WorkflowFields.kt`、`PromptBuilder.kt` |
| 图与字段 | `WorkflowGraphs.kt`、`WorkflowDecision.kt` |
| 鸿蒙 ABI | `shared/src/ohosArm64Main/.../SessionAbi.kt`、`harmony/entry/src/main/cpp/napi_init.cpp` |
| AI 共享核心 | `shared/.../ai/`（AiWorkflow/AiEdit/AiBridge） |
| AI 运行时 | `app/.../ai/AiRuntime.kt`、`harmony/.../ai/AiAssistant.ets` |
| 存储 | `PinnedNodeStore`、`NodeNoteStore`、`WorkflowDraftStore`、`ConnectionHistoryStore` |

## 6. 构建与运行

环境：JDK 21、Android SDK、DevEco Studio SDK（API 12）、Node（DevEco 自带）。

```powershell
# Android debug
$env:JAVA_HOME='D:\Tools\Java\jdk-21.0.6'
.\gradlew.bat :app:assembleDebug --console=plain

# Android release（签名在根目录 keystore.properties，缺失会跳过）
.\gradlew.bat :app:assembleRelease --console=plain

# 共享测试
.\gradlew.bat :shared:jvmTest --no-daemon

# 鸿蒙 .so
cd ohos
$env:DEVECO_SDK_HOME='D:\Huawei\DevEco Studio\sdk'
$env:OHOS_SDK_HOME='D:\Huawei\DevEco Studio\sdk\default\openharmony'
.\gradlew.bat :shared-ohos:linkDebugSharedOhosArm64

# 鸿蒙 HAP
cd harmony
$env:NODE_HOME='D:\Huawei\DevEco Studio\tools\node'
& 'D:\Huawei\DevEco Studio\tools\hvigor\bin\hvigorw.bat' assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
```

产物：

- APK：`app/build/outputs/apk/release/app-release.apk`
- HAP：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`
- `.so`：`ohos/shared-ohos/build/bin/ohosArm64/debugShared/libcomfypilot_core.so`（复制到 `harmony/entry/libs/arm64-v8a/`）

## 7. 测试设备与工具

- Android：iQOO 7 `1575924193008T5`，adb `D:\Android\Sdk\platform-tools\adb.exe`
- 鸿蒙：`33Z0224613077036`，hdc `D:\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe`
- hdc 失效时：`hdc kill; hdc start`
- Android 测试代理：`node tools/comfy-proxy.mjs 18188 <host> 8188` + `adb reverse tcp:18188 tcp:18188`
- 日志：`adb logcat -s ComfyPilotAI` / `hdc shell "hilog -x" | Select-String ComfyPilotAI`

## 8. 数据与安全

- 不收集数据；地址、草稿、AI Key 只存本机
- Android AI Key：Keystore AES-GCM；鸿蒙：Asset Store
- `keystore.properties`、`*.jks`、`harmony/build-profile.json5`、`harmony/entry/libs/` 均被忽略，不入库
- AI 启用后，工作流结构与参数会发送给用户自己配置的模型服务商

## 9. 已完成 vs 待办

已完成（近期）：

- 主机作用域存储、地址面板、通知抑制、对话框统一
- 鸿蒙 PNG 保存修复、上传压缩、任务通知修复
- 包名拆分（`com.fly.comfypilot.app`）
- 仓库整理、README/GitHub 展示、第三方许可声明、隐私政策
- AI 助手主体（见 `docs/ai-assistant-handoff.md`）

待办（AI）：

1. AI 日志进 App 诊断页
2. 宿主 snapshot/restore
3. 同键冲突提示
4. 状态过期检测
5. 撤销本次 AI 修改
6. 提交后验证循环（等待任务 → 失败自动重试 → 带回产物）
7. 对话/操作记录持久化
8. 鸿蒙悬浮球红点
9. 两端工具循环统一到共享层（减少漂移）

待办（非 AI）：

- 真机回归：长任务、断网重连、VHS 动画、鸿蒙相册保存
- 发布：GitHub Release 资产、华为 AppGallery 上架材料
- 仓库历史里有旧 GIF 大对象，可选 filter-repo 清理

## 10. 已知问题

- AI 循环两端各一份，鸿蒙工具结果仍是文本回填，Android 已用标准 `tool_call_id`
- 悬浮球位置不持久化；鸿蒙无待处理红点
- 未做提交后验证，AI 只保证“提交成功”
- Android 弹窗高度已修，但小屏仍需真机确认
- 鸿蒙 settings 的 AI/高级页为整屏覆盖，弹窗层级已修（确认框在最上层）
- 部分早先截图含真实 IP，未入库；README 图片已打码

## 11. 发布信息

- 签名文件：`D:\ComfyPilot_Beta\comfypilot-release.jks`（密码/别名不入库）
- 发布目录：`D:\ComfyPilot_Beta\release-0.2.0`、`E:\迅雷云盘\ComfyPilot-release-0.2.0`
- 上架文档：`docs/release/huawei-appgallery.md`
- 隐私政策模板：`docs/release/privacy-policy.zh-CN.md`
- GitHub 只能走 SSH（`ssh.github.com:443`），API/web 在本机被墙

## 12. 接手建议

1. 先跑 `:shared:jvmTest` 和两端构建，确认基线
2. 读 `docs/ai-assistant-handoff.md` 了解 AI 部分
3. 按待办顺序做，每项单独构建 + 装机
4. 改 AI 相关逻辑时，同时改 Android 与鸿蒙，避免继续漂移

## 13. 2026-10-28 晚更新

已修复（对应 `docs/bug-report-2026-10-28.md`）：

- BUG-01 Reroute / PrimitiveNode 穿透；Reroute 的空名输入不再被 `parseInput` 丢弃（否则下游字段既不算连线驱动、提交也只用旧控件值）
- BUG-02 三份图统一：`WorkflowGraphs.analyze` 接收会话开关后再判断图是否启用/哪些节点未启用；排序包含 `enabledNodeIds`；`disabledNodeIds` 剔除手动启用的节点
- BUG-03 分组归属改为包围盒相交
- BUG-05 前端专用白名单收到 `WorkflowCodec.UI_ONLY_NODE_TYPES`；`WorkflowNodeView.uiOnly` 让卡片文案不再误报
- BUG-06 节点开关与图选择按 host+path 持久化（两端）
- BUG-07 Android `hostScope` 断线窗口退回 `SessionHost.baseUrl`
- BUG-08 `napi_init.cpp` 三处 GBK 乱码
- 另修：自动增长/动态输入不再被当成必填（与 PromptBuilder 判定对齐）

AI 侧新增：历史会话、撤销/冲突跳过/状态过期、`task_status` 提交后验证、多模态图片输入（大图压缩后仍发给模型）、按生成模型给提示词规范、模型规范搜索工具、权限开关、对话与操作记录落盘、鸿蒙红点、AI 日志进诊断、应用后回读保障层。

剩余：

1. **工具循环统一到 shared（第二步）**：工具声明已收进 `AiToolSpec` 并经 `cp_ai_tools_json` 下发；循环状态机仍在两端，需要定义 `AiToolHost` 回调接口后再迁移。
2. **BUG-04 辅助图组语义**：待产品决策（整组开关 vs 只启用组内被旁路节点）。
3. Android `AiRuntime` 里旧的 `TOOLS` 构造已是死代码，可删。
