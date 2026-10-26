# ComfyUI 启动脚本（Windows 便携版）

这两个脚本是给**服务端**（跑 ComfyUI 的那台机器）用的，不是 App 的一部分。

## 放置位置

必须放在 ComfyUI 便携版**根目录**，和下面的目录同级：

```
ComfyUI_windows_portable\
├─ ComfyUI\
├─ python_embeded\
├─ run_local.bat     ← 放这里
└─ run_remote.bat    ← 放这里
```

## 用法

| 脚本 | 监听 | 适用场景 |
|---|---|---|
| `run_local.bat` | 仅 `127.0.0.1:8188` | 本机浏览器 / 本机转发 |
| `run_remote.bat` | `0.0.0.0:8188`（`--listen`） | 手机、局域网其它设备、内网穿透 |

启动后脚本会打印本机 IPv4（带网卡名），手机 App 填 `http://<那个IP>:8188` 即可。

同时列出多个地址时，选和手机同一网络的那个：
- `WLAN` / `以太网`：同一 WiFi/热点时用；
- `Tailscale`（100.x）：手机也装了 Tailscale 才能用。

## 安全提醒

`--listen` 会把 ComfyUI 暴露给能连到本机 8188 的所有设备，而 ComfyUI **默认没有登录认证**，任何能访问的人都能提交任意工作流。

- 只在可信局域网使用；
- 需要公网访问时，用 VPN 或内网穿透（Tailscale、frp、Cloudflare Tunnel 等），并在外层加访问控制；
- 不要在公网直接开 8188。

## 与 App 的动图采样预览

App 在提交时会给当前 prompt 打开 `VHS_latentpreview` 开关（`extra_pnginfo.workflow.extra`），**前提是服务端装了 VideoHelperSuite**：

```
ComfyUI\custom_nodes\ComfyUI-VideoHelperSuite
```

没装也能用，只是采样区显示单帧，不会循环动画。

## 自定义

- 端口：在 `main.py` 后加 `--port 8189`；
- 预览方式：`--preview-method auto`（默认）/ `latent2rgb` / `taesd` / `none`；
- 显卡参数等按需追加。