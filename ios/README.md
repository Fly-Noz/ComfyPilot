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
| `ComfyPilot/Screens.swift` | 连接 / 任务 / 设置三页 |
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
| `-uiScreen connect\|tasks\|settings` | 首屏 |
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
- 分发自签名 IPA，没有自更新；`update.json` 那套在 iOS 上不用。

## 进度

- [x] 共享层 iOS target + framework
- [x] 桥协议宿主（HTTP / WS / 网络变化）
- [x] 连接页、任务页、设置页骨架
- [ ] 工作流详情（参数、开关、节点图）
- [ ] 采样预览（二进制帧已经能收，还没解码显示）
- [ ] 产物预览与保存到相册
- [ ] AI 助手
- [ ] 弱网模式、诊断日志
- [ ] 未签名 IPA 打包脚本
