package com.teleteh.xplayer2.data.depth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DepthFrameWorkerTest {
    private fun CountDownLatch.awaitOrFail() = assertTrue(await(5, TimeUnit.SECONDS))

    @Test fun initializeInferenceAndReleaseStayOnOneThread() = runBlocking {
        val threads = CopyOnWriteArrayList<Thread>()
        val inferred = CountDownLatch(1)
        val worker = DepthFrameWorker(
            initialize = { threads.add(Thread.currentThread()); true },
            infer = { _, _, _ -> threads.add(Thread.currentThread()); inferred.countDown(); floatArrayOf(1f) },
            release = { threads.add(Thread.currentThread()) },
            log = { _, _ -> },
        )
        try {
            assertTrue(worker.start())
            worker.submit(intArrayOf(1), 1, 1, 1)
            inferred.awaitOrFail()
        } finally {
            assertTrue(worker.stop())
        }
        assertEquals(3, threads.size)
        assertSame(threads[0], threads[1])
        assertSame(threads[0], threads[2])
        assertNotSame(Thread.currentThread(), threads[0])
    }

    @Test fun cancellationDuringInitializationReleasesOnOwnerWithoutInference() = runBlocking {
        val entered = CountDownLatch(1)
        val finishInit = CountDownLatch(1)
        val inferenceCalls = AtomicInteger()
        val threads = CopyOnWriteArrayList<Thread>()
        val worker = DepthFrameWorker(
            initialize = {
                threads.add(Thread.currentThread())
                entered.countDown()
                finishInit.awaitOrFail()
                true
            },
            infer = { _, _, _ -> inferenceCalls.incrementAndGet(); null },
            release = { threads.add(Thread.currentThread()) },
            log = { _, _ -> },
        )
        val startup = launch(start = CoroutineStart.UNDISPATCHED) { worker.start() }
        try {
            entered.awaitOrFail()
            worker.submit(intArrayOf(1), 1, 1, 1)
            startup.cancel()
        } finally {
            finishInit.countDown()
            assertTrue(worker.stop())
        }
        startup.join()
        assertEquals(0, inferenceCalls.get())
        assertEquals(2, threads.size)
        assertSame(threads[0], threads[1])
    }

    @Test fun busyWorkerConsumesOnlyNewestPendingFrame() = runBlocking {
        val entered = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val gotLatest = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<Int>()
        val worker = DepthFrameWorker(
            initialize = { true },
            infer = { pixels, _, _ ->
                frames.add(pixels[0])
                if (pixels[0] == 1) {
                    entered.countDown()
                    finishFirst.awaitOrFail()
                } else gotLatest.countDown()
                floatArrayOf(1f)
            },
            release = {},
            log = { _, _ -> },
        )
        try {
            assertTrue(worker.start())
            worker.submit(intArrayOf(1), 1, 1, 1)
            entered.awaitOrFail()
            worker.submit(intArrayOf(2), 1, 1, 2)
            worker.submit(intArrayOf(3), 1, 1, 3)
            finishFirst.countDown()
            gotLatest.awaitOrFail()
        } finally {
            finishFirst.countDown()
            assertTrue(worker.stop())
        }
        assertEquals(listOf(1, 3), frames.toList())
    }
}
