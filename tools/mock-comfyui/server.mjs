import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import zlib from 'node:zlib'
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

/**
 * 采样预览夹具图：一张 192×192 的紫粉渐变（同心圆纹路），体积接近真实 latents。
 * 用真实图片而不是 1×1 纯色：截图能看出「预览真的显示出来了」，
 * 也顺带压一压 base64 往返的量级。`/view` 返回的产物图也是它。
 */
const PREVIEW_PNG = readFileSync(
  nodePath.join(nodePath.dirname(fileURLToPath(import.meta.url)), 'preview.png'),
)

/** 视频样例：ffmpeg 生成的 4 秒 H.264。视频任务（VHS_VideoCombine）经 /view 返回它。 */
const SAMPLE_MP4_PATH = nodePath.join(
  nodePath.dirname(fileURLToPath(import.meta.url)),
  'sample.mp4',
)
const SAMPLE_MP4 = existsSync(SAMPLE_MP4_PATH)
  ? readFileSync(SAMPLE_MP4_PATH)
  : Buffer.alloc(0)

/** 音频样例：3 秒双音提示音，音频任务（SaveAudio）经 /view 返回它。 */
const SAMPLE_AUDIO_PATH = nodePath.join(
  nodePath.dirname(fileURLToPath(import.meta.url)),
  'sample.wav',
)
const SAMPLE_AUDIO = existsSync(SAMPLE_AUDIO_PATH)
  ? readFileSync(SAMPLE_AUDIO_PATH)
  : Buffer.alloc(0)

/**
 * fixtures/：演示素材夹具（可选）。
 * frames/frame-1..8.jpg 是同一张图的采样过程帧（噪点云团 → 逐渐显影 → 成品），
 * sample-output.jpg 是出图产物。没有这些文件时回退到程序化生成/1x1 占位图。
 */
const FIXTURES_DIR = nodePath.join(
  nodePath.dirname(fileURLToPath(import.meta.url)),
  'fixtures',
)
const PREVIEW_FRAMES = (() => {
  const frames = []
  for (let i = 1; i <= 8; i++) {
    const file = nodePath.join(FIXTURES_DIR, 'frames', `frame-${i}.jpg`)
    if (existsSync(file)) frames.push(readFileSync(file))
  }
  return frames
})()
const SAMPLE_OUTPUT = (() => {
  const file = nodePath.join(FIXTURES_DIR, 'sample-output.jpg')
  return existsSync(file) ? readFileSync(file) : null
})()

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
  PreviewImage: {
    input: {
      required: {
        images: ['IMAGE'],
      },
    },
    output: ['IMAGE'],
    output_name: ['IMAGE'],
    name: 'PreviewImage',
    display_name: 'Preview Image',
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
  LoadAudio: {
    input: {
      required: {
        audio: [['example.wav'], { audio_upload: true }],
      },
    },
    output: ['AUDIO'],
    output_name: ['AUDIO'],
    name: 'LoadAudio',
    display_name: 'Load Audio',
    category: 'audio',
  },
  SaveAudio: {
    input: {
      required: {
        audio: ['AUDIO'],
        filename_prefix: ['STRING', { default: 'ComfyPilot' }],
      },
    },
    output: [],
    output_name: [],
    name: 'SaveAudio',
    display_name: 'Save Audio',
    category: 'audio',
    output_node: true,
  },
  VHS_VideoCombine: {
    input: {
      required: {
        frame_rate: ['INT', { default: 24, min: 1, max: 120 }],
        loop_count: ['INT', { default: 0 }],
        format: [
          ['video/h264-mp4', 'video/h265-mp4', 'video/webm'],
          { default: 'video/h264-mp4' },
        ],
        pix_fmt: [['yuv420p', 'yuvp10le'], { default: 'yuv420p' }],
        crf: ['INT', { default: 19, min: 0, max: 51 }],
        images: ['IMAGE'],
      },
      optional: {
        audio: ['AUDIO'],
      },
    },
    output: ['VHS_VIDEOINFO'],
    output_name: ['video_info'],
    name: 'VHS_VideoCombine',
    display_name: 'Video Combine 🎥🅥🅗🅢',
    description: ' mock: combines frames into a video and reports it as an mp4 artifact.',
    category: 'video',
    output_node: true,
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
/** 最近一次 /upload/image 的文件名、字节数与 PNG 颜色类型，便于核对上传链路与 alpha。 */
let lastUpload = null
/** 最近 10 次上传，UI 测试用它区分「原图」和「蒙版」两次上传。 */
const uploads = []

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
function previewFrame(promptId, progress = 0) {
  const meta = Buffer.from(JSON.stringify({ prompt_id: promptId, node_id: '3' }))
  const head = Buffer.alloc(8)
  head.writeUInt32BE(4, 0) // BinaryEventTypes.PREVIEW_IMAGE_WITH_METADATA
  head.writeUInt32BE(meta.length, 4)
  // 有素材帧就按进度取帧（1..8），没有则回退程序化「逐渐显影」。
  const image = PREVIEW_FRAMES.length
    ? PREVIEW_FRAMES[
        Math.min(
          PREVIEW_FRAMES.length - 1,
          Math.max(0, Math.ceil(progress * PREVIEW_FRAMES.length) - 1),
        )
      ]
    : makePreviewImage(progress)
  return Buffer.concat([head, meta, image])
}

// ------------------------------------------------------------ 程序化 PNG
// 手写 PNG 编码（node 内置 zlib 即可，无第三方依赖）：模拟真实 ComfyUI 的
// latent 采样预览——进度越高，画面从上往下逐渐「渲染成型」，下方仍是噪点。

const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()

function pngChunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length, 0)
  const typeBuf = Buffer.from(type, 'ascii')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(
    (() => {
      const body = Buffer.concat([typeBuf, data])
      let c = -1
      for (let i = 0; i < body.length; i++) c = CRC_TABLE[(c ^ body[i]) & 0xff] ^ (c >>> 8)
      return (c ^ -1) >>> 0
    })(),
    0,
  )
  return Buffer.concat([len, typeBuf, data, crc])
}

