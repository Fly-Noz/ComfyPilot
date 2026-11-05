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
import com.comfypilot.core.ai.AiDigest
import com.comfypilot.core.ai.AiDoneReason
import com.comfypilot.core.ai.AiEditState
import com.comfypilot.core.ai.AiEdits
import com.comfypilot.core.ai.AiLoopContext
import com.comfypilot.core.ai.AiLoopState
import com.comfypilot.core.ai.AiLoopStep
import com.comfypilot.core.ai.AiOp
import com.comfypilot.core.ai.AiQuery
import com.comfypilot.core.ai.AiStreamAssembler
import com.comfypilot.core.ai.AiToolEnv
import com.comfypilot.core.ai.AiToolOutcome
import com.comfypilot.core.ai.AiToolRouter
import com.comfypilot.core.ai.AiWorkflowView
import com.comfypilot.core.api.ImageUpload
import com.comfypilot.core.api.ImageUploadResult
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.app.SessionHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val apiKey: String = "",
    /** 总开关：关闭后悬浮球不出现，也不会发起请求。 */
    val enabled: Boolean = false,
    /** true：AI 改完直接应用并提交；false：先展示 diff 等用户确认。 */
    val autoSubmit: Boolean = false,
    /** 单轮最多几次模型往返；设置页可调，默认 8。 */
    val maxRounds: Int = 8,
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

/**
 * 对话里的一条消息。
 *
 * [content] 是**发给模型**的正文（附图时末尾带「附了 N 张图…」这类给模型的说明）；
 * [display] 是气泡里给用户看的正文，为空时用 [content]。两者分开，是因为内部说明
 * 不该出现在用户眼前，但模型又必须看到。
 */
data class AiChatMessage(
    val role: String,
    val content: String,
    val display: String? = null,
)

/**
 * 随消息发给多模态模型的图片。
 *
 * [name] 是原文件名（含扩展名，作展示与去重用）；[dataUrl] 是给模型的 data:image/...;base64；
 * [uploadBytes] 是「等 AI 决定要上传时」才发出去的字节——选图时不传，由 `upload_image`
 * 工具在模型真正要把图写进节点的那一刻上传。
 *
 * 注意：这是 data class 但 [uploadBytes] 是数组，`equals` 按引用比。查找一律按 [name]，
 * 不要拿两张图做相等判断。
 */
