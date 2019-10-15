/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.scheduling

import kotlinx.coroutines.*
import org.junit.*
import java.util.concurrent.*

class BlockingCoroutineDispatcherMixedStealingStressTest : SchedulerTestBase() {

    private val iterations = 10_000

    @Before
    fun setUp() {
        idleWorkerKeepAliveNs = Long.MAX_VALUE
    }

    @Test
    fun testBlockingProgressPreventedInternal()  {
        val blocking = blockingDispatcher(corePoolSize).asExecutor()
        val regular = dispatcher.asExecutor()
        repeat(iterations * stressTestMultiplier) {
            val cpuBlocker = CyclicBarrier(corePoolSize + 1)
            val blockingBlocker = CyclicBarrier(2)
            regular.execute(Runnable {
                // Block all CPU cores except current one
                repeat(corePoolSize - 1) {
                    regular.execute(Runnable {
                        cpuBlocker.await()
                    })
                }

                blocking.execute(Runnable {
                    blockingBlocker.await()
                })

                regular.execute(Runnable {
                    blockingBlocker.await()
                    cpuBlocker.await()
                })
            })
            cpuBlocker.await()
        }
    }
}