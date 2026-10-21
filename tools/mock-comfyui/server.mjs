import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import nodePath from 'node:path'
import { fileURLToPath } from 'node:url'
import { WebSocketServer } from 'ws'

/**
 * 最小 ComfyUI 兼容 mock：只实现 ComfyPilot 用到的路由。
 * 目的：在没有真实 ComfyUI 的情况下复现连接异常场景，并给工作流浏览提供夹具。
 *
 * 场景（POST /mock/scenario {"scenario":"..."}）：
 *   normal  正常：进度事件 -> executed -> history success
 *   error   执行失败：execution_error -> history status_str=error
 *   silent  静默：接受提交但永不发事件、永不写 history（测进度看门狗）
 *   lost    丢失：接受提交后立刻从 queue 消失，也不写 history（测 Unknown）
 *   slow    慢速：每 10s 才发一次进度（测自适应静默预算）
 *
 * 工作流夹具在 ./workflows/，通过真实的 /userdata 与 /object_info 路由暴露，
 * 这样客户端走的是和服务端一致的路径，而不是测试专用的后门。
 */

const PORT = Number(process.env.PORT || 8188)
const HOST = process.env.HOST || '0.0.0.0'

const WORKFLOW_DIR = nodePath.join(nodePath.dirname(fileURLToPath(import.meta.url)), 'workflows')

/** 1x1 红色 PNG：既给 /view，也给采样预览帧用。 */
const PREVIEW_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

/** 与真实服务端 `/object_info` 同构的最小节点定义。 */
const OBJECT_INFO = {
  CheckpointLoaderSimple: {
    input: {
      required: {
        ckpt_name: [
          ['sd_xl_base_1.0.safetensors', 'sd15.safetensors'],
          { default: 'sd_xl_base_1.0.safetensors' },
        ],
      },
    },
    output: ['MODEL', 'CLIP', 'VAE'],
    output_name: ['MODEL', 'CLIP', 'VAE'],
    name: 'CheckpointLoaderSimple',
    display_name: 'Load Checkpoint',
    description: 'Loads a diffusion model checkpoint.',
    category: 'loaders',
    output_node: false,
  },
  CLIPTextEncode: {
    input: {
      required: {
        text: ['STRING', { multiline: true, default: '' }],
        clip: ['CLIP'],
      },
    },
    output: ['CONDITIONING'],
    output_name: ['CONDITIONING'],
    name: 'CLIPTextEncode',
    display_name: 'CLIP Text Encode (Prompt)',
    category: 'conditioning',
  },
  KSampler: {
    input: {
      required: {
        model: ['MODEL'],
        seed: [
          'INT',
          {
            default: 0,
            min: 0,
            max: 9007199254740991,
            step: 1,
            control_after_generate: true,
          },
        ],
        steps: ['INT', { default: 20, min: 1, max: 10000 }],
        cfg: ['FLOAT', { default: 8.0, min: 0, max: 100, step: 0.1 }],
        sampler_name: [
          ['euler', 'euler_ancestral', 'dpmpp_2m', 'dpmpp_2m_sde'],
          { default: 'euler' },
        ],
        scheduler: [
          ['normal', 'karras', 'exponential'],
          { default: 'normal' },
        ],
        positive: ['CONDITIONING'],
        negative: ['CONDITIONING'],
        latent_image: ['LATENT'],
        denoise: ['FLOAT', { default: 1.0, min: 0, max: 1, step: 0.01 }],
      },
    },
    output: ['LATENT'],
    output_name: ['LATENT'],
    name: 'KSampler',
    display_name: 'KSampler',
    category: 'sampling',
  },
  EmptyLatentImage: {
    input: {
      required: {
        width: ['INT', { default: 512, min: 16, max: 16384, step: 8 }],
        height: ['INT', { default: 512, min: 16, max: 16384, step: 8 }],
        batch_size: ['INT', { default: 1, min: 1, max: 4096 }],
      },
    },
    output: ['LATENT'],
    output_name: ['LATENT'],
    name: 'EmptyLatentImage',
    display_name: 'Empty Latent Image',
    category: 'latent',
  },
  VAEDecode: {
    input: {
      required: {
        samples: ['LATENT'],
        vae: ['VAE'],
      },
    },
    output: ['IMAGE'],
    output_name: ['IMAGE'],
    name: 'VAEDecode',
    display_name: 'VAE Decode',
    category: 'latent',
  },
  SaveImage: {
    input: {
      required: {
        images: ['IMAGE'],
        filename_prefix: ['STRING', { default: 'ComfyUI' }],
      },
    },
    output: [],
    output_name: [],
    name: 'SaveImage',
    display_name: 'Save Image',
    category: 'image',
    output_node: true,
  },
  LoadImage: {
    input: {
      required: {
        image: [['example.png'], { image_upload: true }],
      },
    },
    output: ['IMAGE', 'MASK'],
    output_name: ['IMAGE', 'MASK'],
    name: 'LoadImage',
    display_name: 'Load Image',
    category: 'image',
  },
}

