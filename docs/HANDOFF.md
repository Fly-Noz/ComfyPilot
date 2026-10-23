# ComfyPilot 当前项目状态 / 交接说明

日期：2026-10-23  
分支：`master`  
代码基线：`c1601c0 鸿蒙图片和视频保存到相册`  
注意：S7 的改动目前都在工作区，**尚未提交**。

## 一句话状态

Android / HarmonyOS 双端已经完成 S7 的导航对齐、任务排序与置顶、节点图/多图输入、鸿蒙实时采样预览和详情页产物入口。鸿蒙侧真机验证通过；Android 侧代码、单测和构建通过，但最新 APK 尚未装到设备做最后一轮导航回归。下一步进入 UI 美化。

## 已确认可用

| 能力 | 状态 |
|---|---|
| 共享工作流决策层 | 实现完成，单测通过；issue 01 仍待两端真机回归 |
| 任务列表最新在上 | Android 真机确认；鸿蒙共享桥按 `createdAtMs` 倒序 |
| 工作流置顶 | Android 真机确认；鸿蒙已接入，HAP 已安装 |
| 节点图启停 / 置顶节点排序 | 共享层测试通过；两端页面已接入 |
| 辅助节点图 / 多图输入 | 已实现，见 `docs/verification/stageS7-auxiliary-image-groups.md` |
| 鸿蒙实时采样预览 | **真机通过**：进度条、百分比、`#节点 x/y`、采样图正常 |
| 鸿蒙详情页预览 / 提交后停留 | **真机通过**：提交后不跳任务页，完成后可从详情打开产物 |
| 鸿蒙图片上传 | 已实现；取消/断网边界仍待真机回归 |
| Android 从详情打开任务并保留详情 | 代码完成；最新 APK 安装被系统权限拦截，issue 06 待真机回归 |
| 鸿蒙视频/音频产物播放 | 未开始，issue 04 `needs-triage` |
| 鸿蒙日志/诊断入口降级 | 未开始，issue 08 `needs-triage` |
| 后台长任务保活 | 未完成；主界面底部已明确提示 |

## 测试与构建结果

- 共享层：`:shared:jvmTest` **213 / 0 / 0**（4 个既有 e2e 用例在 JVM 跳过）
- Android：`:app:testDebugUnitTest` **13 / 0 / 0**
- Android 构建：`:app:assembleDebug` 成功
- 鸿蒙构建：`harmony/build.ps1` 成功
- HAP：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`
  - 18:03:18 构建，8,323,039 字节
  - 已安装到鸿蒙设备 `33Z0224613077036`
- Android APK：`app/build/outputs/apk/debug/app-debug.apk`
  - 17:18:34 构建，13,594,569 字节
  - **最新 APK 未安装**；设备上仍是之前的导航版本

## 架构速记

- `shared/`：Kotlin Multiplatform 核心。会话状态机、ComfyUI HTTP/WebSocket、
  工作流决策、任务状态、预览解码都在这里。
- `app/`：Android Compose 壳；`ComfyPilotApp` 负责页面和任务覆盖层，
  `WorkflowDetailScreen` 负责详情。
- `harmony/`：ArkTS 页面 + C++ NAPI。
  - `ComfyBridge.ets` 是宿主循环，负责 HTTP/WebSocket/图片解码；
  - `napi_init.cpp` 导出 ABI；
  - `Index.ets` 是主页面。
- 协议：宿主和 Kotlin 之间只走 JSON 消息；ABI 当前是 **4**。
- 鸿蒙预览链路：
  1. ComfyUI 发二进制 WebSocket 帧；
  2. ArkTS 转 base64，回投 `{"t":"ws.binary","id":...,"base64":"..."}`；
  3. 共享层 `HostMessage.SocketBinary` → `WebSocketListener.onBinary()`；
  4. 状态帧只带预览元数据；图片字节通过 `cp_session_preview` 按需取。
- Android 连接：`adb reverse tcp:18188 tcp:18188` + `tools/comfy-proxy.mjs`。
- 鸿蒙连接：直连 Tailscale `http://100.x.x.x:8188`。

