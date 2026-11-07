# AGENTS.md

本文件是 ComfyPilot 的长期协作约定。新会话先读这里，再读 `README.md` 与 `docs/agents/`。
更细的会话记录在 `.agent-memory/`（本机、未入库），但**以本文件为准**。

## 项目概览

ComfyUI 的移动客户端：

- `app/`：Android（Kotlin + Jetpack Compose）；
- `harmony/`：HarmonyOS NEXT（ArkTS/ArkUI）；
- `shared/`：Kotlin Multiplatform 共享核心（工作流解析、Prompt 构建、会话状态机、AI 循环）；
- `docs/`：发布、合规、设计文档；
- `tools/`：辅助脚本。

两端功能尽量保持对等；共享逻辑优先放 `shared/src/commonMain`，UI 各自实现。

## 环境与构建

Windows 本机路径：

```powershell
$env:JAVA_HOME = 'D:\Tools\Java\jdk-21.0.6'
.\gradlew.bat :shared:jvmTest :app:assembleRelease
```

鸿蒙（参数用数组传，避免 `-p` 被 PowerShell 吞掉）：

```powershell
$args = @('assembleApp','--mode','project','-p','product=release','-p','buildMode=release','--no-daemon')
& 'D:\ComfyPilot\harmony\build.ps1' @args
```

日常 HAP：

```powershell
& 'D:\ComfyPilot\harmony\build.ps1'
```

安装/截图（`hdc`）：

```powershell
$hdc = 'D:\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe'
& $hdc install -r "D:\ComfyPilot\harmony\entry\build\default\outputs\default\entry-default-signed.hap"
& $hdc shell "snapshot_display -f /data/local/tmp/s.jpeg"
& $hdc file recv /data/local/tmp/s.jpeg "D:\ComfyPilot\.scratch\s.jpeg"
```

Android 包：`app/build/outputs/apk/release/app-release.apk`。
鸿蒙未签名包：`harmony/entry/build/default/outputs/default/entry-default-unsigned.hap`。

## 版本与发布

- 版本号两端必须一致：`app/build.gradle.kts` 与 `harmony/AppScope/app.json5`；
- 发版顺序：
  1. 两端构建通过、`shared` 测试通过；
  2. 升版本号并提交；
  3. 打 annotated tag 并推送；
  4. 更新 E 盘发布目录（APK、未签名 HAP、README、SHA256SUMS）；
  5. 发 GitHub Release（label 选 **Latest**）；
  6. Release 确认可下载后，再改 `update.json`；
  7. 最后同步 Gitee（仓库与 Release）。
- **不要提前改 `update.json`**：否则用户收到提示但下载页还没有对应包。
- E 盘发布目录：`E:\迅雷云盘\ComfyPilot-release-0.2.0\`。
- Gitee 未同步时，国内用户通常收不到更新提示；客户端优先读 Gitee。

## Git 约定

- 提交信息用中文、Conventional Commits 前缀：`feat(scope): …`、`fix(scope): …`、`chore(release): …`；
- 未经用户明确要求**不要推送**，只做本地提交；
- GitHub SSH 可能间歇性失败，重试即可（`ssh -o ConnectTimeout=20`）；
- 标签被移动后，拉取方需要 `git fetch --tags --force`。

## 安全与文件位置

- **不往 C 盘放项目文件**（用户长期要求）：测试包、文档、归档放 D 盘或 E 盘；
- 桌面不再放项目产物；临时文件统一放 `D:\ComfyPilot\.scratch\`（会被清理）；
- 不提交签名材料：`keystore.properties`、`*.p12`、`*.p7b`、`*.cer`、`*.csr`、`agconnect-services.json`；
- 鸿蒙发布配置在 `harmony/build-profile.json5`（已 gitignore），改前先备份；
- 不在任何 tracked 文件里写密码或 Key。

## 验证要求

- 改 `shared` 必须跑 `:shared:jvmTest`；
- 改 Android 需 `:app:assembleRelease` 成功；
- 改鸿蒙需 `build.ps1` 成功；
- UI 改动尽量装到真机并截图确认；
- 修复提交类问题时，优先用真实工作流或现有测试复现，不靠猜。

## 已知坑

- 鸿蒙 `build-profile.json5` 改 `applyToProducts` 时注意 CRLF/字符串匹配；
- `hdc` 设备会掉线，安装前先 `hdc list targets`；
- 工作流里控件值可能是字符串（如 `bit_depth: "8"`），提交前需按字段类型转换；
- LoRA/模型类候选列表可能滞后于服务器实际文件；不匹配时保留原值，不静默替换；
- Power Lora Loader 是前端虚拟节点，CLIP 未接线时展开成 `LoraLoaderModelOnly`；
- 子图展开后的合成节点 id 可能变化，依赖节点 id 的本机记忆可能对不上。

## Agent 工作方式

### Issue tracker

Issues live as markdown files under `.scratch/<feature>/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five roles, label string equal to role name. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (`GLOSSARY.md` + `docs/adr/` at the repo root). See `docs/agents/domain.md`.

### 会话记忆

- 长期约定写在**本文件**（随仓库走）；
- 当前状态、最近改动与交接写在 `.agent-memory/MEMORY.md`（本机、不入库，换 AI 时先读它）；
- 早期过程日志归档到 `E:\ComfyPilot-notes\agent-memory-archive\`，不要继续堆在项目里；
- 每次会话结束更新 `MEMORY.md` 的“当前状态 / 最近完成 / 已知问题”三节，并写清日期与提交号；
- 修复类改动完成后，优先补测试或说明验证方式。