/** 递归列出夹具，返回真实 `/userdata` 的形状（路径相对 workflows/）。 */
function listWorkflowFiles(dir = WORKFLOW_DIR, base = '') {
  let files = []
  for (const name of readdirSync(dir)) {
    const full = nodePath.join(dir, name)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      files = files.concat(listWorkflowFiles(full, base ? `${base}/${name}` : name))
    } else if (name.toLowerCase().endsWith('.json')) {
      files.push({
        path: base ? `${base}/${name}` : name,
        size: stat.size,
        modified: Math.trunc(stat.mtimeMs),
        created: Math.trunc(stat.birthtimeMs),
      })
    }
  }
  return files
}

const SCENARIOS = new Set(['normal', 'error', 'history_error', 'silent', 'lost', 'slow'])

let scenario = 'normal'
let seq = 0
/** @type {Map<string, {number:number, createdAt:number, progress:number, max:number, status:string}>} */
const pending = new Map()
const running = new Map()
const history = new Map()
const sockets = new Set()
/** 只统计 ComfyUI 真实接口的命中次数，便于验证"失败不重试"之类的承诺。 */
const hits = new Map()
/** 最近一次 /prompt 的 prompt 图：只用于端到端核对提交内容，不落盘。 */
let lastPrompt = null
/** 最近一次 /upload/image 的文件名与字节数，便于核对相册上传链路。 */
let lastUpload = null

function hit(key) {
  hits.set(key, (hits.get(key) ?? 0) + 1)
}

const tickMs = () => (scenario === 'slow' ? 10_000 : 500)

function broadcast(message) {
  const text = JSON.stringify(message)
  for (const ws of sockets) {
    if (ws.readyState === ws.OPEN) ws.send(text)
  }
}

/** 采样预览走二进制帧：4 字节事件类型 + 4 字节 metadata 长度 + JSON + 图片。 */
function previewFrame(promptId) {
  const meta = Buffer.from(JSON.stringify({ prompt_id: promptId, node_id: '3' }))
  const head = Buffer.alloc(8)
  head.writeUInt32BE(4, 0) // BinaryEventTypes.PREVIEW_IMAGE_WITH_METADATA
  head.writeUInt32BE(meta.length, 4)
  return Buffer.concat([head, meta, PREVIEW_PNG])
}

function broadcastBinary(payload) {
  for (const ws of sockets) {
    if (ws.readyState === ws.OPEN) ws.send(payload)
  }
}

