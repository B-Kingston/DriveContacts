package dev.drivecontacts.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPolicyTest {
    private fun policy(vararg infos: Pair<State, Int>) = WorkSyncController.policyFor(infos.toList())

    @Test
    fun runningWorkGetsOneFollowUp() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, policy(State.RUNNING to 0))
        assertNull(policy(State.RUNNING to 0, State.BLOCKED to 0))
    }

    @Test
    fun enqueuedWorkThatNeverRanStays() {
        assertNull(policy(State.ENQUEUED to 0))
    }

    @Test
    fun backoffFinishedOrNoWorkIsReplaced() {
        assertEquals(ExistingWorkPolicy.REPLACE, policy(State.ENQUEUED to 2))
        assertEquals(ExistingWorkPolicy.REPLACE, policy(State.SUCCEEDED to 1))
        assertEquals(ExistingWorkPolicy.REPLACE, policy())
    }
}
