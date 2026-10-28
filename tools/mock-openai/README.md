# mock-openai

AI 循环联调用的最小 OpenAI 兼容服务：只实现 `/v1/chat/completions`，固定回一个
工具调用，收到工具结果后再回一句结论。

**用途**：不配真实 Key、不碰提交，也能验证「请求拼装 → 工具分派 → 结果回填 →
二次请求 → 答复渲染」整条链路。它不参与单元测试——循环状态机的单测跑在
`:shared:jvmTest`，不需要起服务。

## 启动

```powershell
node tools/mock-openai/server.mjs              # 127.0.0.1:8790，工具用 list_graphs
node tools/mock-openai/server.mjs 8791 list_nodes
```

请求摘要追加到 `.scratch/mock-ai.log`（`MOCK_AI_LOG` 可改路径），每条一行。
日志里能直接看到 `tools` 数量、`roles` 顺序和回填的 `toolResult`。

## 接上 App

1. 模拟器里把 AI 的「接口地址」填 `http://10.0.2.2:8790/v1`，Key 随便填（例如 `mock`）。
2. 打开一个工作流，对 AI 说任意一句话。
3. 回复里会带上工具返回的 JSON —— 那就是真实的 `list_graphs` 结果。

串完记得把接口地址改回真实服务商；Key 留空时 App 会提示未配置。