/** progress 0~1：上 percent 部分是「成图」，下面是彩色噪点，视觉上逐渐显影 */
function makePreviewImage(progress) {
  const size = 256
  const cut = Math.max(2, Math.floor(size * Math.min(Math.max(progress, 0), 1)))
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // color type: truecolor RGB
  const raw = Buffer.alloc(size * (1 + size * 3))
  let p = 0
  for (let y = 0; y < size; y++) {
    raw[p++] = 0 // filter: none
    for (let x = 0; x < size; x++) {
      let r, g, b
      if (y < cut) {
        // 「成图」区：柔和渐变条纹，越往下越接近成品色调
        const t = y / size
        const band = Math.floor((x / size) * 6) % 2 ? 46 : 208
        r = (band * (0.45 + 0.55 * t)) | 0
        g = (140 + ((x + y) % 48)) | 0
        b = (96 + (x % 90)) | 0
      } else {
        // 噪点区：伪随机彩色噪点（每帧随 cut 变化，看起来在动）
        r = (x * 7 + y * 13 + cut * 31) % 255
        g = (x * 3 + y * 17 + cut * 11) % 255
        b = (x * 11 + y * 5 + cut * 7) % 255
      }
      raw[p++] = r
      raw[p++] = g
      raw[p++] = b
    }
  }
  const idat = zlib.deflateSync(raw)
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', idat),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
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

/** 二进制安全版本：/upload/image 要从 multipart 里取出真实的 PNG 字节校验 alpha。 */
function readRawBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

/**
 * 从 multipart 里摘出 name="image" 的文件字节。latin1 与字节一一对应，
 * 用它做分隔不会像 utf8 那样把 PNG 数据改坏。
 */
function multipartImageBytes(raw, contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;\s]+))/.exec(contentType || '')
  const boundary = match ? (match[1] || match[2]) : null
  if (!boundary) return null
  const marker = `--${boundary}`
  const parts = raw.toString('latin1').split(marker)
  for (const part of parts) {
    if (!/name="image"/.test(part)) continue
    const split = part.indexOf('\r\n\r\n')
    if (split < 0) continue
    const bytes = Buffer.from(part.slice(split + 4), 'latin1')
    const end = bytes.lastIndexOf('\r\n')
    return end > 0 ? bytes.subarray(0, end) : bytes
  }
  return null
}