## 本次收尾修的关键问题

### 1. 鸿蒙采样预览收不到

根因有两层：

1. `ComfyBridge` 最初直接丢弃二进制 WebSocket 帧；
2. 修复回投后，`LivePreviewCard(task)` 的 `@Builder` 对象参数不会随任务快照
   刷新，导致共享层已经 `Running:40`、预览元数据也有，界面仍停在
   `0% Submitting`。

最终做法：

- 二进制帧 base64 回投共享层；
- `LivePreviewCard` 改成**无参数 builder**，每次直接读取 `this.tasks`；
- 详情页和任务页共用同一张卡，按顶部页签选择
  `activeWorkflowTask()` / `previewTask()`。

### 2. 详情页找不到刚提交的任务

提交时 `workflowPath` 改用 `selectedPath`，避免 `view.path` 为空导致
详情预览按工作流过滤不到新任务。

## 当前 issue 状态

目录：`.scratch/s7-parity/issues/`

| Issue | 状态 |
|---|---|
| 01 共享工作流决策层 | `ready-for-human` |
| 02 鸿蒙 LoadImage 上传 | `resolved` |
| 03 鸿蒙实时采样预览 | `resolved` |
| 04 鸿蒙视频/音频播放 | `needs-triage` |
| 05 两端导航对齐 | `resolved` |
| 06 Android 保留详情 | `ready-for-human` |
| 07 鸿蒙详情页预览 | `resolved` |
| 08 鸿蒙诊断入口降级 | `needs-triage` |

## 已知坑 / 交接注意

- **HAP 安装不一定会杀掉旧进程。** 改完代码安装后要 `aa force-stop` 再启动，
  否则测试的仍是旧 ArkTS。
- **鸿蒙默认地址是 `127.0.0.1:18188`**，这是 Android 代理地址；鸿蒙测试要手填
  `http://100.x.x.x:8188`。可以考虑后续把最后一次地址持久化。
- **预览卡不要改回 `LivePreviewCard(task)` 的对象参数**，否则进度/图片可能
  卡在首次渲染值。
- ComfyUI 需要 `--preview-method auto` 才会发采样预览；没有预览帧的工作流
  只显示等待提示。
- 升级前的旧任务没有 `workflowPath`，不会出现在详情预览，但仍可在任务页看到。
- Android 安装可能被系统弹窗拦截：`INSTALL_FAILED_ABORTED: User rejected permissions`。
  需要解锁设备并允许安装。
- `hdc` 路径：`D:\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe`
- `adb` 路径：`D:\Android\Sdk\platform-tools\adb.exe`

## 建议的下一步

1. **Android 06 收尾**：安装最新 APK，回归“详情 → 任务 → 返回详情”状态保留、
   详情预览按工作流过滤、提交后停留详情。
2. **UI 美化（优先级 5）**：
   - 统一颜色、字体层级、间距；
   - 规范进度条、空状态、错误提示；
   - 统一两端导航高亮和操作按钮布局；
   - 不改变现有功能路径。
3. 之后处理 issue 04（音视频播放）、issue 08（诊断入口降级）和后台保活。
4. 把 S7 工作区改动整理成 commit / PR；当前所有改动仍未提交。

## 常用命令

```powershell
# 测试 + Android 构建
$env:Path='D:\Tools\Java\jdk-21.0.6\bin;C:\Windows\system32;C:\Windows'
$env:CLASSPATH=''
.\gradlew.bat :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --console=plain

# 鸿蒙构建
powershell -NoProfile -ExecutionPolicy Bypass -File .\harmony\build.ps1

# 鸿蒙安装（先确认设备）
& 'D:\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe' install -r `
  'D:\ComfyPilot\harmony\entry\build\default\outputs\default\entry-default-signed.hap'

# Android 安装
& 'D:\Android\Sdk\platform-tools\adb.exe' install -r `
  'D:\ComfyPilot\app\build\outputs\apk\debug\app-debug.apk'
```
