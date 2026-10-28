import http from 'node:http'
import fs from 'node:fs'

/**
 * 假模型服务：验证 AI 循环的请求拼装与工具往返，不联网、不改工作流。
 *
 * 第一次请求回一个工具调用（默认 list_graphs），收到工具结果后再给一句结论。
 * 这样一条消息就能跑完「发请求 → 工具 → 回填 → 再请求 → 答复」全程，
 * 既不需要真 Key，也不会碰到提交。
 *
 * 用法：
 *   node tools/mock-openai/server.mjs            # 端口 8790
 *   node tools/mock-openai/server.mjs 8791 list_nodes
 *
 * 请求日志追加到 .scratch/mock-ai.log（可用 MOCK_AI_LOG 改路径）。
 */
const PORT = Number(process.argv[2] || 8790)
const TOOL = process.argv[3] || 'list_graphs'
const LOG = process.env.MOCK_AI_LOG || './.scratch/mock-ai.log'

let round = 0

function record(entry) {
  if (!LOG) return
  fs.mkdirSync(LOG.replace(/[\\/][^\\/]+$/, ''), { recursive: true })
  fs.appendFileSync(LOG, `${JSON.stringify(entry)}\n`, 'utf8')
}

function messageFor(messages) {
  const toolResult = messages.filter((item) => item.role === 'tool').pop()
  if (toolResult) {
    return {
      role: 'assistant',
      content: `查到了，工具返回：${String(toolResult.content).slice(0, 160)}`,
    }
  }
  return {
    role: 'assistant',
    content: null,
    tool_calls: [
      {
        id: `call_mock_${++round}`,
        type: 'function',
        function: { name: TOOL, arguments: '{}' },
      },
    ],
  }
}

const server = http.createServer((request, response) => {
  let raw = ''
  request.on('data', (chunk) => { raw += chunk })
  request.on('end', () => {
    let body = {}
    try { body = JSON.parse(raw) } catch { /* 记原文即可，不必中断 */ }
    const messages = body.messages || []
    const reply = messageFor(messages)

    record({
      at: new Date().toISOString(),
      path: request.url,
      model: body.model,
      temperature: body.temperature,
      tools: (body.tools || []).length,
      roles: messages.map((item) => item.role),
      systems: messages.filter((item) => item.role === 'system').map((item) => String(item.content).slice(0, 60)),
      toolResult: messages.filter((item) => item.role === 'tool').pop()?.content ?? null,
    })

    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({
      id: 'mock',
      object: 'chat.completion',
      choices: [{ index: 0, message: reply, finish_reason: reply.tool_calls ? 'tool_calls' : 'stop' }],
    }))
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock openai: http://127.0.0.1:${PORT}/v1/chat/completions (tool: ${TOOL})`)
})
