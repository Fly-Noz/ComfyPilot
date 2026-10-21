import net from 'node:net'

/**
 * 最小 TCP 转发：把本机端口转发到 ComfyUI。
 *
 * 用途：手机/鸿蒙设备通过 USB 的 adb reverse / hdc rport 访问本机端口，
 * 本机再通过 Tailscale 访问 100.x 的 ComfyUI，设备本身不需要装 Tailscale。
 *
 * 用法：
 *   node comfy-proxy.mjs
 *   node comfy-proxy.mjs 18188 100.x.x.x 8188
 */
const listenPort = Number(process.argv[2] || process.env.LISTEN_PORT || 18188)
const targetHost = process.argv[3] || process.env.TARGET_HOST || '100.x.x.x'
const targetPort = Number(process.argv[4] || process.env.TARGET_PORT || 8188)

const server = net.createServer((client) => {
  const upstream = net.connect(targetPort, targetHost)

  client.on('error', () => upstream.destroy())
  upstream.on('error', () => client.destroy())
  client.on('close', () => upstream.destroy())
  upstream.on('close', () => client.destroy())

  client.pipe(upstream)
  upstream.pipe(client)
})

server.listen(listenPort, '127.0.0.1', () => {
  console.log(`ComfyPilot proxy: http://127.0.0.1:${listenPort} -> ${targetHost}:${targetPort}`)
})
