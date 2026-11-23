package com.comfypilot.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumDialog

const val PRIVACY_POLICY_URL = "http://123.249.88.232/privacy.html"
const val USER_AGREEMENT_URL = "http://123.249.88.232/terms.html"
const val ICP_NUMBER = "豫ICP备2026059527号"
const val ICP_LINK = "https://beian.miit.gov.cn/"

/** 首次启动隐私确认的本机记录。同意后不再重复弹出。 */
object PrivacyConsentStore {
    private const val FILE = "privacy_consent"
    private const val KEY_ACCEPTED = "accepted"

    fun accepted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPTED, true)
            .apply()
    }
}

fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 首次启动的隐私政策确认。不同意则退出应用，不进入任何联网流程。 */
@Composable
fun PrivacyConsentDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    val c = Spectrum.colors
    val context = LocalContext.current

    SpectrumDialog(
        title = "隐私政策与用户协议",
        onDismiss = onDecline,
        confirmText = "同意并继续",
        onConfirm = onAgree,
        dismissText = "暂不同意",
        body = {
            Text(
                text = "首次使用前，请阅读并同意《隐私政策》和《用户协议》。\n\n" +
                    "连接信息、工作流参数和任务记录只保存在本机；连接 ComfyUI 时，图片、工作流和任务数据会发送到你填写的服务器。",
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
            )
            ConsentLink("隐私政策", PRIVACY_POLICY_URL) { openExternalUrl(context, it) }
            ConsentLink("用户协议", USER_AGREEMENT_URL) { openExternalUrl(context, it) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { openExternalUrl(context, ICP_LINK) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = ICP_NUMBER,
                    color = c.textDim,
                    fontSize = 11.sp,
                    fontFamily = com.comfypilot.app.ui.theme.SpectrumMono,
                )
            }
        },
    )
}

@Composable
private fun ConsentLink(label: String, url: String, open: (String) -> Unit) {
    val c = Spectrum.colors
    Text(
        text = label,
        color = c.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { open(url) }
            .padding(vertical = 9.dp),
    )
}

/** 拒绝隐私政策时退出当前 Activity。 */
fun exitApplication(context: Context) {
    (context as? Activity)?.finish()
}
