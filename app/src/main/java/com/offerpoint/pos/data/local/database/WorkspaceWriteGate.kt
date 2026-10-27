package com.offerpoint.pos.data.local.database

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Queued mutations from the previous dataset must not run against reused product/sale IDs. */
class WorkspaceWriteGate {
    private val mutex=Mutex()
    private val epoch=AtomicLong()
    private class Lease(val owner:WorkspaceWriteGate):AbstractCoroutineContextElement(Key) {
        companion object Key:CoroutineContext.Key<Lease>
    }
    suspend fun <T> write(block:suspend()->T):T {
        if(currentCoroutineContext()[Lease]?.owner===this) return block()
        val expected=epoch.get()
        return mutex.withLock {
            check(expected==epoch.get()) { "The workspace was restored. Reopen this screen before making changes." }
            withContext(Lease(this)) { block() }
        }
    }
    suspend fun <T> restore(block:suspend()->T):T = mutex.withLock {
        // After explicit confirmation/recovery verification, complete the local commit and epoch together.
        withContext(NonCancellable+Lease(this)) { val result=block();epoch.incrementAndGet();result }
    }
}

suspend fun <T> PosDatabase.withWriteTransaction(block:suspend()->T):T = writeGate.write { withTransaction(block) }
suspend fun <T> PosDatabase.withRestoreTransaction(block:suspend()->T):T = writeGate.restore { withTransaction(block) }
