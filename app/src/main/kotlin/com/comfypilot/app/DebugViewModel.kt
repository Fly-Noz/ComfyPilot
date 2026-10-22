package com.comfypilot.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.comfypilot.core.net.SessionState
import kotlinx.coroutines.flow.StateFlow

class DebugViewModel(application: Application) : AndroidViewModel(application) {
    val state: StateFlow<SessionState> = SessionHost.state
    val notice: StateFlow<String?> = SessionHost.lastNotice

    fun connect(url: String, progressSilenceMs: Long = 0L) =
        SessionHost.connect(
            getApplication(),
            url,
            progressSilenceMs.takeIf { it > 0L },
        )

    fun disconnect() = SessionHost.disconnect()

    fun reconnect() = SessionHost.reconnect()

    fun submit() = SessionHost.submit(SAMPLE_WORKFLOW)

    fun submit(workflowJson: String, workflowName: String? = null, workflowPath: String? = null) =
        SessionHost.submit(workflowJson, workflowName, workflowPath)

    fun refreshWorkflows() = SessionHost.refreshWorkflows()

    fun openWorkflow(path: String) = SessionHost.openWorkflow(path)

    fun cancel(promptId: String) = SessionHost.cancel(promptId)

    companion object {
        /** 仅用于连接层联调的最小工作流。 */
        const val SAMPLE_WORKFLOW = """{"3":{"class_type":"KSampler","inputs":{}}}"""
    }
}
