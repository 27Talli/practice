package com.offerpoint.pos

import com.offerpoint.pos.data.local.database.WorkspaceWriteGate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceWriteGateTest {
    @Test fun writeQueuedBehindSuccessfulRestoreIsRejected()=runTest {
        val gate=WorkspaceWriteGate();val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val restoring=async { gate.restore { entered.complete(Unit);finish.await() } };entered.await()
        var applied=false
        val writing=async { runCatching { gate.write { applied=true } } };runCurrent();finish.complete(Unit)
        restoring.await();assertTrue(writing.await().isFailure);assertFalse(applied)
        gate.write { applied=true };assertTrue(applied)
    }
    @Test fun nestedWritesShareTheSameLeaseInsteadOfDeadlocking()=runTest {
        val gate=WorkspaceWriteGate();var count=0
        gate.write { count++;gate.write { count++ } };assertEquals(2,count)
    }
    @Test fun failedRestoreDoesNotInvalidateQueuedMutations()=runTest {
        val gate=WorkspaceWriteGate();val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val restoring=async { runCatching { gate.restore { entered.complete(Unit);finish.await();error("Rollback") } } };entered.await()
        var applied=false;val writing=async { gate.write { applied=true } };runCurrent();finish.complete(Unit)
        assertTrue(restoring.await().isFailure);writing.await();assertTrue(applied)
    }
}
