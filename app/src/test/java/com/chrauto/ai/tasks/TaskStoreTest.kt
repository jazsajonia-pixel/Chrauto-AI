package com.chrauto.ai.tasks

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TaskStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sample(id: String, at: Long = 100) = ScheduledTask(
        id = id, title = "Task $id", prompt = "Open Settings", scheduledAt = at, status = TaskStatus.SCHEDULED, createdAt = 1, zoneId = "Asia/Manila",
        viber = ViberBatch("Family", listOf(ViberMessage("m1", "Hi", listOf("/data/x.jpg"))), autoSend = true, gapSeconds = 30), kind = TaskKind.VIBER_BATCH
    )

    @Test fun persistsAcrossInstances() {
        val f = tmp.newFile("tasks.json")
        TaskStore(f).upsert(sample("a"))
        val reloaded = TaskStore(f).get("a")!!
        assertEquals(sample("a"), reloaded)
    }

    @Test fun jsonRoundTripKeepsEveryField() {
        val t = sample("b").copy(attempts = 2, startedAt = 5, finishedAt = 6, lastResult = "ok", lastError = "e", preApproved = true,
            waitingReason = "locked", history = listOf(TaskEvent(3, "Started", false), TaskEvent(4, "Oops", true)))
        assertEquals(listOf(t), TaskJson.decode(TaskJson.encode(listOf(t))))
        assertTrue(TaskJson.decode("not json").isEmpty())
    }

    @Test fun dueTaskIsClaimedOnlyOnce() {
        val store = TaskStore(tmp.newFile("t.json"))
        store.upsert(sample("a"))
        val claims = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1); val done = CountDownLatch(8)
        repeat(8) { pool.execute { go.await(); if (store.claimDue(1_000) != null) claims.incrementAndGet(); done.countDown() } }
        go.countDown(); done.await(); pool.shutdown()
        assertEquals(1, claims.get())
        assertEquals(TaskStatus.RUNNING, store.get("a")!!.status)
        assertEquals(1, store.get("a")!!.attempts)
    }

    @Test fun claimRespectsAllowedStates() {
        val store = TaskStore(tmp.newFile("t.json"))
        store.upsert(sample("a").copy(status = TaskStatus.COMPLETED))
        assertNull(store.claim("a", 1, setOf(TaskStatus.SCHEDULED)))
        assertNull(store.claimDue(10_000))
    }

    @Test fun editorCannotOverwriteARunningOrChangedTask() {
        val store = TaskStore(tmp.newFile("t.json"))
        val t = sample("a")
        assertNull(store.saveEdited(t, null))
        val base = store.get("a")!!.updatedAt
        store.claimDue(1_000)
        assertNotNull(store.saveEdited(t.copy(title = "edited"), base))
        assertEquals(TaskStatus.RUNNING, store.get("a")!!.status)
        store.upsert(store.get("a")!!.copy(status = TaskStatus.FAILED, updatedAt = 77))
        assertNotNull(store.saveEdited(t.copy(title = "edited"), base))
        assertNull(store.saveEdited(t.copy(title = "edited"), 77))
        assertEquals("edited", store.get("a")!!.title)
    }

    @Test fun deleteAndRecover() {
        val store = TaskStore(tmp.newFile("t.json"))
        store.upsert(sample("a").copy(status = TaskStatus.RUNNING))
        store.recoverInterrupted(5, null)
        assertEquals(TaskStatus.NEEDS_ATTENTION, store.get("a")!!.status)
        store.delete("a")
        assertNull(store.get("a"))
    }
}
