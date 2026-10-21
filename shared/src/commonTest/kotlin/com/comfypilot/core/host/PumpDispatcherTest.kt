package com.comfypilot.core.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PumpDispatcherTest {

    @Test
    fun `nothing runs until pumped`() {
        val dispatcher = PumpDispatcher()
        var ran = false
        CoroutineScope(SupervisorJob() + dispatcher).launch { ran = true }

        assertEquals(false, ran)
        assertEquals(1, dispatcher.pump(1_000))
        assertEquals(true, ran)
        assertEquals(0, dispatcher.pump(1_000))
    }

    @Test
    fun `delay only resumes when the host feeds enough time`() {
        val dispatcher = PumpDispatcher()
        val order = mutableListOf<String>()
        val local = CoroutineScope(SupervisorJob() + dispatcher)

        local.launch {
            order += "start"
            delay(500)
            order += "after-500"
        }

        dispatcher.pump(0)
        assertEquals(listOf("start"), order)
        assertEquals(500L, dispatcher.nextDelayMs(0))

        dispatcher.pump(499)
        assertEquals(listOf("start"), order)

        dispatcher.pump(500)
        assertEquals(listOf("start", "after-500"), order)
        assertNull(dispatcher.nextDelayMs(500))
    }

    @Test
    fun `timers fire in due order even when pumped in one jump`() {
        val dispatcher = PumpDispatcher()
        val order = mutableListOf<String>()
        val local = CoroutineScope(SupervisorJob() + dispatcher)

        local.launch {
            delay(300)
            order += "late"
        }
        local.launch {
            delay(100)
            order += "early"
        }

        // 先在 t=0 把定时器登记好（模拟"宿主正常运行"），再模拟被冻结后一次跳到 1 秒。
        // 注意 delay 的基准是"登记时那一轮 pump 的时间"，所以必须先 pump(0)。
        dispatcher.pump(0)
        assertEquals(listOf<String>(), order)

        dispatcher.pump(1_000)
        assertEquals(listOf("early", "late"), order)
    }

    @Test
    fun `pending work survives a pump that hits the step budget`() {
        val dispatcher = PumpDispatcher(maxStepsPerPump = 2)
        var count = 0
        val local = CoroutineScope(SupervisorJob() + dispatcher)
        repeat(5) { local.launch { count++ } }

        assertEquals(2, dispatcher.pump(0))
        assertEquals(2, count)
        assertTrue(dispatcher.pump(0) > 0)
    }

    @Test
    fun `cancelling the scope drops the queued work`() {
        val dispatcher = PumpDispatcher()
        var ran = false
        val local = CoroutineScope(SupervisorJob() + dispatcher)
        local.launch {
            delay(10)
            ran = true
        }
        dispatcher.pump(0)
        local.cancel()
        dispatcher.pump(100)
        assertEquals(false, ran)
    }
}