/** PNG IHDR 的第 25 字节是颜色类型：6=RGBA、2=RGB；非 PNG 返回 null。 */
function pngColorType(bytes) {
  if (!bytes || bytes.length < 26 || bytes.subarray(1, 4).toString() !== 'PNG') return null
  return bytes[25]
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
    broadcastBinary(previewFrame(promptId, job.progress / job.max))
    if (job.progress >= job.max) {
      finishJob(promptId, job)
    } else {
      setTimeout(step, tickMs())
    }
  }
  setTimeout(step, 200)
}

/**
 * 会产出文件的节点。PreviewImage 只写 temp 预览，不当作产物——
 * 客户端看到的产物数量要和提交里的「真正输出节点」一致。
 */
const OUTPUT_CLASSES = new Set(['SaveImage', 'SaveAudio', 'VHS_VideoCombine'])

/** 提交里的输出节点；一个都没有时（例如只跑 KSampler）沿用老行为挂到 9 号。 */
function outputNodesOf(prompt) {
  const nodes = []
  for (const [id, node] of Object.entries(prompt || {})) {
    if (!node || !OUTPUT_CLASSES.has(node.class_type)) continue
    nodes.push({ id, classType: node.class_type })
  }
  return nodes.length > 0 ? nodes : [{ id: '9', classType: 'SaveImage' }]
}

/** 单个输出节点的产物：后缀跟节点类型走，多节点时文件名带上节点号避免撞名。 */
function artifactOf(promptId, node, multi) {
  const ext =
    node.classType === 'VHS_VideoCombine'
      ? 'mp4'
      : node.classType === 'SaveAudio'
        ? 'wav'
        : SAMPLE_OUTPUT
          ? 'jpg'
          : 'png'
  const kind = node.classType === 'SaveAudio' ? 'audio' : 'images'
  const suffix = multi ? `_${node.id}` : ''
  return { nodeId: node.id, kind, filename: `ComfyPilot_mock_${promptId.slice(0, 8)}${suffix}.${ext}` }
}

