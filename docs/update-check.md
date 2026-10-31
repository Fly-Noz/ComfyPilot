# 更新检查

两端（Android / 鸿蒙）启动时静默查一次，关于页也有手动入口。发现新版弹同一个对话框，
给 **GitHub** 和 **Gitee** 两个下载入口，用户自己选。

- Android 判定：`shared/.../core/update/UpdateManifest.kt`（有单测）
- 鸿蒙判定：`harmony/entry/src/main/ets/update/UpdateChecker.ets`（同一套口径，ArkTS 侧重写）
- 清单：仓库根目录 `update.json`

## 为什么读 Gitee

GitHub 在国内时通时不通。检查更新如果走 GitHub，等于让一部分用户永远看不到新版本。

所以：**主源是 Gitee raw，GitHub raw 只做第二条源**（Gitee 取不到时才试）。
Gitee 的 raw 对公开仓库、10MB 以内的文件免登录，`update.json` 只有 1KB，正好符合。

## update.json 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `versionCode` | 是 | 整数。**唯一用于比较的字段**，必须与 `app/build.gradle.kts` 里的 versionCode 同步 |
| `versionName` | 是 | 展示用，如 `0.2.2` |
| `notes` | 否 | 更新说明，弹窗里限高滚动 |
| `publishedAt` | 否 | 日期，排查「Gitee 同步到没到」时有用 |
| `android` / `harmony` | 否 | 平台专属下载页，各含 `github` / `gitee` |
| `github` / `gitee` | 否 | 顶层兜底：平台块缺失或两个链接都为空时用它 |

只比 `versionCode`，不比版本名字符串——`"0.2.10"` 按字符串比会小于 `"0.2.9"`，这个坑踩过一次就够了。

## 发版清单

按顺序做，缺一步就会出现「发了版但用户收不到」：

1. 改 `app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`
2. 鸿蒙侧同步 `harmony/AppScope/app.json5` 的同名字段
3. 构建 release APK / HAP
4. 上传到 GitHub Releases（APK）与 Gitee 发行版（APK；**镜像不会同步发行版附件，必须手动传**）
5. 改仓库根目录 `update.json`：`versionCode`、`versionName`、`notes`、日期
6. 提交并推送到 GitHub，等 Gitee 镜像同步后**手动确认**：
   浏览器打开 `https://gitee.com/Fly-Noz/ComfyPilot/raw/main/update.json`，看 `versionCode` 是不是新的。
   如果还是旧的，去 Gitee 仓库页点一次「更新镜像」。

第 6 步不能省。镜像同步是静默的：GitHub 令牌过期、webhook 失效、Gitee 改策略，
都不会通知你，App 会一直读到旧清单。

## Gitee 镜像的配置

`Gitee 仓库 → 管理 → 仓库镜像管理 → 添加镜像`：

- 镜像方向 **Pull**（GitHub → Gitee）
- GitHub 私人令牌需勾 `repo` 与 `admin:repo_hook`（少了后者加不上自动同步）
- 勾选「自动从 GitHub 同步仓库」

发版后仍要按上面第 6 步肉眼确认。免费版的同步不是实时的。

## 鸿蒙的特殊之处

系统没有「点 HAP 就安装」的入口，也没有「允许未知来源」开关。下载回去也装不上，
只能：电脑上 `hdc install`（签名用的 Profile 里要登记设备 UDID），或走 AGC 邀请测试。

所以鸿蒙的弹窗多一块警示：**「鸿蒙版需自行签名后安装」**，写在按钮上方。
这不是文案洁癖——不写的话用户点完跳浏览器下载一个 HAP，回来发现点不动，会以为包坏了。

Android 没有这块提示，弹窗只有版本号、说明和三个按钮。

## 行为约定

- 每次冷启动检查一次：**有新版就弹，没有新版（或网络失败）完全安静**，不 toast、不打扰
- 「稍后」只关掉这一次，**不记录**：下次打开还会提。这是有意的——宁可多提醒一次，
  也不要让用户因为点过一次「稍后」而长期错过版本
- 只检查一次，不从后台切回来时重查：否则点完下载跳到浏览器、切回来又弹一次
- 关于页手动检查：无论结果都给反馈（发现新版本 / 已是最新 / 检查失败）
- 清单解析失败一律当「检查失败」，不抛异常——更新检查不能把 App 弄崩

## 实现注意（踩过的坑）

**ArkUI 的 `@Builder` 参数不是响应式的。** 关于页那一行本来是复用 `AboutRow(label, value, action)`，
检查完把 `value` 换成结果，Text 不会重绘——`@Builder` 只在父级重建时按值取一次参数。
同理，状态读取不能包在普通方法里（`updateRowValue()`），框架追踪不到依赖。
最终这一行改成内联的 `Row` + `Text`，直接绑定 `this.updateNote`，才会实时更新。

Android 那边没这个问题（Compose 按状态重组），但两边的「跳过版本」语义要保持一致。

## 排查

| 现象 | 先看这里 |
|---|---|
| 一直「已是最新」 | Gitee raw 是不是旧内容（镜像没同步）；`versionCode` 是不是忘了加 |
| 手动检查「检查失败」 | 手机能否访问 `gitee.com`；清单里 `versionCode` / `versionName` 是否缺失 |
| 弹窗出现但点了没反应 | 两个链接都为空，或设备没有浏览器 |
| 同一版本反复提醒 | 预期行为：不记忆「稍后」，每次冷启动都会提；手动检查也会提 |
