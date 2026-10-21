import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { WebSocketServer } from 'ws'

/**
 * 最小 ComfyUI 兼容 mock：只实现 ComfyPilot 阶段一用到的路由。
 * 目的：在没有真实 ComfyUI 的情况下复现四种连接异常场景。
 *
 * 场景（POST /mock/scenario {"scenario":"..."}）：
 *   normal  正常：进度事件 -> executed -> history success
 *   error   执行失败：execution_error -> history status_str=error
 *   silent  静默：接受提交但永不发事件、永不写 history（测进度看门狗）
 *   lost    丢失：接受提交后立刻从 queue 消失，也不写 history（测 Unknown）
 *   slow    慢速：每 10s 才发一次进度（测自适应静默预算）
 */

const PORT = Number(process.env.PORT || 8188)
const HOST = process.env.HOST || '0.0.0.0'

const SCENARIOS = new Set(['normal', 'error', 'silent', 'lost', 'slow'])

let scenario = 'normal'
let seq = 0
/** @type {Map<string, {number:number, createdAt:number, progress:number, max:number, status:string}>} */
const pending = new Map()
const running = new Map()
const history = new Map()
const sockets = new Set()

const tickMs = () => (scenario === 'slow' ? 10_000 : 500)

function broadcast(message) {
  const text = JSON.stringify(message)
  for (const ws of sockets) {
    if (ws.readyState === ws.OPEN) ws.send(text)
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
    // 故意什么都不发：用于验证客户端进度静默看门狗
    pending.delete(promptId)
    return
  }

  pending.delete(promptId)
  running.set(promptId, job)
  job.status = 'running'

  broadcast({
    type: 'execution_start',
    data: { prompt_id: promptId, timestamp: Date.now() },
  })

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
      // 1x1 透明 PNG，足够验证"接续方从服务端取图"的链路
      const png = Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
        'base64',
      )
      res.writeHead(200, { 'content-type': 'image/png', 'content-length': png.length })
      return res.end(png)
    }

    if (req.method === 'POST' && path === '/upload/image') {
      await readBody(req)
      return sendJson(res, 200, { name: 'mock-upload.png', subfolder: '', type: 'input' })
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
      })
    }

    if (req.method === 'POST' && path === '/mock/reset') {
      pending.clear()
      running.clear()
      history.clear()
      seq = 0
      return sendJson(res, 200, { ok: true })
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
