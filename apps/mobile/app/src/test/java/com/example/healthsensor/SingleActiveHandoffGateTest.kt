package com.example.healthsensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleActiveHandoffGateTest {
    @Test
    fun repeatedSubmissionIsRejectedUntilTerminalRelease() {
        val gate = SingleActiveHandoffGate()

        val firstToken = gate.tryAcquire()
        assertTrue(firstToken != null)
        assertTrue(gate.isActive())
        assertTrue(gate.tryAcquire() == null)

        gate.release(firstToken!!)
        assertFalse(gate.isActive())
        assertTrue(gate.tryAcquire() != null)
    }

    @Test
    fun staleCompletionCannotReleaseNewRequestAfterReset() {
        val gate = SingleActiveHandoffGate()
        val staleToken = gate.tryAcquire()!!
        gate.reset()
        val currentToken = gate.tryAcquire()!!

        gate.release(staleToken)
        assertTrue(gate.isActive())

        gate.release(currentToken)
        assertFalse(gate.isActive())
    }
}