data class AiImage(
    val name: String,
    val dataUrl: String,
    val uploadBytes: ByteArray? = null,
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
    /** 当前正在做什么（"请求模型" / "执行 get_node"），替代三个点。 */
    val activity: String = "",
    /** 流式输出到一半的正文（整段模式下为空）。 */
    val streaming: String = "",
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

    fun load(): AiConfig {
        val defaults = AiConfig()
        // 旧默认值 deepseek-chat 已被官方下线：没改过模型的用户自动升到 deepseek-flash。
        val storedModel = prefs.getString("model", null) ?: defaults.model
        val model = if (storedModel == "deepseek-chat") "deepseek-flash" else storedModel
        if (model != storedModel) prefs.edit().putString("model", model).apply()
        return AiConfig(
            baseUrl = prefs.getString("base_url", null) ?: defaults.baseUrl,
            model = model,
            apiKey = decrypt(prefs.getString("api_key", null).orEmpty()),
            enabled = prefs.getBoolean("enabled", false),
            autoSubmit = prefs.getBoolean("auto_submit", false),
            maxRounds = prefs.getInt("max_rounds", 8),
        )
    }

    fun save(config: AiConfig) {
        prefs.edit()
            .putString("base_url", config.baseUrl.trim())
            .putString("model", config.model.trim())
            .putString("api_key", encrypt(config.apiKey.trim()))
            .putBoolean("enabled", config.enabled)
            .putBoolean("auto_submit", config.autoSubmit)
            .putInt("max_rounds", config.maxRounds.coerceIn(2, 20))
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

    /**
     * 本次运行里出现过的附图原名。
     *
     * 模型能从历史消息里再看到这些名字，然后在后续轮次直接写进 image 字段——那时
     * [pendingImages] 已经是空的，「本轮附图」那道拦网就漏了。这里按会话记忆，
     * 只要没上传就一律拒绝。
     */
    private val knownLocalImages = mutableSetOf<String>()
    private var pendingUserText: String = ""
    /** 本轮已上传过的附图（原文件名 → 服务器文件名）；避免同一张图重复上传。 */
    private val uploadedRefs = mutableMapOf<String, String>()
    /** 明确拒绝过图片的模型名；下次直接跳过图片，省一次失败请求。 */
    private var visionOffModel: String = ""

    /** 当前步骤（给界面替代三个点）：请求模型 / 执行某个工具。 */
    private var activity: String = ""

    /** 流式输出到一半的正文；整段模式下始终为空。 */
    private var streamingText: String = ""

    /** 界面订阅：运行时在协程里改了状态就推一份新快照过去。 */
    var onChange: (() -> Unit)? = null

    /** 没打开工作流时的宿主（只负责切流）；由应用根组件注册，详情页关闭时回落。 */
    var fallbackHost: AiWorkflowHost? = null

    /**
     * 聊天里点「定位 #id」时置位；工作流详情页订阅后展开并滚动到该节点，然后清空。
     *
     * 用 Flow 而不是普通字段：详情页是 Compose，需要可观察的信号。
     */
    val locateRequests = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    fun requestLocate(nodeId: Int) {
        locateRequests.value = nodeId
    }

    private fun notifyUi() {
        onChange?.invoke()
    }

    /** 上一次 AI 修改前的编辑状态；用于「撤销这次修改」。 */
    private val undoStack = ArrayDeque<AiEditState>()
    private var canUndo: Boolean = false

    /** 历史会话；当前会话的 messages/actions 在持久化时同步进来。 */
    private val sessions = mutableListOf<AiSession>()
    private var activeSessionId: String = ""

    /**
     * 草稿会话：每次启动都开一个新对话，但用户真正发第一条消息之前不写进 [sessions]。
     *
     * 之前的做法是启动就把「新会话」建出来落盘，反复打开又不用，历史里会堆一串空会话。
     * 现在草稿只活在内存里：发消息时 [materializeDraft] 才把它转正并参与持久化。
     */
    private var draftSessionId: String = ""

    fun attach(context: Context) {
        if (secretStore == null) {
            secretStore = AiSecretStore(context.applicationContext)
            // 功能未开放时把 enabled 压成 false：老库里存着 true 的也不会亮起入口。
            config = AiFeature.gate(secretStore!!.load())
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
                        val display = msg["display"]?.jsonPrimitive?.contentOrNull
                        AiChatMessage(role, content, display)
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
        // 不再把上一轮会话顶到前台：每次打开应用都是新的一轮，历史留在列表里。
        startDraft()
    }

    /** 开一个新对话，先不落盘；用户发第一条消息时才加入历史。 */
    private fun startDraft() {
        draftSessionId = newSessionId()
        activeSessionId = draftSessionId
        messages = emptyList()
        actions = emptyList()
        pending = null
        question = null
        error = null
    }

    /** 用户真的用了这轮对话：把它转正，之后才会被持久化。 */
    private fun materializeDraft() {
        if (draftSessionId.isEmpty()) return
        sessions += AiSession(
            draftSessionId,
            titleFor(messages),
            messages.takeLast(40).toMutableList(),
            actions.takeLast(50).toMutableList(),
            System.currentTimeMillis(),
        )
        while (sessions.size > 20) sessions.removeAt(0)
        draftSessionId = ""
        // 转正这一刻就落盘：否则进程在下一轮请求前被杀，这条刚开头的记录会丢。
        persistConversation()
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
        val saved = sessions.sortedByDescending { it.updatedAt }.map {
            AiSessionInfo(it.id, it.title, it.updatedAt, it.id == activeSessionId)
        }
        // 草稿虽然还没落盘，但它就是「当前这一轮」，列表里不出现的话看起来一个都没选中。
        if (draftSessionId.isEmpty()) return saved
        return listOf(AiSessionInfo(draftSessionId, "新会话", System.currentTimeMillis(), true)) + saved
    }

    /** 新对话同样先当草稿：没聊就走，历史里不会多出一条空的。 */
    fun newSession() {
        syncActiveSession()
        startDraft()
        persistConversation()
    }

    fun switchSession(id: String) {
        val target = sessions.firstOrNull { it.id == id } ?: return
        if (id == activeSessionId) return
        syncActiveSession()
        // 离开草稿就丢掉它：它本来就没落盘，也没有内容可留。
        draftSessionId = ""
        activeSessionId = id
        messages = target.messages.toList()
        actions = target.actions.toList()
        pending = null
        question = null
        error = null
        persistConversation()
    }

    fun deleteSession(id: String) {
        if (id == draftSessionId) {
            // 删的是还没用过的新对话：重新开一个草稿就行，历史不用动。
            startDraft()
            return
        }
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
                                            message.display?.let { put("display", it) }
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
        val gated = AiFeature.gate(next)
        config = gated
        secretStore?.save(gated)
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
        AiSnapshot(actions, messages, pending, question, busy, error, tokenEstimate, config, canUndo, activity, streamingText)

    /**
     * 发一轮对话。
     *
     * [prompt] 是发给模型的正文；[displayText] 是气泡里显示的内容（附图时去掉内部说明）。
     */
    fun send(prompt: String, images: List<AiImage> = emptyList(), displayText: String? = null) {
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
        val currentHost = host ?: fallbackHost
        if (currentHost == null) {
            error = "AI 还没初始化完成，请稍后再试"
            return
        }
        aiLog("send: $text images=${images.size}")
        // content 给模型（可能带附图说明），display 给气泡；两者分开，内部说明不上屏。
        messages = messages + AiChatMessage("user", text, displayText)
        // 第一条用户消息落地，这轮对话才算真的用过，转正并参与持久化。
        materializeDraft()
        pending = null
        error = null
        busy = true
        // 上次这个模型明确拒绝过图片，就不再重复撞墙，只发文件名。
        pendingImages = if (visionOffModel == config.model) emptyList() else images
        images.forEach { image -> knownLocalImages += image.name }
        uploadedRefs.clear()
        pendingUserText = text
        persistConversation()
        job = scope.launch {
            try {
                runLoop(currentHost, historyForModel(messages.dropLast(1)))
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
        // 按工作流记住最近改过什么：下次进来模型不用重新问一遍背景。
        runCatching {
            val key = "ai_wf_mem_${view.path.hashCode()}"
            val previous = prefs?.getString(key, null).orEmpty()
            val merged = (listOf(edit.summary) + previous.split('\n'))
                .filter { it.isNotBlank() }
                .distinct()
                .take(5)
                .joinToString("\n")
            prefs?.edit()?.putString(key, merged)?.apply()
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
        edit.baseState?.let { base ->
            undoStack.addLast(base)
            while (undoStack.size > 10) undoStack.removeFirst()
        }
        canUndo = undoStack.isNotEmpty()
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
                messages = messages + AiChatMessage("assistant", "已提交任务，等待结果…")
                persistConversation()
                // 确认后要自己盯着这次任务：不能只丢一句「已提交」就没动静。
                job = scope.launch {
                    busy = true
                    activity = "等待任务结果…"
                    notifyUi()
                    val task = runCatching { currentHost.taskSummary(60) }.getOrDefault("[]")
                    busy = false
                    activity = ""
                    val report = taskReport(task)
                    messages = messages + AiChatMessage("assistant", report.text)
                    if (report.failed) {
                        error = null
                        aiLog("task failed: ${report.detail.take(200)}")
                    }
                    persistConversation()
                    notifyUi()
                }
            }
        } else {
            // 只应用不提交时也要给个交代，否则界面就停在「已应用」不动了。
            messages = messages + AiChatMessage(
                "assistant",
                "（没有提交）要跑的话点下方「提交任务」，或对我说「提交」，我会盯着这次结果。",
            )
        }
        persistConversation()
    }

    /**
     * 把 taskSummary 的 JSON 翻成一句话。
     *
     * 成功给出产物文件名，失败给出服务端错误并提示可以让我接着修——之前这里只留
     * 一句「已提交」，任务失败时 AI 和用户都收不到结果。
     */
    private fun taskReport(taskJson: String): TaskReport {
        val last = runCatching { json.parseToJsonElement(taskJson).jsonArray.lastOrNull() }.getOrNull()
            ?: return TaskReport(false, "任务状态未知：没有拿到任务信息")
        val obj = last as? JsonObject ?: return TaskReport(false, "任务状态未知")
        val state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val outputs = (obj["outputs"] as? JsonArray)?.mapNotNull { item ->
            (item as? JsonObject)?.get("filename")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val failed = error.isNotBlank() || state.contains("fail", true) || state.contains("error", true)
        return if (failed) {
            TaskReport(true, "任务失败：${error.ifBlank { state }}。要我现在定位并修吗？说「修」我就继续。", error)
        } else if (state.contains("success", true) || state.contains("complete", true)) {
            val names = outputs.take(3).joinToString("、")
            TaskReport(false, if (names.isEmpty()) "任务完成。" else "任务完成，产物：$names")
        } else {
            TaskReport(false, "任务还在跑（$state），完成后我再报。")
        }
    }

    private data class TaskReport(val failed: Boolean, val text: String, val detail: String = "")

    /** 撤销上一次 AI 修改：把提案前的编辑状态原样写回。 */
    fun undoLast(): Boolean {
        val currentHost = host ?: return false
        val state = undoStack.removeLastOrNull() ?: return false
        val result = currentHost.applyEdit(state)
        if (!result.ok) {
            // 撤销失败时把状态放回去，别丢。
            undoStack.addLast(state)
            error = "撤销失败：${result.errors.joinToString("；")}"
            return false
        }
        canUndo = undoStack.isNotEmpty()
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
        // 新一轮必须以「回答」作为用户输入。之前这里沿用了上一条原始请求，
        // 模型看到的还是旧问题，于是反复追问同一件事。
        pendingUserText = answer
        pendingImages = emptyList()
        val currentHost = host ?: fallbackHost ?: return
        busy = true
        job = scope.launch {
            try {
                runLoop(currentHost, historyForModel(messages.dropLast(1)))
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
        activity = ""
        notifyUi()
        error = "已停止"
    }

    /**
     * 一轮用户输入对应的工具循环。状态机在共享层（[AiLoopState]），这里只做三件事：
     * 发请求、执行工具、把结果交回去。
     */
    private suspend fun runLoop(
        currentHost: AiWorkflowHost,
        history: List<JsonObject>,
    ) {
        val loop = AiLoopState(maxRounds = config.maxRounds.coerceIn(2, 20))
        loop.start(userTurn(pendingUserText, pendingImages), history)
        var step: AiLoopStep = loop.nextRequest(contextFor(currentHost))
        while (true) {
            when (step) {
                is AiLoopStep.Http -> {
                    activity = "第 ${loop.round} 轮 · 请求模型…"
                    notifyUi()
                    // 每一轮都用「当前」宿主：open_workflow 之后详情页会换掉 host，
                    // 继续用开跑时捕获的那个会被 toolkit 当成「工作流已关闭」。
                    val active = host ?: fallbackHost ?: currentHost
                    val message = try {
                        postModelWithRetry(step.body)
                    } catch (err: Throwable) {
                        // 模型不认图片时退回纯文本，并记住这个模型，下次不再带图。
                        if (loop.round == 1 && pendingImages.isNotEmpty()) {
                            aiLog("vision rejected: ${err.message}")
                            visionOffModel = config.model
                            prefs?.edit()?.putString("vision_off_model", visionOffModel)?.apply()
                            // 只丢「给模型看」的那份；uploadBytes 留着，
                            // 模型看不见图不代表不能用 upload_image 把图送进 LoadImage。
                            pendingImages = pendingImages.map { it.copy(dataUrl = "") }
                            loop.replaceUserTurn(userTurn(pendingUserText, emptyList()))
                            messages = messages + AiChatMessage(
                                "assistant",
                                "（当前模型不接受图片输入，已改用文件名描述继续；要让它看图，请在设置里换支持视觉的模型）",
                            )
                            step = loop.nextRequest(contextFor(host ?: fallbackHost ?: currentHost))
                            continue
                        }
                        throw err
                    }
                    step = loop.acceptReply(message)
                }

                is AiLoopStep.CallTool -> {
                    activity = "执行 ${step.name}…"
                    notifyUi()
                    val active = host ?: fallbackHost ?: currentHost
                    val result = executeTool(step.name, step.args, active)
                    // 工具执行会改 pending / question；循环靠这两个标志决定停不停。
                    if (pending != null) loop.markPending()
                    question?.let { loop.markQuestion(it.question) }
                    step = loop.acceptToolResult(step.callId, result, contextFor(host ?: fallbackHost ?: active))
                }

                is AiLoopStep.Done -> {
                    finishLoop(step)
                    return
                }
            }
        }
    }

    private fun finishLoop(step: AiLoopStep.Done) {
        activity = ""
        notifyUi()
        when (step.reason) {
            AiDoneReason.ANSWER ->
                messages = messages + AiChatMessage("assistant", step.text.orEmpty().ifBlank { "（空响应）" })

            AiDoneReason.ROUND_LIMIT, AiDoneReason.ERROR ->
                error = step.text ?: "AI 请求失败"

            // PENDING / QUESTION：界面已经有待确认提案或问题，这里不用再说话；
            // STOPPED：stop() 已经写了「已停止」。
            AiDoneReason.PENDING, AiDoneReason.QUESTION, AiDoneReason.STOPPED -> Unit
        }
    }

    /**
     * 每一轮请求的上下文。摘要必须每轮重算：模型可能刚切过工作流或改过字段，
     * 沿用上一轮的摘要会让它对着旧值改。
     */
    private fun contextFor(currentHost: AiWorkflowHost): AiLoopContext {
        val view = currentHost.digest()
        val state = currentHost.editState()
        val digest = view?.let { AiDigest.summary(it, state) }
        tokenEstimate = (digest?.let { AiDigest.estimateTokens(it) } ?: 0) +
            messages.sumOf { AiDigest.estimateTokens(it.content) }
        val path = view?.path.orEmpty()
        val paths = allowedPaths(currentHost)
        val guide = currentHost.promptGuide()
        val system = buildList {
            add(AiPrompt.systemPrompt(config.model))
            if (path.isNotEmpty()) add("用户当前停留的工作流：$path")
            if (digest != null) add("当前工作流摘要：$digest")
            else add("当前没有打开工作流：要改东西就先 open_workflow 打开一个；不确定用户要哪个就先问。")
            if (paths.isNotEmpty()) {
                add("可切换的工作流（open_workflow 用）：" + paths.take(60).joinToString("、"))
            }
            if (guide.isNotEmpty()) {
                add("生成提示词规范（写 text/prompt 字段时必须遵守）：$guide")
            }
            if (path.isNotEmpty()) {
                val memory = prefs?.getString("ai_wf_mem_${path.hashCode()}", null)
                if (!memory.isNullOrBlank()) {
                    add("这个工作流最近由你改过（仅供参考，不要重复追问）：$memory")
                }
            }
        }
        return AiLoopContext(model = config.model, system = system)
    }

    /** 发给模型的历史：最近 16 条、约 6000 字预算，从旧到新裁剪。 */
    private fun historyForModel(history: List<AiChatMessage>): List<JsonObject> {
        val usable = history.filter { it.role == "user" || it.role == "assistant" }
        val kept = ArrayDeque<JsonObject>()
        var chars = 0
        for (message in usable.asReversed()) {
            val content = message.content
            if (kept.isNotEmpty() && chars + content.length > 6000) break
            kept.addFirst(
                buildJsonObject {
                    put("role", message.role)
                    put("content", content)
                },
            )
            chars += content.length
        }
        return kept.toList()
    }

    /** 网络类 / 5xx / 429 自动重试一次；其余错误原样抛出，避免重复扣费。 */
    private fun postModelWithRetry(payload: String): JsonObject {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                return postModel(payload)
            } catch (err: Throwable) {
                last = err
                val message = (err.message ?: "").lowercase()
                val retryable = err is java.io.IOException ||
                    listOf("timeout", "connection", "502", "503", "504", "429").any { message.contains(it) }
                if (attempt == 0 && retryable) {
                    aiLog("模型请求失败，重试一次：${err.message}")
                    Thread.sleep(800)
                } else {
                    throw err
                }
            }
        }
        throw last ?: IllegalStateException("模型请求失败")
    }

    private fun aiLog(message: String) {
        val line = message.take(2000)
        Log.i("ComfyPilotAI", line)
        // 同一批事件也进 App 内诊断页，装不了电脑时能直接在手机上看。
        com.comfypilot.app.DiagnosticsLog.append("ai $line")
    }

    /**
     * 把现有宿主能力包成共享路由器要的接口。
     *
     * Android 的宿主能力就在本进程里，所以每个方法都能立刻给出 [AiToolOutcome.Value]；
     * 鸿蒙那边同样的接口会返回 `CallHost` / `Fetch`，由 ArkTS 执行后再回填。
     */
    private fun toolEnv(currentHost: AiWorkflowHost): AiToolEnv =
        object : AiToolEnv {
            override fun allowedPaths(): List<String> = this@AiRuntime.allowedPaths(currentHost)

            override fun submit(): AiToolOutcome {
                val ok = currentHost.submitCurrent()
                if (!ok) return AiToolOutcome.Value("""{"ok":false}""")
                // 操作记录跟着提交走：路由器的 submit 分支只拿结果，记一笔得在这里。
                actions = actions + "提交任务"
                // 提交后等一会儿结果：成功模型就能收尾，失败能拿着 error 去修，不必再猜。
                val task = runCatching { currentHost.taskSummary(45) }.getOrDefault("[]")
                return AiToolOutcome.Value("""{"ok":true,"task":$task}""")
            }

            override fun openWorkflow(path: String): AiToolOutcome {
                val ok = currentHost.openWorkflow(path)
                if (ok) actions = actions + "切换工作流：$path"
                return AiToolOutcome.Value("""{"ok":$ok}""")
            }

            override fun queryGraphs(): AiToolOutcome {
                val view = currentHost.digest()
                    ?: return AiToolOutcome.Value("""{"ok":false,"error":"没有打开工作流"}""")
                return AiToolOutcome.Value(AiQuery.graphs(view, currentHost.editState()).toString())
            }

            override fun queryNodes(argsJson: String): AiToolOutcome {
                val view = currentHost.digest()
                    ?: return AiToolOutcome.Value("""{"ok":false,"error":"没有打开工作流"}""")
                val state = currentHost.editState()
                val parsed = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
                val query = parsed?.get("query")?.jsonPrimitive?.contentOrNull
                val limit = parsed?.get("limit")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 40
                return AiToolOutcome.Value(AiQuery.nodes(view, state, query, limit).toString())
            }

            override fun queryNode(argsJson: String): AiToolOutcome {
                val view = currentHost.digest()
                    ?: return AiToolOutcome.Value("""{"ok":false,"error":"没有打开工作流"}""")
                val id = runCatching {
                    json.parseToJsonElement(argsJson).jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                }.getOrNull() ?: return AiToolOutcome.Value("""{"ok":false,"error":"缺少节点 id"}""")
                return AiToolOutcome.Value(
                    AiQuery.node(view, currentHost.editState(), id)?.toString()
                        ?: """{"ok":false,"error":"节点不存在"}""",
                )
            }

            override fun taskSummary(waitSeconds: Int): AiToolOutcome =
                AiToolOutcome.Value(currentHost.taskSummary(waitSeconds))

            override fun lookupPromptGuide(model: String): AiToolOutcome =
                AiToolOutcome.Value(AiLookup.lookupPromptGuide(model))

            override fun fetchDoc(url: String): AiToolOutcome =
                AiToolOutcome.Value(AiLookup.fetchDoc(url))

            override fun hostStatus(): AiToolOutcome = AiToolOutcome.Value(
                runCatching { getComfy("/system_stats") }.getOrElse {
                    """{"ok":false,"error":"读不到主机状态：${it.message?.take(80)}"}"""
                },
            )

            override fun modelOptions(loaderClass: String): AiToolOutcome {
                val path = if (loaderClass.isEmpty()) "/embeddings" else "/object_info/$loaderClass"
                return AiToolOutcome.Value(
                    runCatching { getComfy(path) }.getOrElse {
                        """{"ok":false,"error":"读不到模型列表：${it.message?.take(80)}"}"""
                    },
                )
            }
        }

    /**
     * GET 一个 ComfyUI 的只读端点。
     *
     * host_status / list_models 打的是 ComfyUI 自己的路由，不走模型接口，所以不带
     * Authorization——ComfyUI 默认没有鉴权，真加了反向代理认证也不是这个 key 管用。
     * 地址是 [SessionHost.baseUrl]（连的是哪台 ComfyUI），不是模型接口地址。
     */
    private fun comfyBase(): String? {
        // 不能用 SessionHost.baseUrl 判空：它有默认值（10.0.2.2:8188），未连接时也非空。
        // 以会话租约为准——没有 lease 就是没连上，直接告诉模型，别拿默认地址去撞超时。
        if (SessionHost.state.value.lease == null) return null
        return SessionHost.baseUrl.trimEnd('/').takeIf { it.isNotEmpty() }
    }

    private fun getComfy(path: String): String {
        val base = comfyBase() ?: return """{"ok":false,"error":"还没连接 ComfyUI"}"""
        val request = Request.Builder()
            .url(base + path)
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            text
        }
    }

    private suspend fun executeTool(name: String, argsJson: String, currentHost: AiWorkflowHost): String {
        if (host !== currentHost) {
            aiLog("tool $name rejected: host detached")
            return """{"ok":false,"error":"工作流已关闭，请重新打开后再试"}"""
        }
        aiLog("tool $name args=${argsJson.take(300)}")
        if (name == "open_workflow") {
            val path = runCatching {
                json.parseToJsonElement(argsJson).jsonObject["path"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty().trim()
            if (path.isBlank()) return """{"ok":false,"error":"path 为空"}"""
            if ((host ?: currentHost).digest()?.path == path) {
                return """{"ok":true,"already":true}"""
            }
            if (!currentHost.openWorkflow(path)) {
                return """{"ok":false,"error":"打不开这个工作流（不在可用列表里？）"}"""
            }
            actions = actions + "切换工作流：$path"
            // 详情页换视图是异步的：等摘要里出现目标路径再回话，否则模型会反复 open。
            repeat(30) {
                delay(300)
                val fresh = host ?: fallbackHost
                if (fresh != null && fresh.digest()?.path == path) {
                    return """{"ok":true,"loaded":true}"""
                }
            }
            return """{"ok":true,"loading":true,"hint":"工作流还在加载；稍后再查，不要重复 open"}"""
        }
        // 无状态工具交给共享路由器；propose_edits / ask_user 仍由本端处理。
        AiToolRouter.execute(name, argsJson, toolEnv(currentHost))?.let { outcome ->
            return when (outcome) {
                // 措辞统一由路由器收尾（submit 的核实提示就在里面）。
                is AiToolOutcome.Value -> AiToolRouter.formatResult(name, outcome.json)
                // Android 的宿主能力都在本进程里，不该收到指令；真收到就是接线错了。
                is AiToolOutcome.CallHost, is AiToolOutcome.Fetch ->
                    """{"ok":false,"error":"宿主指令未在本端实现"}"""
            }
        }
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
                        // 硬拦：把手机上的原文件名写进字段，服务器上没有那张图，提交必然失败。
                        // 不只查本轮附图——历史消息里的名字也会被模型捡回来用，所以连
                        // 本次运行见过的所有原名一起拦。
                        val localNames = knownLocalImages + pendingImages.map { it.name }
                        val wroteLocal = if (localNames.isEmpty()) null else proposal.ops
                            .filterIsInstance<AiOp.SetInput>()
                            .firstOrNull { op ->
                                val text = (op.value as? JsonPrimitive)?.contentOrNull?.trim()
                                text != null && text in localNames
                            }
                        if (wroteLocal != null) {
                            buildJsonObject {
                                put("ok", false)
                                put(
                                    "error",
                                    "「${wroteLocal.input}」写的是手机上的原文件名，服务器上没有这张图；" +
                                        "先调用 upload_image（index 或 name）拿到服务器文件名再写",
                                )
                            }.toString()
                        } else {
                        val state = currentHost.editState()
                        val result = AiEdits.apply(view, state, proposal)
                        if (!result.ok) {
                            buildJsonObject {
                                put("ok", false)
                                put("errors", result.errors.joinToString("；"))
                            }.toString()
                        } else if (AiEdits.isNavigationOnly(proposal)) {
                            // 切图组/切工作流是导航，不进确认框：立即执行，也不带提交。
                            val applied = currentHost.applyEdit(result.state)
                            if (!applied.ok) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("errors", applied.errors.joinToString("；"))
                                }.toString()
                            } else {
                                result.openWorkflow?.let { currentHost.openWorkflow(it) }
                                actions = actions + result.changes.map { it.label }
                                messages = messages + AiChatMessage("assistant", "已切换：${proposal.summary}")
                                buildJsonObject {
                                    put("ok", true)
                                    put("applied", true)
                                    put("navigated", true)
                                    put("hint", "已切换；继续改参数/提示词，改完再单独 submit")
                                }.toString()
                            }
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

            "upload_image" -> uploadImageTool(argsJson)

            else -> """{"ok":false,"error":"未知工具 $name"}"""
        }
    }

    /**
     * `upload_image {index|name}`：AI 决定把某张附图用进工作流时，才真正上传到 ComfyUI。
     *
     * 图片字节在 [pendingImages] 里（选图时只编码、不上传）。按序号（从 1 开始）或原文件名
     * 定位，再走共享层的 [ImageUpload]。上传后的措辞统一由 [AiToolRouter.formatResult] 收尾。
     */
    private suspend fun uploadImageTool(argsJson: String): String {
        val base = comfyBase() ?: return """{"ok":false,"error":"还没连接 ComfyUI，没法上传图片"}"""
        val parsed = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        val index = parsed?.get("index")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val name = parsed?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        val target = when {
            index > 0 -> pendingImages.getOrNull(index - 1)
            name.isNotEmpty() -> pendingImages.firstOrNull { it.name == name }
            else -> null
        } ?: return """{"ok":false,"error":"没找到这张附图：给 index（第几张，从 1 开始）或 name（原文件名）"}"""

        // 同一张图被重复要求上传时直接用上次的结果，别在服务器上堆副本。
        uploadedRefs[target.name]?.let { cached ->
            val raw = buildJsonObject {
                put("ok", true)
                put("filename", cached)
            }.toString()
            return AiToolRouter.formatResult("upload_image", raw)
        }

        val bytes = target.uploadBytes
            ?: return """{"ok":false,"error":"这张图没有待上传的字节了（多半是上一轮丢掉的）；让用户在同一条消息里重新附一次图，再上传"}"""

        // 服务器上可能已有同名文件；换一个唯一名字再传，避免覆盖/失败，也免得模型
        // 写进节点的名字和服务器实际文件名对不上。
        val uploadName = ImageUpload.uniqueFilename(
            target.name,
            System.currentTimeMillis(),
            kotlin.random.Random.nextInt(0, 1 shl 20),
        )
        aiLog("upload_image ${target.name} -> $uploadName bytes=${bytes.size}")
        return when (
            val result = ImageUpload.upload(
                http = OkHttpHttpTransport(),
                baseUrl = base,
                filename = uploadName,
                bytes = bytes,
            )
        ) {
            is ImageUploadResult.Success -> {
                uploadedRefs[target.name] = result.reference
                val raw = buildJsonObject {
                    put("ok", true)
                    put("filename", result.reference)
                }.toString()
                AiToolRouter.formatResult("upload_image", raw)
            }
            is ImageUploadResult.Failure -> result.message.let {
                """{"ok":false,"error":${quote(it)}}"""
            }
        }
    }

    private var job: Job? = null

    /** 用户这一轮的消息：有图时按 OpenAI 多模态格式给 content 数组。 */
    private fun userTurn(text: String, images: List<AiImage>): JsonObject = buildJsonObject {
        put("role", "user")
        // 每一轮都把当前工作流带在用户消息里：切流后即使系统提示被历史稀释，
        // 模型也不会继续拿第一个打开的工作流当“当前”。
        val currentPath = (host ?: fallbackHost)?.digest()?.path.orEmpty()
        val content = if (currentPath.isEmpty()) text else "（当前工作流：$currentPath）\n$text"
        if (images.isEmpty()) {
            put("content", content)
        } else {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", content)
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

    /** 只负责发：请求体由共享层的状态机拼好，这里不做消息拼装。 */
    private fun postModel(payload: String): JsonObject {
        // 先试流式：正文能边生成边显示；不支持就退回整段，别因为流式挂了就不回话。
        streamModel(payload)?.let { return it }
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
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

    /**
     * 流式请求：读 OkHttp 的流，按 SSE 行喂给 [AiStreamAssembler]，实时更新界面。
     *
     * 返回 null 表示「不支持/没数据」，交给整段请求重来。
     */
    private fun streamModel(payload: String): JsonObject? {
        val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val withStream = JsonObject(body + ("stream" to JsonPrimitive(true)))
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(withStream.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null
                val assembler = AiStreamAssembler()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    assembler.feed(line)
                    val partial = assembler.text.ifEmpty { assembler.thoughts }
                    if (partial != streamingText) {
                        streamingText = partial
                        notifyUi()
                    }
                }
                assembler.message()
            }
        } catch (err: Throwable) {
            aiLog("stream fallback: ${err.message}")
            null
        } finally {
            streamingText = ""
            notifyUi()
        }
    }

    private fun quote(text: String): String =
        kotlinx.serialization.json.JsonPrimitive(text).toString()
}
