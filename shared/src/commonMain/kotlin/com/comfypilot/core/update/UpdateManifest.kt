package com.comfypilot.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `update.json` 的解析与版本判定。
 *
 * 这里只回答一个问题：**这份清单是不是比我现在装的更新**。不发请求、不弹窗、不读配置。
 * 取 JSON 的工作交给两端（Android 走 OkHttp，鸿蒙走 @kit.NetworkKit），
 * 因为那边的网络栈、线程模型和错误处理都不一样；判断口径必须一致，所以放在共享层。
 *
 * 清单示例（仓库根目录）：
 * ```json
 * {
 *   "versionCode": 4,
 *   "versionName": "0.2.2",
 *   "publishedAt": "2026-11-01",
 *   "notes": "修复……",
 *   "android": { "github": "…", "gitee": "…" },
 *   "harmony": { "github": "…", "gitee": "…" }
 * }
 * ```
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val notes: String?,
    val publishedAt: String?,
    /** 按平台分开的下载页；缺哪个平台就回落到顶层的 github / gitee。 */
    private val platforms: Map<String, UpdateLinks>,
    private val fallback: UpdateLinks,
) {
    fun linksFor(platform: String): UpdateLinks {
        val links = platforms[platform]
        return if (links != null && !links.empty) links else fallback
    }
}

/** 两条下载路径，任意一条都可能是 null（发布时可能只准备了一个）。 */
data class UpdateLinks(val github: String? = null, val gitee: String? = null) {
    val empty: Boolean get() = github == null && gitee == null
}

/** 判定结果。 */
sealed interface UpdateVerdict {
    /** 有新版；[links] 已经按当前平台选好。 */
    data class Available(val manifest: UpdateManifest, val links: UpdateLinks) : UpdateVerdict

    /** 已是最新（含本地版本更高的情况，例如装了内测包）。 */
    data class UpToDate(val versionName: String) : UpdateVerdict

    /** 清单拿不到或格式不对；调用方按「网络问题」处理，不要打扰用户。 */
    data object Unavailable : UpdateVerdict
}

object UpdateCheck {
    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_HARMONY = "harmony"

    /** 解析失败一律返回 null：更新检查是锦上添花，不能因为它让 App 崩掉。 */
    fun parse(raw: String): UpdateManifest? {
        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (err: Throwable) {
            null
        } ?: return null

        val code = root.int("versionCode") ?: return null
        if (code <= 0) return null
        val name = root.str("versionName") ?: return null

        val platforms = buildMap {
            for (platform in listOf(PLATFORM_ANDROID, PLATFORM_HARMONY)) {
                val block = root[platform] as? JsonObject ?: continue
                put(platform, UpdateLinks(github = block.str("github"), gitee = block.str("gitee")))
            }
        }

        return UpdateManifest(
            versionCode = code,
            versionName = name,
            notes = root.str("notes"),
            publishedAt = root.str("publishedAt"),
            platforms = platforms,
            fallback = UpdateLinks(github = root.str("github"), gitee = root.str("gitee")),
        )
    }

    fun evaluate(raw: String, platform: String, currentVersionCode: Int): UpdateVerdict =
        evaluate(parse(raw), platform, currentVersionCode)

    /**
     * 只比 `versionCode`，不比版本名字符串——`"0.2.10" < "0.2.9"` 这种坑不值得踩第二次。
     * 因此每次发版必须真的把 versionCode 加上去。
     */
    fun evaluate(
        manifest: UpdateManifest?,
        platform: String,
        currentVersionCode: Int,
    ): UpdateVerdict {
        if (manifest == null) return UpdateVerdict.Unavailable
        if (manifest.versionCode <= currentVersionCode) {
            return UpdateVerdict.UpToDate(manifest.versionName)
        }
        return UpdateVerdict.Available(manifest, manifest.linksFor(platform))
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
