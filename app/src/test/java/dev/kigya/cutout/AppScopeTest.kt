package dev.kigya.cutout

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

class AppScopeTest {
    @Test fun `failed background child does not cancel independent pending save work`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> failures += error }
        var saved = false
        scope.launch(handler) { error("one export failed") }
        scope.launch { saved = true }
        testScheduler.advanceUntilIdle()
        assertTrue(saved)
        assertEquals("one export failed", failures.single().message)
        assertTrue(scope.coroutineContext[Job]!!.isActive)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { assertSame(Dispatchers.Main.immediate, CoroutineModule.mainImmediate()) }
        finally { Dispatchers.resetMain() }
        scope.coroutineContext[Job]!!.cancel()
    }
}
