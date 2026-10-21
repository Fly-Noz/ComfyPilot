package com.comfypilot.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.comfypilot.core.net.SessionState
import kotlinx.coroutines.flow.StateFlow

class DebugViewModel(application: Application) : AndroidViewModel(application) {
    val state: StateFlow<SessionState> = SessionHost.state
    val notice: StateFlow<String?> = SessionHost.lastNotice

    fun connect(url: String) = SessionHost.connect(getApplication(), url)

    fun disconnect() = SessionHost.disconnect()

    fun reconnect() = SessionHost.reconnect()

    fun submit() = SessionHost.submit(SAMPLE_WORKFLOW)

    fun cancel(promptId: String) = SessionHost.cancel(promptId)

    companion object {
        /** 仅用于连接层联调的最小工作流。 */
        const val SAMPLE_WORKFLOW = """{"3":{"class_type":"KSampler","inputs":{}}}"""
    }
}
