# ios/ —— iOS 客户端（施工中）

SwiftUI 宿主 + `shared` 的 Kotlin/Native framework。分工和鸿蒙完全一致：

```
SwiftUI 界面
  → BridgeHost（URLSession 做 HTTP/WS，250ms 定时器驱动）
    → ComfyCore.framework（shared 的状态机、工作流解析、Prompt 构建）
      → 你自己的 ComfyUI
```

Kotlin 不碰网络、不碰文件、不碰 UI。所有平台差异都压在 `BridgeHost` 一个文件里。

## 目录

| 文件 | 作用 |
|---|---|
| `project.yml` | XcodeGen 工程描述，`.xcodeproj` 不入库（由它现场生成） |
| `ComfyPilot/App.swift` | 入口 |
| `ComfyPilot/BridgeHost.swift` | 桥协议的宿主机实现：HTTP、WebSocket、网络变化 |
| `ComfyPilot/SessionStore.swift` | 状态帧解析 + 对外动作 |
| `ComfyPilot/Screens.swift` | 页面栈（连接 / 工作流 / 任务 / 设置）+ 任务卡 |
| `ComfyPilot/WorkflowScreen.swift` | 工作流列表、参数表单、全部节点面板 |
| `ComfyPilot/PreviewPane.swift` | 预览页：实时采样帧 / 最近产物 |
| `ComfyPilot/ServerStatsStrip.swift` | 主机状态条（内存、显存、CPU、温度） |
| `ComfyPilot/ArtifactViewer.swift` | 产物查看与保存到相册 |
| `ComfyPilot/Theme.swift` | 光谱设计语言的 iOS token，与 Android 取值一致 |

## 有 Mac 时

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
cd ios
brew install xcodegen
xcodegen generate
open ComfyPilot.xcodeproj
```

真机（需要 `iosArm64` 产物）：

```bash
./gradlew :shared:linkDebugFrameworkIosArm64
xcodebuild -project ios/ComfyPilot.xcodeproj -scheme ComfyPilot \
  -destination 'generic/platform=iOS' \
  KMP_FRAMEWORK_DIR=$(pwd)/shared/build/bin/iosArm64/debugFramework \
  CODE_SIGNING_ALLOWED=NO build
```

## 未签名 IPA

```bash
bash ios/package.sh
```

产物：

```
ios/build/ComfyPilot-unsigned.ipa
```

脚本会编译 `iosArm64` release framework、归档未签名 App、组装 `Payload/`。
IPA 没有签名，**安装前需要自行签名**，手机直接点 IPA 不会有反应。

也可以在 GitHub Actions 手动运行 `iOS Unsigned IPA`，或推 `v*` tag；tag 构建会把 IPA 附到 Release。

## 没有 Mac 时（当前状态）

`.github/workflows/ios.yml` 是唯一能编译 iOS 的地方：

1. push 到 `main`（改动 `ios/`、`shared/`、`tools/mock-comfyui/` 时自动触发）；
2. macOS runner 编 framework → 生成 Xcode 工程 → 编 App → 起 mock ComfyUI → 模拟器里跑起来截图；
3. 截图推到 `ci-artifacts` 分支。

本机取图（**不用 GitHub API**，全程走 SSH）：

```bash
git fetch origin ci-artifacts
git checkout origin/ci-artifacts -- .   # 会把 png 放到仓库根目录，注意别覆盖东西
# 或者只看某一张：
git show origin/ci-artifacts:tasks.png > .scratch/tasks.png
```

## 启动参数

模拟器没法点屏幕，靠参数把界面推到目标状态：

| 参数 | 说明 |
|---|---|
| `-uiScreen connect\|workflow\|tasks\|settings` | 首屏 |
| `-uiOpenWorkflow demo_ui.json` | 连上后自动打开这个工作流 |
| `-uiTab preview` | 详情页停在「预览」分段 |
| `-uiAutoSubmit` | 参数页就绪后自动提交一次（验证提交链路用） |
| `-uiDiagnostics` | 启动即弹出诊断页 |
| `-uiGraph` | 工作流加载完自动弹出节点图 |
| `-uiUploadImage` | 自动把内置夹具图传到第一个 LoadImage 字段 |
| `-uiBaseUrl http://127.0.0.1:8188` | 服务端地址 |
| `-uiTheme dark\|light` | 强制配色，不传跟随系统 |
| `-uiFixture` | 用内置样例数据渲染，不连服务端，**只用于调排版** |

```bash
xcrun simctl launch booted com.fly.comfypilot -uiScreen tasks -uiBaseUrl http://127.0.0.1:8188
xcrun simctl io booted screenshot .scratch/tasks.png
```

## 已知限制

- **后台保活做不到**：iOS 没有 Android 前台服务的对应物，`BGTaskScheduler` 是机会式调度，
  App 进后台后 WebSocket 会被挂起。Android 那句「切后台收进度通知」在 iOS 上不成立。
- ATS 开了 `NSAllowsArbitraryLoads`（ComfyUI 默认明文 HTTP，且地址由用户自填）；上架会被问，
  自签名分发不受影响。
- 首次连局域网 IP 会弹「本地网络」权限，用户拒绝后连接直接失败，需要引导去设置里开。
- 分发自签名 IPA，有更新检查与提醒，但只能跳下载页，不能自动安装。

## 进度

- [x] 共享层 iOS target + framework
- [x] 桥协议宿主（HTTP / WS / 网络变化）
- [x] 连接页、任务页、设置页骨架
- [x] 工作流列表与参数表单（字段、连线只读、图分组）
- [x] 节点开关（单个 + 全部节点面板）、节点置顶、图分组切换
- [x] 采样预览（`ws.binary` → `IosHost.preview()` → UIImage）
- [x] 产物预览（图片 + 视频播放）与保存到相册
- [x] 主机状态条（Crystools 采样 + `/system_stats` 轮询）
- [x] 节点图浮层（只读，坐标与连线来自共享层 `graph`）
- [ ] VHS 多帧动画预览
- [x] 图片字段上传（LoadImage：相册选图 → 压成 JPEG → /upload/image → 回填文件名）
- [x] 参数草稿按「主机+路径」存本机：参数、置顶、图分组、节点备注
- [x] 产物分享（系统分享盘，含「存储到文件」）
- [x] 节点卡可折叠（默认展开置顶或含常用参数的节点）、待填写/不可达说明
- [x] 输入区按图分组分节：分区头（N 个节点 · M 个会提交）、子图折叠、子图嵌套、其他节点
- [x] 节点图「跳转」：回到参数页并展开、滚动到该节点
- [x] 诊断日志流：共享层 `DiagnosticTrace` → IosHost 环形缓冲 → 诊断页倒序日志 + 复制/清空
- [x] 全局提示条（提交、上传、恢复参数都有即时反馈）
- [x] 工作流列表：刷新、读取失败重试、条目数与文件大小
- [x] 弱网模式（少抢带宽）、诊断页、忽略本地校验强制提交
- [x] 外观切换（跟随系统 / 深 / 浅，持久化）、地址历史、参数一键恢复
- [x] 更新检查与提醒（每次启动 / 每个版本只提示一次）
- [ ] AI 助手：设置里已留禁用入口，功能未开发
- [ ] 网络测速：设置里已留禁用入口，功能未开发
- [x] 未签名 IPA 打包脚本与 Actions workflow
