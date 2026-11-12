# 从源码构建

面向开发者的完整构建、签名与测试说明。只想安装客户端的用户请直接看 [Releases](https://github.com/Fly-Noz/ComfyPilot/releases)。

## 环境

- JDK 21
- Android SDK（compileSdk 37）
- DevEco Studio 5.0（HarmonyOS SDK 12）与 hvigor
- iOS 另需 macOS + Xcode + XcodeGen

## Android

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

正式签名构建需要仓库根目录的 `keystore.properties`（参考 `keystore.properties.example`）。文件不存在时 release 构建保持未签名：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## HarmonyOS

```powershell
# 编译共享层 .so、复制到头文件目录，再打包 HAP
.\harmony\build.ps1
```

产物：

```
harmony/entry/build/default/outputs/default/entry-default-unsigned.hap
```

配置签名后为 `entry-default-signed.hap`。

首次在 DevEco Studio 打开 `harmony/` 时需要配置签名（自动签名即可）；`harmony/build-profile.json5` 已被 gitignore，模板见 `harmony/build-profile.example.json5`。

发布构建：

```powershell
$args = @('assembleApp','--mode','project','-p','product=release','-p','buildMode=release','--no-daemon')
& 'D:\ComfyPilot\harmony\build.ps1' @args
```

## iOS

需要 macOS + Xcode 与 XcodeGen：

```bash
bash ios/package.sh
# 产物：ios/build/ComfyPilot-unsigned.ipa（未签名）
```

也可以在 GitHub Actions 里运行 **iOS Unsigned IPA**，或推送 `v*-ios*` 测试 tag 生成 pre-release 附件。签名与真机安装说明见 [ios/README.md](../ios/README.md)。

## 测试

```bash
./gradlew :shared:jvmTest
```

改 `shared` 必须跑共享测试；改 Android / 鸿蒙 / iOS 后还应完成对应平台的 release 构建。

---

# Building from source

Full build, signing and test instructions for contributors. End users should install from [Releases](https://github.com/Fly-Noz/ComfyPilot/releases).

## Requirements

- JDK 21
- Android SDK (compileSdk 37)
- DevEco Studio 5.0 (HarmonyOS SDK 12) with hvigor
- iOS additionally needs macOS + Xcode + XcodeGen

## Android

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Release builds need `keystore.properties` in the repository root (see `keystore.properties.example`). Without that file, the release build stays unsigned:

```bash
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## HarmonyOS

```powershell
# build the shared .so, copy headers, then package the HAP
.\harmony\build.ps1
```

Output:

```
harmony/entry/build/default/outputs/default/entry-default-unsigned.hap
```

With signing configured it becomes `entry-default-signed.hap`.

The first time you open `harmony/` in DevEco Studio you need to configure signing (automatic signing is fine). `harmony/build-profile.json5` is gitignored; a template lives in `harmony/build-profile.example.json5`.

Release build:

```powershell
$args = @('assembleApp','--mode','project','-p','product=release','-p','buildMode=release','--no-daemon')
& 'D:\ComfyPilot\harmony\build.ps1' @args
```

## iOS

Requires macOS with Xcode and XcodeGen:

```bash
bash ios/package.sh
# output: ios/build/ComfyPilot-unsigned.ipa (unsigned)
```

You can also run **iOS Unsigned IPA** from GitHub Actions, or push a `v*-ios*` test tag to produce a pre-release asset. See [ios/README.md](../ios/README.md) for signing and device installation.

## Tests

```bash
./gradlew :shared:jvmTest
```

Changes to `shared` must pass the shared tests; Android, HarmonyOS and iOS changes should also complete the corresponding release build.
