import net from 'node:net'

/**
 * 最小 TCP 转发：把本机端口转发到 ComfyUI。
 *
 * 用途：手机/鸿蒙设备通过 USB 的 adb reverse / hdc rport 访问本机端口，
 * 本机再通过 Tailscale 访问 100.x 的 ComfyUI，设备本身不需要装 Tailscale。
 *
 * 用法：
 *   node comfy-proxy.mjs
 *   node comfy-proxy.mjs 18188 100.x.x.x 8188            # 只听 127.0.0.1（USB 转发用）
 *   node comfy-proxy.mjs 18188 100.x.x.x 8188 0.0.0.0    # 同 Wi-Fi 直连用
 *
 * 第四个参数是监听地址：手机和电脑在同一个 Wi-Fi 时用它，App 里填
 * http://<电脑的局域网 IP>:18188。默认仍只听本机回环，别无意中把它暴露到局域网。
 */
const listenPort = Number(process.argv[2] || process.env.LISTEN_PORT || 18188)
const targetHost = process.argv[3] || process.env.TARGET_HOST || '100.x.x.x'
const targetPort = Number(process.argv[4] || process.env.TARGET_PORT || 8188)
const listenHost = process.argv[5] || process.env.LISTEN_HOST || '127.0.0.1'

const server = net.createServer((client) => {
  const upstream = net.connect(targetPort, targetHost)

  client.on('error', () => upstream.destroy())
  upstream.on('error', () => client.destroy())
  client.on('close', () => upstream.destroy())
  upstream.on('close', () => client.destroy())

  client.pipe(upstream)
  upstream.pipe(client)
})

server.listen(listenPort, listenHost, () => {
  console.log(`ComfyPilot proxy: http://${listenHost}:${listenPort} -> ${targetHost}:${targetPort}`)
})
