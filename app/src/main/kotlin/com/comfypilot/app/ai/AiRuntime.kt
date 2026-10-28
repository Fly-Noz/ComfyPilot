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
import com.comfypilot.core.ai.AiOp
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

    /**
     * 最近任务的 JSON 摘要；[waitSeconds] > 0 时等到没有活动任务或超时。
     * 用于提交后确认结果，而不是让模型凭空说"已完成"。
     */
    fun taskSummary(waitSeconds: Int = 0): String = ""
}

data class AiChatMessage(
    val role: String,
    val content: String,
)

/** 随消息发给多模态模型的图片：dataUrl 是 data:image/jpeg;base64,... */
data class AiImage(
    val name: String,
    val dataUrl: String,
)

/** 一个历史会话：消息与操作记录各自独立，切换时不会串台。 */
data class AiSession(
    val id: String,
    var title: String,
    var messages: MutableList<AiChatMessage>,
    var actions: MutableList<String>,
    var updatedAt: Long,
)

data class AiSessionInfo(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val active: Boolean,
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
    /** 有可撤销的 AI 修改。 */
    val canUndo: Boolean = false,
)

data class AiPendingEdit(
    val summary: String,
    val state: AiEditState,
    val changes: List<AiChange>,
    /** 提案时各字段的旧值（"nodeId.input" -> 显示值），用于检测用户期间改过。 */
    val oldValues: Map<String, String> = emptyMap(),
    /** 提案时的编辑状态快照，撤销时原样写回。 */
    val baseState: AiEditState? = null,
    /** 提案时的工作流指纹（路径 + 字段数），用来判断状态是否过期。 */
    val baseSignature: String = "",
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
    private var prefs: android.content.SharedPreferences? = null

    /** 本轮要发给多模态模型的图片；只用于当前请求，不写进历史，避免越存越大。 */
    private var pendingImages: List<AiImage> = emptyList()
    private var pendingUserText: String = ""
    /** 明确拒绝过图片的模型名；下次直接跳过图片，省一次失败请求。 */
    private var visionOffModel: String = ""

    /** 上一次 AI 修改前的编辑状态；用于「撤销这次修改」。 */
    private var undoState: AiEditState? = null
    private var canUndo: Boolean = false

    /** 历史会话；当前会话的 messages/actions 在持久化时同步进来。 */
    private val sessions = mutableListOf<AiSession>()
    private var activeSessionId: String = ""

    fun attach(context: Context) {
        if (secretStore == null) {
            secretStore = AiSecretStore(context.applicationContext)
            config = secretStore!!.load()
            prefs = context.applicationContext.getSharedPreferences("ai_assistant", Context.MODE_PRIVATE)
            visionOffModel = prefs?.getString("vision_off_model", "").orEmpty()
            loadSessions()
        }
    }

    /** 恢复会话列表；老版本只有单条 messages/actions 的，迁移成一个会话。 */
    private fun loadSessions() {
        val store = prefs ?: return
        runCatching {
            val raw = store.getString("ai_sessions", null)
            if (!raw.isNullOrBlank()) {
                json.parseToJsonElement(raw).jsonArray.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "新会话"
                    val updated = obj["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    val chat = obj["messages"]?.jsonArray?.mapNotNull { entry ->
                        val msg = entry as? JsonObject ?: return@mapNotNull null
                        val role = msg["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val content = msg["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        AiChatMessage(role, content)
                    }.orEmpty()
                    val log = obj["actions"]?.jsonArray?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }.orEmpty()
                    sessions += AiSession(id, title, chat.toMutableList(), log.toMutableList(), updated)
                }
            }
        }
        if (sessions.isEmpty()) {
            // 迁移老键值；解析失败就当新会话。
            val legacyChat = runCatching {
                store.getString("ai_messages", null)?.let { raw ->
                    json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        AiChatMessage(role, content)
                    }
                }
            }.getOrNull().orEmpty()
            val legacyLog = runCatching {
                store.getString("ai_actions", null)?.let { raw ->
                    json.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                }
            }.getOrNull().orEmpty()
            sessions += AiSession(newSessionId(), titleFor(legacyChat), legacyChat.toMutableList(), legacyLog.toMutableList(), System.currentTimeMillis())
        }
        activeSessionId = sessions.first().id
        messages = sessions.first().messages.toList()
        actions = sessions.first().actions.toList()
    }

    private fun newSessionId(): String = "s" + System.currentTimeMillis().toString(36)

    private fun titleFor(chat: List<AiChatMessage>): String {
        for (message in chat) {
            if (message.role == "user" && !message.content.startsWith("（工具结果）")) {
                val text = message.content.replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) return if (text.length > 16) text.take(16) + "…" else text
            }
        }
        return "新会话"
    }

    /** 把当前 messages/actions 写回活动会话。 */
    private fun syncActiveSession() {
        val session = sessions.firstOrNull { it.id == activeSessionId } ?: return
        session.messages = messages.takeLast(40).toMutableList()
        session.actions = actions.takeLast(50).toMutableList()
        session.title = titleFor(messages)
        session.updatedAt = System.currentTimeMillis()
    }

    fun sessionInfos(): List<AiSessionInfo> {
        syncActiveSession()
        return sessions.sortedByDescending { it.updatedAt }.map {
            AiSessionInfo(it.id, it.title, it.updatedAt, it.id == activeSessionId)
        }
    }

    fun newSession() {
        syncActiveSession()
        val session = AiSession(newSessionId(), "新会话", mutableListOf(), mutableListOf(), System.currentTimeMillis())
        sessions += session
        while (sessions.size > 20) sessions.removeAt(0)
        activeSessionId = session.id
        messages = emptyList()
        actions = emptyList()
        pending = null
        question = null
        error = null
        persistConversation()
    }

    fun switchSession(id: String) {
        val target = sessions.firstOrNull { it.id == id } ?: return
        if (id == activeSessionId) return
        syncActiveSession()
        activeSessionId = id
        messages = target.messages.toList()
        actions = target.actions.toList()
        pending = null
        question = null
        error = null
        persistConversation()
    }

    fun deleteSession(id: String) {
        val remaining = sessions.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            sessions.clear()
            activeSessionId = ""
            newSession()
            return
        }
        sessions.clear()
        sessions += remaining
        if (activeSessionId == id) {
            val next = remaining.last()
            activeSessionId = next.id
            messages = next.messages.toList()
            actions = next.actions.toList()
            pending = null
            question = null
            error = null
        }
        persistConversation()
    }

    private fun persistConversation() {
        val store = prefs ?: return
        runCatching {
            syncActiveSession()
            val array = buildJsonArray {
                sessions.takeLast(20).forEach { session ->
                    add(
                        buildJsonObject {
                            put("id", session.id)
                            put("title", session.title)
                            put("updatedAt", session.updatedAt)
                            putJsonArray("messages") {
                                session.messages.forEach { message ->
                                    add(
                                        buildJsonObject {
                                            put("role", message.role)
                                            put("content", message.content)
                                        },
                                    )
                                }
                            }
                            putJsonArray("actions") {
                                session.actions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            }
                        },
                    )
                }
            }
            store.edit().putString("ai_sessions", array.toString()).apply()
        }
    }

    fun saveConfig(next: AiConfig) {
        config = next
        secretStore?.save(next)
    }

    /** 权限开关：每次改动先看 diff，还是让 AI 直接改并提交。 */
    fun toggleAutoSubmit(): Boolean {
        config = config.copy(autoSubmit = !config.autoSubmit)
        secretStore?.save(config)
        return config.autoSubmit
    }

    fun resetConversation() {
        messages = emptyList()
        pending = null
        error = null
        tokenEstimate = 0
        persistConversation()
    }

    fun clearError() {
        error = null
    }

    fun snapshot(): AiSnapshot =
        AiSnapshot(actions, messages, pending, question, busy, error, tokenEstimate, config, canUndo)

    fun send(prompt: String, images: List<AiImage> = emptyList()) {
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
        aiLog("send: $text images=${images.size}")
        messages = messages + AiChatMessage("user", text)
        pending = null
        error = null
        busy = true
        // 上次这个模型明确拒绝过图片，就不再重复撞墙，只发文件名。
        pendingImages = if (visionOffModel == config.model) emptyList() else images
        pendingUserText = text
        persistConversation()
        job = scope.launch {
            try {
                runLoop(currentHost)
            } catch (err: Throwable) {
                error = err.message ?: "AI 请求失败"
            } finally {
                busy = false
                persistConversation()
            }
        }
    }

    fun confirmPending(submit: Boolean) {
        val edit = pending ?: return
        val currentHost = host ?: return
        val view = currentHost.digest()
        if (view == null) {
            pending = null
            error = "工作流已关闭，请重新打开后再试"
            return
        }
        // 状态过期：路径或字段数变了，说明工作流不是生成提案时那一份。
        if (signatureOf(view) != edit.baseSignature) {
            pending = null
            error = "工作流在你等待期间变了，这次修改已作废，请让 AI 重新生成"
            return
        }
        // 同键冲突：用户在等待期间自己改过这些字段，跳过它们，别把人手改的覆盖掉。
        val current = currentHost.editState()
        val conflicts = edit.oldValues.filterKeys { key ->
            val parts = key.split('.')
            val nodeId = parts.getOrNull(0)?.toIntOrNull()
            if (nodeId == null || parts.size < 2) false
            else currentValueText(view, current, nodeId, parts[1]) != edit.oldValues[key]
        }.keys
        var stateToApply = edit.state
        if (conflicts.isNotEmpty()) {
            val filtered = stateToApply.overrides.mapValues { (nodeId, fields) ->
                fields.filterKeys { input -> "$nodeId.$input" !in conflicts }
            }.filterValues { it.isNotEmpty() }
            stateToApply = stateToApply.copy(overrides = filtered)
            if (filtered.isEmpty() && stateToApply.bypassed == edit.baseState?.bypassed &&
                stateToApply.enabled == edit.baseState?.enabled
            ) {
                pending = null
                error = "这些字段你都改过了，本次修改已全部跳过"
                return
            }
        }
        pending = null
        val result = currentHost.applyEdit(stateToApply)
        if (!result.ok) {
            error = result.errors.joinToString("\n")
            return
        }
        // 保障层：回读校验只做「同步可读」的部分。
        // 节点开关走 SessionState，回调是异步的，立刻回读会读到旧值——
        // 之前因此把成功误报成「改动没有生效」。
        val applied = currentHost.editState()
        val dropped = mutableListOf<String>()
        stateToApply.overrides.forEach { (nodeId, fields) ->
            fields.forEach { (input, value) ->
                if (applied.overrides[nodeId]?.get(input) != value) {
                    dropped += "#$nodeId.$input"
                }
            }
        }
        if (stateToApply.graphs != null && applied.graphs != stateToApply.graphs) {
            dropped += "节点图选择"
        }
        if (dropped.isNotEmpty()) {
            error = "这些改动没有生效：${dropped.joinToString()}（工作流可能已刷新，请重试）"
        }
        undoState = edit.baseState
        canUndo = edit.baseState != null
        actions = actions + edit.changes.map { it.label }
        messages = messages + AiChatMessage(
            "assistant",
            if (conflicts.isEmpty()) {
                "已应用：${edit.summary}"
            } else {
                "已应用：${edit.summary}（跳过你期间改过的：${conflicts.joinToString()}）"
            },
        )
        if (submit) {
            if (!currentHost.submitCurrent()) {
                error = "提交失败：请检查工作流是否有未填字段"
            } else {
                messages = messages + AiChatMessage("assistant", "已提交任务，等待结果。")
            }
        }
        persistConversation()
    }

    /** 撤销上一次 AI 修改：把提案前的编辑状态原样写回。 */
    fun undoLast(): Boolean {
        val currentHost = host ?: return false
        val state = undoState ?: return false
        undoState = null
        canUndo = false
        val result = currentHost.applyEdit(state)
        if (!result.ok) {
            error = "撤销失败：${result.errors.joinToString("；")}"
            return false
        }
        actions = actions + "撤销上一次修改"
        messages = messages + AiChatMessage("assistant", "已撤销上一次 AI 修改")
        persistConversation()
        return true
    }

    private fun signatureOf(view: AiWorkflowView): String =
        "${view.path}#${view.nodes.sumOf { it.fields.size }}"

    private fun currentValueText(
        view: AiWorkflowView,
        state: AiEditState,
        nodeId: Int,
        input: String,
    ): String {
        val field = view.field(nodeId, input) ?: return ""
        return state.valueOf(field)?.toString().orEmpty()
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
                persistConversation()
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
        conversation += userTurn(pendingUserText, pendingImages)
        repeat(6) { round ->
            val state = currentHost.editState()
            val digest = view?.let { com.comfypilot.core.ai.AiDigest.summary(it, state) }
            tokenEstimate = (digest?.let { com.comfypilot.core.ai.AiDigest.estimateTokens(it) } ?: 0) +
                messages.sumOf { com.comfypilot.core.ai.AiDigest.estimateTokens(it.content) }
            currentHostPaths = allowedPaths(currentHost)
            currentHostPath = view?.path.orEmpty()
            val message: JsonObject = try {
                callModel(digest, conversation)
            } catch (err: Throwable) {
                // 模型不认图片时退回纯文本，并记住这个模型，下次不再带图。
                if (round == 0 && pendingImages.isNotEmpty()) {
                    aiLog("vision rejected: ${err.message}")
                    visionOffModel = config.model
                    prefs?.edit()?.putString("vision_off_model", visionOffModel)?.apply()
                    pendingImages = emptyList()
                    conversation[0] = userTurn(pendingUserText, emptyList())
                    messages = messages + AiChatMessage(
                        "assistant",
                        "（当前模型不接受图片输入，已改用文件名描述继续；要让它看图，请在设置里换支持视觉的模型）",
                    )
                    callModel(digest, conversation)
                } else {
                    throw err
                }
            }
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
        val line = message.take(2000)
        Log.i("ComfyPilotAI", line)
        // 同一批事件也进 App 内诊断页，装不了电脑时能直接在手机上看。
        com.comfypilot.app.DiagnosticsLog.append("ai $line")
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
                            // 记下提案时的旧值与基线状态：确认时检测「用户期间改过」，
                            // 撤销时原样写回。
                            val oldValues = proposal.ops.mapNotNull { op ->
                                (op as? AiOp.SetInput)?.let {
                                    "${it.nodeId}.${it.input}" to
                                        currentValueText(view, state, it.nodeId, it.input)
                                }
                            }.toMap()
                            pending = AiPendingEdit(
                                summary = proposal.summary,
                                state = result.state,
                                changes = result.changes,
                                oldValues = oldValues,
                                baseState = state,
                                baseSignature = signatureOf(view),
                            )
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

            "lookup_prompt_guide" -> {
                val model = runCatching {
                    json.parseToJsonElement(argsJson).jsonObject["model"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (model.isNullOrBlank()) """{"ok":false,"error":"model 为空"}"""
                else AiLookup.lookupPromptGuide(model)
            }

            "fetch_doc" -> {
                val url = runCatching {
                    json.parseToJsonElement(argsJson).jsonObject["url"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (url.isNullOrBlank()) """{"ok":false,"error":"url 为空"}"""
                else AiLookup.fetchDoc(url)
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
                if (ok) {
                    """{"ok":true,"queued":true,"hint":"用 task_status（wait_seconds 例如 30）确认真实结果，别直接说已完成"}"""
                } else {
                    """{"ok":false,"error":"工作流还有未填字段或未就绪"}"""
                }
            }

            "task_status" -> {
                val wait = runCatching {
                    json.parseToJsonElement(argsJson).jsonObject["wait_seconds"]
                        ?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                }.getOrNull() ?: 0
                currentHost.taskSummary(wait.coerceIn(0, 120))
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

    /** 用户这一轮的消息：有图时按 OpenAI 多模态格式给 content 数组。 */
    private fun userTurn(text: String, images: List<AiImage>): JsonObject = buildJsonObject {
        put("role", "user")
        if (images.isEmpty()) {
            put("content", text)
        } else {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
                images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put(
                                "image_url",
                                buildJsonObject { put("url", image.dataUrl) },
                            )
                        },
                    )
                }
            }
        }
    }

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
            // 工具声明由共享层提供，两端不再各写一份。
            put("tools", json.parseToJsonElement(com.comfypilot.core.ai.AiToolSpec.json))
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
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", "task_status")
                        put(
                            "description",
                            "查看最近任务的状态/进度/产物；wait_seconds 可等待任务跑完（最多 120 秒）",
                        )
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("wait_seconds", buildJsonObject { put("type", "integer") })
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
                        put("name", "lookup_prompt_guide")
                        put(
                            "description",
                            "搜索某个生成模型的提示词规范，返回标题/链接/摘要；挑一个再用 fetch_doc 读正文",
                        )
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "model",
                                            buildJsonObject {
                                                put("type", "string")
                                                put("description", "模型名或文件名，例如 flux1-dev、Qwen-Image")
                                            },
                                        )
                                    },
                                )
                                put("required", buildJsonArray { add(okJson("model")) })
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
                        put("name", "fetch_doc")
                        put("description", "抓取一个 https 文档地址的正文；用于读官方 README/提示词指南")
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("url", buildJsonObject { put("type", "string") })
                                    },
                                )
                                put("required", buildJsonArray { add(okJson("url")) })
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
