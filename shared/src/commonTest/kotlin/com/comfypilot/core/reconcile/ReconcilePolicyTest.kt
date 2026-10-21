package com.comfypilot.core.reconcile

import com.comfypilot.core.api.HistoryEntry
import com.comfypilot.core.api.QueueItem
import com.comfypilot.core.api.QueueSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReconcilePolicyTest {
    private val budget = ReconcileBudget(maxAttempts = 3, windowMs = 10_000, retryBaseMs = 1_000)

    @Test
    fun unreadableRetriesWithinBudget() {
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Unreadable("timeout"), queue = null, promptId = "p1",
            attempts = 1, elapsedMs = 500, budget = budget,
        )
        assertIs<ReconcileOutcome.Retry>(outcome)
    }

    @Test
    fun unreadableGivesUpAfterAttempts() {
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Unreadable("timeout"), queue = null, promptId = "p1",
            attempts = 3, elapsedMs = 2_000, budget = budget,
        )
        assertIs<ReconcileOutcome.GiveUp>(outcome)
    }

    @Test
    fun unreadableGivesUpAfterWindow() {
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Unreadable("timeout"), queue = null, promptId = "p1",
            attempts = 1, elapsedMs = 10_000, budget = budget,
        )
        assertIs<ReconcileOutcome.GiveUp>(outcome)
    }

    @Test
    fun readableWithOutputsCompletes() {
        val entry = HistoryEntry(
            outputs = mapOf("9" to kotlinx.serialization.json.buildJsonObject {
                put("images", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("filename", kotlinx.serialization.json.JsonPrimitive("a.png"))
                    })
                })
            }),
            status = null,
        )
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Readable(entry), queue = null, promptId = "p1",
            attempts = 1, elapsedMs = 100, budget = budget,
        )
        assertEquals(1, assertIs<ReconcileOutcome.Completed>(outcome).outputs.size)
    }

    @Test
    fun readableErrorFailsImmediately() {
        val entry = HistoryEntry(
            outputs = emptyMap(),
            status = com.comfypilot.core.api.HistoryStatus(
                statusStr = "error",
                completed = false,
                messages = listOf(
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("execution_error"))
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("exception_message", kotlinx.serialization.json.JsonPrimitive("boom"))
                        })
                    },
                ),
            ),
        )
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Readable(entry), queue = null, promptId = "p1",
            attempts = 1, elapsedMs = 100, budget = budget,
        )
        assertEquals("boom", assertIs<ReconcileOutcome.Failed>(outcome).message)
    }

    @Test
    fun readableWithoutOutputsNeedsQueue() {
        val pending = ReconcilePolicy.decide(
            HistoryProbe.Readable(null), queue = null, promptId = "p1",
            attempts = 1, elapsedMs = 100, budget = budget,
        )
        assertIs<ReconcileOutcome.Retry>(pending)
    }

    @Test
    fun stillInQueueMeansActive() {
        val queue = QueueSnapshot(running = listOf(QueueItem("p1", 0)))
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Readable(null), queue = queue, promptId = "p1",
            attempts = 1, elapsedMs = 100, budget = budget,
        )
        assertEquals(true, assertIs<ReconcileOutcome.StillActive>(outcome).running)
    }

    @Test
    fun missingFromBothMeansLost() {
        val outcome = ReconcilePolicy.decide(
            HistoryProbe.Readable(null), queue = QueueSnapshot(), promptId = "p1",
            attempts = 1, elapsedMs = 100, budget = budget,
        )
        assertIs<ReconcileOutcome.Lost>(outcome)
    }
}
