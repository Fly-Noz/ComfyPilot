package com.comfypilot.app.ai

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.comfypilot.core.ai.AiApplyResult
import com.comfypilot.core.ai.AiChange
import com.comfypilot.core.ai.AiEditState
import com.comfypilot.core.ai.AiEdits
import com.comfypilot.core.ai.AiWorkflowView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 用户配置的模型服务；Key 加密后存本机。 */
data class AiConfig(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val model: String = "deepseek-chat",
    val apiKey: String = "",
    /** 总开关：关闭后悬浮球不出现，也不会发起请求。 */
    val enabled: Boolean = false,
    /** true：AI 改完直接应用并提交；false：先展示 diff 等用户确认。 */
    val autoSubmit: Boolean = false,
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

/** 详情页接入 AI 的桥：提供当前工作流、应用编辑、提交、切换工作流。 */
interface AiWorkflowHost {
    fun digest(): AiWorkflowView?

    fun editState(): AiEditState

    /** 应用编辑；返回实际生效后的结果（可能包含校验错误）。 */
    fun applyEdit(state: AiEditState): AiApplyResult

    fun submitCurrent(): Boolean

    fun openWorkflow(path: String): Boolean

    /** 可供 AI 切换的工作流路径（目录里已有的）。 */
    fun availableWorkflows(): List<String> = emptyList()

    /** 当前工作流里生成模型的提示词写法（宿主按节点推断）。 */
    fun promptGuide(): String = ""
}

data class AiChatMessage(
    val role: String,
    val content: String,
)

data class AiQuestion(val question: String, val options: List<String>)

data class AiSnapshot(
    val actions: List<String>,
    val messages: List<AiChatMessage>,
    val pending: AiPendingEdit?,
    val question: AiQuestion?,
    val busy: Boolean,
    val error: String?,
    val tokens: Int,
    val config: AiConfig,
)

data class AiPendingEdit(
    val summary: String,
    val state: AiEditState,
    val changes: List<AiChange>,
)

class AiSecretStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("ai_assistant", Context.MODE_PRIVATE)

    fun load(): AiConfig = AiConfig(
        baseUrl = prefs.getString("base_url", null) ?: AiConfig().baseUrl,
        model = prefs.getString("model", null) ?: AiConfig().model,
        apiKey = decrypt(prefs.getString("api_key", null).orEmpty()),
        enabled = prefs.getBoolean("enabled", false),
        autoSubmit = prefs.getBoolean("auto_submit", false),
    )

    fun save(config: AiConfig) {
        prefs.edit()
            .putString("base_url", config.baseUrl.trim())
            .putString("model", config.model.trim())
            .putString("api_key", encrypt(config.apiKey.trim()))
            .putBoolean("enabled", config.enabled)
            .putBoolean("auto_submit", config.autoSubmit)
            .apply()
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    private fun decrypt(payload: String): String {
        if (payload.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val body = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "comfypilot.ai.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * AI 会话运行时：消息、待确认编辑、配置都放在这里。
 *
 * Provider 请求是 OpenAI 兼容的 /chat/completions；工具调用通过 [AiWorkflowHost]
 * 落到现有编辑/提交链路上，不新增旁路。
 */
object AiRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var config: AiConfig by mutableStateOf(AiConfig())
        private set
    var actions: List<String> = emptyList()
        private set
    var messages: List<AiChatMessage> = emptyList()
        private set
    var pending: AiPendingEdit? = null
        private set
    var question: AiQuestion? = null
        private set
    var busy: Boolean = false
        private set
    var error: String? = null
        private set
    var tokenEstimate: Int = 0
        private set
    var host: AiWorkflowHost? = null

    /** 空 = 只能用当前工作流；非空 = 这些路径可被 AI 查看/切换。 */
    var scopePaths: Set<String> = emptySet()
        private set
    var scopeAll: Boolean = true
        private set

    fun setScope(all: Boolean, paths: Set<String>) {
        scopeAll = all
        scopePaths = paths
    }

    private fun allowedPaths(currentHost: AiWorkflowHost): List<String> {
        val current = currentHost.digest()?.path.orEmpty()
        if (scopeAll) return currentHost.availableWorkflows()
        if (scopePaths.isEmpty()) return listOf(current)
        return scopePaths.toList()
    }

    private var secretStore: AiSecretStore? = null

    fun attach(context: Context) {
        if (secretStore == null) {
            secretStore = AiSecretStore(context.applicationContext)
            config = secretStore!!.load()
        }
    }

    fun saveConfig(next: AiConfig) {
        config = next
        secretStore?.save(next)
    }

    fun resetConversation() {
        messages = emptyList()
        pending = null
        error = null
        tokenEstimate = 0
    }

    fun clearError() {
        error = null
    }

    fun snapshot(): AiSnapshot =
        AiSnapshot(actions, messages, pending, question, busy, error, tokenEstimate, config)

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || busy) return
        if (!config.enabled) {
            error = "先在设置中启用 AI 助手"
            return
        }
        if (!config.ready) {
            error = "先在设置里填好接口地址、模型和 API Key"
            return
        }
        val currentHost = host
        if (currentHost == null) {
            error = "先打开一个工作流，AI 需要工作流上下文"
            return
        }
        aiLog("send: $text")
        messages = messages + AiChatMessage("user", text)
        pending = null
        error = null
        busy = true
        job = scope.launch {
            try {
                runLoop(currentHost)
            } catch (err: Throwable) {
                error = err.message ?: "AI 请求失败"
            } finally {
                busy = false
            }
        }
    }

    fun confirmPending(submit: Boolean) {
        val edit = pending ?: return
        val currentHost = host ?: return
        pending = null
        val result = currentHost.applyEdit(edit.state)
        if (!result.ok) {
            error = result.errors.joinToString("\n")
            return
        }
        actions = actions + edit.changes.map { it.label }
        messages = messages + AiChatMessage("assistant", "已应用：${edit.summary}")
        if (submit) {
            if (!currentHost.submitCurrent()) {
                error = "提交失败：请检查工作流是否有未填字段"
            } else {
                messages = messages + AiChatMessage("assistant", "已提交任务，等待结果。")
            }
        }
    }

    fun cancelPending() {
        pending = null
    }

    fun answerQuestion(answer: String) {
        val current = question ?: return
        question = null
        messages = messages + AiChatMessage("user", answer)
        val currentHost = host ?: return
        busy = true
        job = scope.launch {
            try {
                runLoop(currentHost)
            } catch (err: Throwable) {
                error = err.message ?: "AI 请求失败"
            } finally {
                busy = false
            }
        }
    }

    /** 停止进行中的请求与工具循环。 */
    fun stop() {
        aiLog("stop requested")
        job?.cancel()
        job = null
        busy = false
        error = "已停止"
    }

    private suspend fun runLoop(currentHost: AiWorkflowHost) {
        val view = currentHost.digest()
        val conversation = mutableListOf<JsonObject>()
        conversation += buildJsonObject {
            put("role", "user")
            put("content", messages.lastOrNull()?.content.orEmpty())
        }
        repeat(6) { round ->
            val state = currentHost.editState()
            val digest = view?.let { com.comfypilot.core.ai.AiDigest.summary(it, state) }
            tokenEstimate = (digest?.let { com.comfypilot.core.ai.AiDigest.estimateTokens(it) } ?: 0) +
                messages.sumOf { com.comfypilot.core.ai.AiDigest.estimateTokens(it.content) }
            currentHostPaths = allowedPaths(currentHost)
            currentHostPath = view?.path.orEmpty()
            val message: JsonObject = callModel(digest, conversation)
            val toolCalls = message["tool_calls"]?.jsonArray
            if (toolCalls == null || toolCalls.isEmpty()) {
                val text = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                messages = messages + AiChatMessage("assistant", text.ifBlank { "（空响应）" })
                return
            }
            // 标准工具往返：assistant 的 tool_calls 原样带回，结果按 tool_call_id 回填。
            conversation += message
            for (call in toolCalls) {
                val callObject = call.jsonObject
                val id = callObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val function = callObject["function"]?.jsonObject ?: continue
                val name = function["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val args = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = executeTool(name, args, currentHost)
                conversation += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", result)
                }
            }
            // 确认模式下一旦产生待确认编辑，就停下来等用户操作。
            if (pending != null || question != null) return
            if (round == 5) {
                error = "工具调用轮数过多，已停止"
                return
            }
        }
    }

    private fun aiLog(message: String) {
        Log.i("ComfyPilotAI", message.take(2000))
    }

    private fun executeTool(name: String, argsJson: String, currentHost: AiWorkflowHost): String {
        if (host !== currentHost) {
            aiLog("tool $name rejected: host detached")
            return """{"ok":false,"error":"工作流已关闭，请重新打开后再试"}"""
        }
        aiLog("tool $name args=${argsJson.take(300)}")
        return when (name) {
            "propose_edits" -> {
                val proposal = AiEdits.parse(argsJson)
                if (proposal == null) {
                    """{"ok":false,"error":"ops 结构不合法"}"""
                } else {
                    val view = currentHost.digest()
                    if (view == null) {
                        """{"ok":false,"error":"没有工作流上下文"}"""
                    } else {
                        val state = currentHost.editState()
                        val result = AiEdits.apply(view, state, proposal)
                        if (!result.ok) {
                            buildJsonObject {
                                put("ok", false)
                                put("errors", result.errors.joinToString("；"))
                            }.toString()
                        } else if (config.autoSubmit) {
                            val applied = currentHost.applyEdit(result.state)
                            if (!applied.ok) {
                                """{"ok":false,"errors":${quote(applied.errors.joinToString("；"))}}"""
                            } else {
                                actions = actions + result.changes.map { it.label } + "提交任务"
                                currentHost.submitCurrent()
                                messages = messages + AiChatMessage(
                                    "assistant",
                                    "已按自动提交应用并提交：${proposal.summary}",
                                )
                                """{"ok":true,"applied":true,"submitted":true}"""
                            }
                        } else {
                            pending = AiPendingEdit(proposal.summary, result.state, result.changes)
                            """{"ok":true,"pending":true,"summary":${quote(proposal.summary)}}"""
                        }
                    }
                }
            }

            "list_graphs" -> {
                val view = currentHost.digest()
                val state = currentHost.editState()
                if (view == null) """{"ok":false,"error":"没有打开工作流"}"""
                else com.comfypilot.core.ai.AiQuery.graphs(view, state).toString()
            }

            "list_nodes" -> {
                val view = currentHost.digest()
                val state = currentHost.editState()
                if (view == null) {
                    """{"ok":false,"error":"没有打开工作流"}"""
                } else {
                    val parsed = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
                    val query = parsed?.get("query")?.jsonPrimitive?.contentOrNull
                    val limit = parsed?.get("limit")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 40
                    com.comfypilot.core.ai.AiQuery.nodes(view, state, query, limit).toString()
                }
            }

            "get_node" -> {
                val view = currentHost.digest()
                val state = currentHost.editState()
                val id = runCatching {
                    json.parseToJsonElement(argsJson).jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                }.getOrNull()
                if (view == null || id == null) {
                    """{"ok":false,"error":"缺少工作流或节点 id"}"""
                } else {
                    com.comfypilot.core.ai.AiQuery.node(view, state, id)?.toString()
                        ?: """{"ok":false,"error":"节点不存在"}"""
                }
            }

            "ask_user" -> {
                val parsed = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
                val question = parsed?.get("question")?.jsonPrimitive?.contentOrNull.orEmpty()
                val options = (parsed?.get("options") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                if (question.isBlank() || options.isEmpty()) {
                    """{"ok":false,"error":"question 或 options 为空"}"""
                } else {
                    this.question = AiQuestion(question, options.take(4))
                    messages = messages + AiChatMessage("assistant", question)
                    """{"ok":true,"waiting":true}"""
                }
            }

            "submit" -> {
                actions = actions + "提交任务"
                val ok = currentHost.submitCurrent()
                """{"ok":$ok}"""
            }

            "open_workflow" -> {
                val path = runCatching {
                    json.parseToJsonElement(argsJson).jsonObject["path"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (path.isNullOrBlank()) {
                    """{"ok":false,"error":"path 为空"}"""
                } else if (path !in allowedPaths(currentHost)) {
                    """{"ok":false,"error":"该工作流不在当前 AI 工作区内"}"""
                } else {
                    actions = actions + "切换工作流：$path"
                    val ok = currentHost.openWorkflow(path)
                    """{"ok":$ok}"""
                }
            }

            else -> """{"ok":false,"error":"未知工具 $name"}"""
        }
    }

    private var job: Job? = null
    private var currentHostPaths: List<String> = emptyList()
    private var currentHostPath: String = ""

    private fun callModel(digest: JsonObject?, conversation: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", 0.2)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", AiPrompt.systemPrompt(config.model))
                    },
                )
                if (currentHostPath.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "用户当前停留的工作流：${currentHostPath}")
                        },
                    )
                }
                if (digest != null) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "当前工作流摘要：${digest}")
                        },
                    )
                }
                val paths = currentHostPaths
                if (paths.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put(
                                "content",
                                "可切换的工作流（open_workflow 用）：" +
                                    paths.take(60).joinToString("、"),
                            )
                        },
                    )
                }
                val guide = host?.promptGuide().orEmpty()
                if (guide.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "生成提示词规范（写 text/prompt 字段时必须遵守）：$guide")
                        },
                    )
                }
                conversation.forEach { add(it) }
            }
            put("tools", TOOLS)
            put("tool_choice", "auto")
        }
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?: throw IllegalStateException("响应里没有 choices")
        }
    }

    private fun quote(text: String): String =
        kotlinx.serialization.json.JsonPrimitive(text).toString()

    private val TOOLS: JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "propose_edits")
                        put("description", "对当前工作流提交一组声明式修改")
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "summary",
                                            buildJsonObject {
                                                put("type", "string")
                                                put("description", "一句话说明这次改了什么")
                                            },
                                        )
                                        put(
                                            "ops",
                                            buildJsonObject {
                                                put("type", "array")
                                                put(
                                                    "items",
                                                    buildJsonObject {
                                                        put("type", "object")
                                                        put(
                                                            "properties",
                                                            buildJsonObject {
                                                                put(
                                                                    "op",
                                                                    buildJsonObject {
                                                                        put("type", "string")
                                                                    },
                                                                )
                                                                put(
                                                                    "node",
                                                                    buildJsonObject {
                                                                        put("type", "integer")
                                                                    },
                                                                )
                                                                put(
                                                                    "input",
                                                                    buildJsonObject {
                                                                        put("type", "string")
                                                                    },
                                                                )
                                                                put(
                                                                    "enabled",
                                                                    buildJsonObject {
                                                                        put("type", "boolean")
                                                                    },
                                                                )
                                                                put(
                                                                    "graphs",
                                                                    buildJsonObject {
                                                                        put("type", "array")
                                                                        put(
                                                                            "items",
                                                                            buildJsonObject {
                                                                                put("type", "string")
                                                                            },
                                                                        )
                                                                    },
                                                                )
                                                                put(
                                                                    "path",
                                                                    buildJsonObject {
                                                                        put("type", "string")
                                                                    },
                                                                )
                                                                put("value", buildJsonObject { })
                                                            },
                                                        )
                                                        put("required", buildJsonArray { add(okJson("op")) })
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                put("required", buildJsonArray { add(okJson("summary")); add(okJson("ops")) })
                            },
                        )
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "submit")
                        put("description", "提交当前工作流")
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject { })
                            },
                        )
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "open_workflow")
                        put("description", "切换到另一个工作流")
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("path", buildJsonObject { put("type", "string") })
                                    },
                                )
                                put("required", buildJsonArray { add(okJson("path")) })
                            },
                        )
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "list_graphs")
                        put("description", "列出工作流里的节点图及选中状态")
                        put("parameters", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) })
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "list_nodes")
                        put("description", "按标题/类型/id 搜索节点，返回轻量列表")
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("query", buildJsonObject { put("type", "string") })
                                        put("limit", buildJsonObject { put("type", "integer") })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "get_node")
                        put("description", "读取单个节点的字段、当前值、COMBO 候选")
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject { put("id", buildJsonObject { put("type", "integer") }) },
                                )
                                put("required", buildJsonArray { add(okJson("id")) })
                            },
                        )
                    },
                )
            },
        )
    }

    private fun okJson(text: String): kotlinx.serialization.json.JsonElement =
        kotlinx.serialization.json.JsonPrimitive(text)
}