function queueRows(map) {
  return [...map.entries()].map(([id, job]) => [job.number, id, {}, {}, []])
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function startJob(promptId, job) {
  if (scenario === 'lost') {
    pending.delete(promptId)
    broadcast({ type: 'status', data: { status: { exec_info: { queue_remaining: pending.size } } } })
    return
  }
  if (scenario === 'silent') {
    // 先让它真的开始跑，然后彻底静默：任务留在 /queue 里，用来验证
    // "WS 静默 -> 看门狗重连 -> /history+/queue 对账后仍在执行中"
    pending.delete(promptId)
    running.set(promptId, job)
    job.status = 'running'
    broadcast({ type: 'execution_start', data: { prompt_id: promptId, timestamp: Date.now() } })
    broadcast({ type: 'progress', data: { prompt_id: promptId, node: '3', value: 1, max: 100 } })
    return
  }

  pending.delete(promptId)
  running.set(promptId, job)
  job.status = 'running'

  broadcast({
    type: 'execution_start',
    data: { prompt_id: promptId, timestamp: Date.now() },
  })

  if (scenario === 'history_error') {
    // WebSocket 只发"结束"提示，错误只写进 /history：验证"可读 + error -> 立即失败、不重试"
    setTimeout(() => {
      running.delete(promptId)
      broadcast({ type: 'executing', data: { prompt_id: promptId, node: null } })
      history.set(promptId, {
        prompt: [],
        outputs: {},
        status: {
          status_str: 'error',
          completed: false,
          messages: [
            ['execution_start', { prompt_id: promptId }],
            [
              'execution_error',
              {
                prompt_id: promptId,
                node_id: '3',
                exception_message: 'history-only error (mock)',
              },
            ],
          ],
        },
      })
    }, 500)
    return
  }

  if (scenario === 'error') {
    setTimeout(() => {
      running.delete(promptId)
      broadcast({
        type: 'execution_error',
        data: {
          prompt_id: promptId,
          node_id: '3',
          node_type: 'KSampler',
          exception_message: 'CUDA out of memory (mock)',
          exception_type: 'RuntimeError',
          traceback: [],
        },
      })
      history.set(promptId, {
        prompt: [],
        outputs: {},
        status: {
          status_str: 'error',
          completed: false,
          messages: [
            ['execution_start', { prompt_id: promptId }],
            [
              'execution_error',
              {
                prompt_id: promptId,
                node_id: '3',
                exception_message: 'CUDA out of memory (mock)',
                exception_type: 'RuntimeError',
              },
            ],
          ],
        },
      })
      broadcast({ type: 'status', data: { status: { exec_info: { queue_remaining: pending.size } } } })
    }, 800)
    return
  }

  const step = () => {
    if (!running.has(promptId)) return
    job.progress += 1
    broadcast({
      type: 'progress',
      data: {
        prompt_id: promptId,
        node: '3',
        value: job.progress,
        max: job.max,
      },
    })
    broadcastBinary(previewFrame(promptId))
    if (job.progress >= job.max) {
      finishJob(promptId, job)
    } else {
      setTimeout(step, tickMs())
    }
  }
  setTimeout(step, 200)
}

function finishJob(promptId, job) {
  running.delete(promptId)
  const filename = `ComfyPilot_mock_${promptId.slice(0, 8)}.png`
  broadcast({
    type: 'executing',
    data: { prompt_id: promptId, node: null },
  })
  broadcast({
    type: 'executed',
    data: {
      prompt_id: promptId,
      node: '9',
      output: { images: [{ filename, subfolder: '', type: 'output' }] },
    },
  })
  history.set(promptId, {
    prompt: [],
    outputs: {
      9: { images: [{ filename, subfolder: '', type: 'output' }] },
    },
    status: {
      status_str: 'success',
      completed: true,
      messages: [
        ['execution_start', { prompt_id: promptId }],
        ['execution_success', { prompt_id: promptId }],
      ],
    },
  })
  broadcast({ type: 'status', data: { status: { exec_info: { queue_remaining: pending.size } } } })
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  const path = url.pathname

  try {
    if (path === '/prompt') hit('prompt')
    else if (path === '/queue') hit('queue')
    else if (path.startsWith('/history/')) hit('history')
    else if (path === '/interrupt') hit('interrupt')
    else if (path === '/userdata') hit('userdata')
    else if (path.startsWith('/userdata/')) hit('workflow')
    else if (path.startsWith('/object_info/')) hit('object_info')
    else if (path === '/system_stats') hit('system')

    if (req.method === 'POST' && path === '/prompt') {
      const raw = await readBody(req)
      let payload
      try {
        payload = JSON.parse(raw || '{}')
      } catch {
        return sendJson(res, 400, { error: { type: 'invalid_json', message: 'body is not valid JSON' } })
      }
      if (!payload.prompt || typeof payload.prompt !== 'object') {
        return sendJson(res, 400, {
          error: { type: 'prompt_no_prompt', message: 'body does not contain a prompt' },
          node_errors: {},
        })
      }
      lastPrompt = payload.prompt
      const promptId = randomUUID()
      const number = seq++
      const job = { number, createdAt: Date.now(), progress: 0, max: 8, status: 'pending' }
      pending.set(promptId, job)
      broadcast({ type: 'status', data: { status: { exec_info: { queue_remaining: pending.size + running.size } } } })
      sendJson(res, 200, { prompt_id: promptId, number, node_errors: {} })
      setTimeout(() => startJob(promptId, job), 150)
      return
    }

    if (req.method === 'GET' && path === '/queue') {
      return sendJson(res, 200, {
        queue_running: queueRows(running),
        queue_pending: queueRows(pending),
      })
    }

    // 与真实 ComfyUI 同构；故意不提供 CPU/GPU 利用率（官方路由也没有）。
    if (req.method === 'GET' && path === '/system_stats') {
      return sendJson(res, 200, {
        system: {
          os: 'posix',
          ram_total: 32 * 1024 ** 3,
          ram_free: 12 * 1024 ** 3,
          comfyui_version: 'mock-0.3',
          python_version: '3.12',
          pytorch_version: '2.6',
        },
        devices: [
          {
            name: 'Mock GPU',
            type: 'cuda',
            index: 0,
            vram_total: 24 * 1024 ** 3,
            vram_free: 8 * 1024 ** 3,
          },
        ],
      })
    }

    if (req.method === 'GET' && path.startsWith('/history/')) {
      const promptId = decodeURIComponent(path.slice('/history/'.length))
      const entry = history.get(promptId)
      if (!entry) return sendJson(res, 200, {})
      return sendJson(res, 200, { [promptId]: entry })
    }

    if (req.method === 'POST' && path === '/interrupt') {
      await readBody(req)
      running.clear()
      broadcast({ type: 'execution_interrupted', data: { node_id: '3' } })
      return sendJson(res, 200, {})
    }

    if (req.method === 'GET' && path === '/view') {
      // 1x1 红色 PNG，足够验证"接续方从服务端取图"的链路
      const png = PREVIEW_PNG
      res.writeHead(200, { 'content-type': 'image/png', 'content-length': png.length })
      return res.end(png)
    }

    if (req.method === 'POST' && path === '/upload/image') {
      const raw = await readBody(req)
      // 真实服务端会解析 multipart 并保存文件；这里只抄回文件名，足够验证客户端链路。
      const match = /filename="([^"]+)"/.exec(raw)
      const name = nodePath.basename(match ? match[1] : 'mock-upload.png')
      lastUpload = { name, bytes: Buffer.byteLength(raw) }
      return sendJson(res, 200, { name, subfolder: '', type: 'input' })
    }

    // 真实服务端：dir=workflows + full_info=true -> [{path,size,modified,created}]
    if (req.method === 'GET' && path === '/userdata') {
      const dir = url.searchParams.get('dir') || 'workflows'
      if (dir !== 'workflows') return sendJson(res, 200, [])
      return sendJson(res, 200, listWorkflowFiles())
    }

    // 真实服务端：/userdata/{file} 直接返回原文（JSON 文件没有额外包装）。
    if (req.method === 'GET' && path.startsWith('/userdata/')) {
      let rel = decodeURIComponent(path.slice('/userdata/'.length))
      // 与真实服务端一致：读取路径是 "workflows/<相对路径>"，前端整体 encodeURIComponent。
      if (rel.startsWith('workflows/')) rel = rel.slice('workflows/'.length)
      const root = nodePath.resolve(WORKFLOW_DIR)
      const full = nodePath.resolve(root, rel)
      if (full !== root && !full.startsWith(root + nodePath.sep)) {
        return sendJson(res, 400, { error: 'invalid path' })
      }
      try {
        const body = readFileSync(full)
        res.writeHead(200, {
          'content-type': 'application/json; charset=utf-8',
          'content-length': body.length,
        })
        return res.end(body)
      } catch {
        return sendJson(res, 404, { error: 'not found' })
      }
    }

    // 真实服务端：未知类返回 {}（200），不是 404。
    if (req.method === 'GET' && path.startsWith('/object_info/')) {
      const name = decodeURIComponent(path.slice('/object_info/'.length))
      const info = OBJECT_INFO[name]
      return sendJson(res, 200, info ? { [name]: info } : {})
    }

    if (req.method === 'POST' && path === '/mock/scenario') {
      const raw = await readBody(req)
      const body = JSON.parse(raw || '{}')
      if (!SCENARIOS.has(body.scenario)) {
        return sendJson(res, 400, { error: `unknown scenario: ${body.scenario}` })
      }
      scenario = body.scenario
      return sendJson(res, 200, { scenario })
    }

    if (req.method === 'GET' && path === '/mock/state') {
      return sendJson(res, 200, {
        scenario,
        pending: pending.size,
        running: running.size,
        history: history.size,
        sockets: sockets.size,
        workflows: listWorkflowFiles().length,
        hits: Object.fromEntries(hits),
        lastPrompt,
        lastUpload,
      })
    }

    if (req.method === 'POST' && path === '/mock/reset') {
      pending.clear()
      running.clear()
      history.clear()
      hits.clear()
      lastPrompt = null
      lastUpload = null
      seq = 0
      return sendJson(res, 200, { ok: true })
    }

    // 模拟 "ComfyUI 进程重启"：只掐断 WebSocket，不重建 jobs/history/hits，
    // 这样客户端重连对账的过程可以在计数器不被清零的前提下验证。
    if (req.method === 'POST' && path === '/mock/drop-sockets') {
      const dropped = sockets.size
      for (const ws of sockets) {
        try {
          ws.close(1012, 'server restart')
        } catch {
          /* 已断开 */
        }
      }
      sockets.clear()
      return sendJson(res, 200, { dropped })
    }

    sendJson(res, 404, { error: 'not found', path })
  } catch (error) {
    sendJson(res, 500, { error: String(error) })
  }
})

const wss = new WebSocketServer({ noServer: true })

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  if (url.pathname !== '/ws') {
    socket.destroy()
    return
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req)
  })
})

wss.on('connection', (ws) => {
  sockets.add(ws)
  ws.send(
    JSON.stringify({
      type: 'status',
      data: { status: { exec_info: { queue_remaining: pending.size + running.size } }, sid: randomUUID() },
    }),
  )
  ws.on('message', (data) => {
    // ComfyUI 的 ws 允许客户端发心跳；这里只回显，用于验证链路
    try {
      const message = JSON.parse(data.toString())
      if (message.type === 'ping') ws.send(JSON.stringify({ type: 'pong', data: {} }))
    } catch {
      /* ignore */
    }
  })
  ws.on('close', () => sockets.delete(ws))
  ws.on('error', () => sockets.delete(ws))
})

server.listen(PORT, HOST, () => {
  console.log(`mock ComfyUI listening on http://${HOST}:${PORT} (ws://${HOST}:${PORT}/ws?clientId=demo)`)
  console.log(`scenario=${scenario}; POST /mock/scenario {"scenario":"normal|error|silent|lost|slow"}`)
})
