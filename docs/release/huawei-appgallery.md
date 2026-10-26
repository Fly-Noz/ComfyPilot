# 华为 AppGallery 上架准备（ComfyPilot）

## 当前标识

| 项 | 值 | 说明 |
|---|---|---|
| 鸿蒙 bundleName | `com.fly.comfypilot` | AGC 注册后**不可修改** |
| Android applicationId | `com.fly.comfypilot.app` | 与鸿蒙 bundleName 区分（卓易通等安卓兼容层按包名识别）；不含商标词；已上架后不可修改 |
| 版本 | `0.2.0` / versionCode `2` | 每次上传必须递增 versionCode |

## 一、账号与资质

1. 华为开发者联盟账号，完成**实名认证**（个人或企业）。
2. 国内分发需要：
   - **工信部 App 备案**（2023 年起强制，需先有域名/主体备案）；
   - **软件著作权**（多数品类要求，个人开发者同样）；
   - 隐私政策公网 URL（可用 GitHub Pages 托管 `docs/release/privacy-policy.zh-CN.md`）。
3. AppGallery Connect 创建应用，包名填 `com.fly.comfypilot`。

## 二、发布签名（AGC 生成，不能用现在的调试证书）

1. AGC → 证书、APP ID 和 Profile → **证书管理**：
   - 新增证书 → 生成 `.p12`（密钥库）与 `.cer`（证书）；
   - 新增 Profile（类型 HarmonyOS，绑定上面的证书与包名）→ 下载 `.p7b`。
2. 在 `harmony/build-profile.json5` 里增加 release 签名配置（**不要提交密码**）：

```json5
"signingConfigs": [
  {
    "name": "release",
    "type": "HarmonyOS",
    "material": {
      "certpath": "signing/comfypilot.cer",
      "storePassword": "<你的密钥库口令>",
      "keyAlias": "<别名>",
      "keyPassword": "<密钥口令>",
      "profile": "signing/comfypilot.p7b",
      "signAlg": "SHA256withECDSA",
      "storeFile": "signing/comfypilot.p12"
    }
  }
]
```

3. 把 `signing/` 加入 `.gitignore`，证书与口令只留在本机/密码管理器。

## 三、构建与自检

```powershell
cd harmony
$env:DEVECO_SDK_HOME='D:\Huawei\DevEco Studio\sdk'
$env:NODE_HOME='D:\Huawei\DevEco Studio\tools\node'
& 'D:\Huawei\DevEco Studio\tools\hvigor\bin\hvigorw.bat' assembleHap `
  --mode module -p product=default -p buildMode=release --no-daemon
```

产物：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`

自检：

```powershell
& 'D:\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\lib\hap-sign-tool.jar'  # 用 java -jar 调 verify-app
```

或直接在 AGC 的「云测试」里安装验证。

## 四、提审前检查

- [ ] `AppScope/app.json5` 的 versionCode 已递增、versionName 与 Release 一致
- [ ] 包名 `com.fly.comfypilot` 与 AGC 上完全一致
- [ ] 使用 AGC 发布证书签名（不是 debug 证书）
- [ ] 隐私政策 URL 可访问，且 App 内「设置/关于」有入口
- [ ] 权限用途说明：网络、通知、后台运行、读取相册（上传图片）
- [ ] 首次启动有隐私提示/同意（审核常查）
- [ ] 图标、截图、应用介绍、分类、年龄分级
- [ ] 说明「需要用户自备 ComfyUI 服务器」——审核会问“没有服务器时能否使用”

## 五、审核常见问题

| 问题 | 处理 |
|---|---|
| 后台运行权限用途不明 | 描述里说明“保持与用户自建 ComfyUI 的长连接，任务运行期间保活” |
| 明文 HTTP | 说明只连用户局域网/自建服务器；必要时在隐私政策里写明 |
| 无账号但要求登录 | 本应用无账号体系，直接说明 |
| 空壳/无法体验 | 提供测试服务器地址与测试步骤给审核（或录屏） |
| 备案号缺失 | 先完成 App 备案再提审 |
