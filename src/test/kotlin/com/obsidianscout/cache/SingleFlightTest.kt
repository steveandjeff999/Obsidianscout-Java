package com.obsidianscout.cache

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SingleFlightTest {

    @Test
    fun testConcurrentDeduplication() = runBlocking {
        val counter = AtomicInteger(0)
        val key = "test:singleflight:dedup"

        val jobs = (1..10).map {
            async(Dispatchers.Default) {
                SingleFlight.execute(key) {
                    delay(50)
                    counter.incrementAndGet()
                    "computed_value"
                }
            }
        }

        val results = jobs.awaitAll()
        assertEquals(1, counter.get(), "Loader should only be invoked once for concurrent requests")
        results.forEach { assertEquals("computed_value", it) }
        assertEquals(0, SingleFlight.activeKeyCount(), "Key should be pruned when all waiters finish")
    }

    @Test
    fun testCancellationPrunesWaiters() = runBlocking {
        val key = "test:singleflight:cancel"
        val started = CompletableDeferred<Unit>()

        val blockingJob = launch(Dispatchers.Default) {
            SingleFlight.execute(key) {
                started.complete(Unit)
                delay(200)
            }
        }

        started.await()

        val cancelledJob = launch(Dispatchers.Default) {
            SingleFlight.execute(key) {
                delay(200)
            }
        }
        delay(20)
        cancelledJob.cancelAndJoin()

        blockingJob.join()
        assertEquals(0, SingleFlight.activeKeyCount(), "Key must be pruned even after cancellation")
    }
}
