package com.comfypilot.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

const val ICP_NUMBER = "豫ICP备2026059527号"
const val ICP_LINK = "https://beian.miit.gov.cn/"

/** 用系统浏览器打开备案查询页。 */
fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