function finishJob(promptId, job) {
  running.delete(promptId)
  const nodes = job.outNodes
  // 一个输出节点产一个文件：两路 SaveImage 就是两个产物，产物查看器才有「上一个/下一个」。
  const artifacts = nodes.map((node) => artifactOf(promptId, node, nodes.length > 1))

  // 先落 history 再广播：客户端收到 executing(null) 会立刻拉 /history，
  // 顺序反了会拿到空 outputs（任务显示完成但没有产物）。
  history.set(promptId, {
    prompt: [],
    outputs: Object.fromEntries(
      artifacts.map((item) => [
        item.nodeId,
        { [item.kind]: [{ filename: item.filename, subfolder: '', type: 'output' }] },
      ]),
    ),
    status: {
      status_str: 'success',
      completed: true,
      messages: [
        ['execution_start', { prompt_id: promptId }],
        ['execution_success', { prompt_id: promptId }],
      ],
    },
  })

  // 每个输出节点各发一条 executed（真实 ComfyUI 就是这么逐节点推的），
  // 客户端据此累计已完成节点数。
  for (const item of artifacts) {
    broadcast({
      type: 'executed',
      data: {
        prompt_id: promptId,
        node: item.nodeId,
        output: { [item.kind]: [{ filename: item.filename, subfolder: '', type: 'output' }] },
      },
    })
  }
  broadcast({
    type: 'executing',
    data: { prompt_id: promptId, node: null },
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
      // 产物按提交里的输出节点逐个生成：两路 SaveImage → 两个产物。
      const job = {
        number,
        createdAt: Date.now(),
        progress: 0,
        max: 8,
        status: 'pending',
        outNodes: outputNodesOf(payload.prompt),
      }
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

    // 真实 ComfyUI：GET /history 不带 id 时返回全部历史。App 重启后靠它
    // 恢复任务列表——没有这个接口，任务页每次启动都是空的（CI #54/#56 的
    // artifact 截图就是这个原因）。
    if (req.method === 'GET' && path === '/history') {
      const all = {}
      for (const [id, entry] of history) all[id] = entry
      return sendJson(res, 200, all)
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
      const filename = url.searchParams.get('filename') || ''
      // 音频产物：返回 WAV 样例；同时支持 Range，MediaPlayer 才能拖动/稳定播放。
      if (filename.toLowerCase().endsWith('.wav') && SAMPLE_AUDIO.length > 0) {
        const total = SAMPLE_AUDIO.length
        const range = req.headers.range
        if (range) {
          const m = /bytes=(\d*)-(\d*)/.exec(range)
          let start = m && m[1] ? parseInt(m[1], 10) : 0
          let end = m && m[2] ? parseInt(m[2], 10) : total - 1
          if (Number.isNaN(start) || start > total - 1) start = 0
          if (Number.isNaN(end) || end >= total) end = total - 1
          const chunk = SAMPLE_AUDIO.subarray(start, end + 1)
          res.writeHead(206, {
            'content-type': 'audio/wav',
            'content-length': chunk.length,
            'content-range': `bytes ${start}-${end}/${total}`,
            'accept-ranges': 'bytes',
          })
          return res.end(chunk)
        }
        res.writeHead(200, {
          'content-type': 'audio/wav',
          'content-length': total,
          'accept-ranges': 'bytes',
        })
        return res.end(SAMPLE_AUDIO)
      }
      // filename 以 .mp4 结尾 → 回视频样例（AVPlayer 严格依赖 Range，必须实现 206）
      if (filename.endsWith('.mp4') && SAMPLE_MP4) {
        const total = SAMPLE_MP4.length
        const range = req.headers.range
        if (range) {
          const m = /bytes=(\d*)-(\d*)/.exec(range)
          let start = m && m[1] ? parseInt(m[1], 10) : 0
          let end = m && m[2] ? parseInt(m[2], 10) : total - 1
          if (Number.isNaN(start) || start > total - 1) start = 0
          if (Number.isNaN(end) || end >= total) end = total - 1
          const chunk = SAMPLE_MP4.subarray(start, end + 1)
          res.writeHead(206, {
            'content-type': 'video/mp4',
            'content-length': chunk.length,
            'content-range': `bytes ${start}-${end}/${total}`,
            'accept-ranges': 'bytes',
          })
          return res.end(chunk)
        }
        res.writeHead(200, {
          'content-type': 'video/mp4',
          'content-length': total,
          'accept-ranges': 'bytes',
        })
        return res.end(SAMPLE_MP4)
      }
      // 有素材就返回成品图（真实尺寸 jpg）；否则 1x1 红色 PNG 占位。
      if (SAMPLE_OUTPUT) {
        res.writeHead(200, { 'content-type': 'image/jpeg', 'content-length': SAMPLE_OUTPUT.length })
        return res.end(SAMPLE_OUTPUT)
      }
      const png = PREVIEW_PNG
      res.writeHead(200, { 'content-type': 'image/png', 'content-length': png.length })
      return res.end(png)
    }

    if (req.method === 'POST' && path === '/upload/image') {
      const raw = await readRawBody(req)
      // 真实服务端会解析 multipart 并保存文件；这里抄回文件名，同时记录文件字节，
      // 让 UI 测试能断言蒙版 PNG 真的带 alpha（颜色类型 6），而不是被转成 RGB。
      const latin = raw.toString('latin1')
      const match = /filename="([^"]+)"/.exec(latin)
      const name = nodePath.basename(match ? match[1] : 'mock-upload.png')
      const fileBytes = multipartImageBytes(raw, req.headers['content-type'])
      const colorType = pngColorType(fileBytes)
      lastUpload = { name, bytes: fileBytes?.length ?? 0, pngColorType: colorType }
      uploads.push(lastUpload)
      if (uploads.length > 10) uploads.shift()
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
      // 真实服务端里，上传的图会出现在 LoadImage 的下拉选项里；这里同步一下，
      // 否则回填的文件名不在选项内，客户端本地校验会把它当成非法值。
      if (info && name === 'LoadImage' && lastUpload?.name) {
        const options = info.input?.required?.image?.[0]
        if (Array.isArray(options) && !options.includes(lastUpload.name)) {
          options.unshift(lastUpload.name)
        }
      }
      if (info && name === 'LoadAudio' && lastUpload?.name) {
        const options = info.input?.required?.audio?.[0]
        if (Array.isArray(options) && !options.includes(lastUpload.name)) {
          options.unshift(lastUpload.name)
        }
      }
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
        uploads,
      })
    }

    if (req.method === 'POST' && path === '/mock/reset') {
      pending.clear()
      running.clear()
      history.clear()
      hits.clear()
      lastPrompt = null
      lastUpload = null
      uploads.length = 0
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
