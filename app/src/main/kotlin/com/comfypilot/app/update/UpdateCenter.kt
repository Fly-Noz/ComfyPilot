package com.comfypilot.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comfypilot.core.update.UpdateCheck
import com.comfypilot.core.update.UpdateLinks
import com.comfypilot.core.update.UpdateVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新检查：读仓库根目录的 `update.json`，判断有没有新版。
 *
 * **源站是 Gitee**：GitHub 在国内时通时不通，更新检查跟着一起失效的话，
 * 用户永远看不到新版本。GitHub 只做第二条源，Gitee 拿不到时才试。
 *
 * 判定口径在共享层 `UpdateCheck`，这里只负责发请求、存「用户跳过的版本」、开浏览器。
 */
object UpdateCenter {
    private const val GITEE = "https://gitee.com/Fly-Noz/ComfyPilot/raw/main/update.json"
    private const val GITHUB = "https://raw.githubusercontent.com/Fly-Noz/ComfyPilot/main/update.json"

    private const val PREFS = "update_state"
    private const val KEY_SKIPPED = "skipped_version"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** 待展示的提示；null 表示没有要弹的东西。 */
    var pending by mutableStateOf<UpdatePrompt?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    /** 手动检查的结论，显示在关于页那一行。 */
    var manualNote by mutableStateOf<String?>(null)
        private set

    /**
     * [manual] = true 时无论结果如何都给一句话（用户点了就得有反馈）；
     * 启动时的静默检查只在真有新版时弹窗，网络失败不打扰。
     */
    suspend fun check(context: Context, manual: Boolean) {
        if (checking) return
        val app = context.applicationContext
        checking = true
        manualNote = null
        val currentCode = currentVersionCode(app)
        val verdict = withContext(Dispatchers.IO) { fetch(currentCode) }
        checking = false
        when (verdict) {
            is UpdateVerdict.Available -> {
                val prompt = UpdatePrompt(
                    versionCode = verdict.manifest.versionCode,
                    versionName = verdict.manifest.versionName,
                    currentVersion = currentVersionName(app),
                    notes = verdict.manifest.notes,
                    links = verdict.links,
                )
                if (manual) {
                    pending = prompt
                    manualNote = "发现新版本 ${prompt.versionName}"
                } else if (UpdateCheck.shouldPrompt(verdict, skippedVersion(app))) {
                    pending = prompt
                }
            }

            is UpdateVerdict.UpToDate -> if (manual) manualNote = "已是最新版本 ${verdict.versionName}"
            UpdateVerdict.Unavailable -> if (manual) manualNote = "检查失败，请检查网络"
        }
    }

    /** 关掉弹窗但不记住版本：下次冷启动还会提。 */
    fun dismiss() {
        pending = null
    }

    /** 「稍后」：记下这个版本，下个版本发布前不再弹。 */
    fun skip(context: Context, versionCode: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIPPED, versionCode)
            .apply()
        pending = null
    }

    fun open(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 没有浏览器时不要崩：检查更新本来就是可选动作。
        runCatching { context.startActivity(intent) }
    }

    fun currentVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "?" }

    private fun skippedVersion(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SKIPPED, 0)

    private fun fetch(currentCode: Int): UpdateVerdict {
        for (url in listOf(GITEE, GITHUB)) {
            val body = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body.string()
                }
            }.getOrNull() ?: continue
            val verdict = UpdateCheck.evaluate(body, UpdateCheck.PLATFORM_ANDROID, currentCode)
            // 拿到 200 但内容不对，换下一个源；两个源都不对才算失败。
            if (verdict !is UpdateVerdict.Unavailable) return verdict
        }
        return UpdateVerdict.Unavailable
    }
}

/** 弹窗要显示的东西，网络结果落地后就与请求无关了。 */
data class UpdatePrompt(
    val versionCode: Int,
    val versionName: String,
    val currentVersion: String,
    val notes: String?,
    val links: UpdateLinks,
)
