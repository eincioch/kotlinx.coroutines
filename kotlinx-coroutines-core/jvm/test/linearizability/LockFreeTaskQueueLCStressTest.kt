/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.*
import kotlin.test.*

@Param(name = "value", gen = IntGen::class, conf = "1:3")
internal abstract class AbstractLockFreeTaskQueueWithoutRemoveLCStressTest protected constructor(singleConsumer: Boolean) : VerifierState() {
    @JvmField
    protected val q = LockFreeTaskQueue<Int>(singleConsumer = singleConsumer)

    @Operation
    fun close() = q.close()

    @Operation
    fun addLast(@Param(name = "value") value: Int) = q.addLast(value)

    override fun extractState() = q.map { it } to q.isClosed()
}

internal class LockFreeTaskQueueWithoutRemoveLCStressTest : AbstractLockFreeTaskQueueWithoutRemoveLCStressTest(singleConsumer = false) {
    @Test
    fun testWithoutRemove() = LCStressOptionsDefault()
        .actorsPerThread(if (isStressTest) 5 else 3)
        .check(this::class)
}

internal class MCLockFreeTaskQueueWithRemoveLCStressTest : AbstractLockFreeTaskQueueWithoutRemoveLCStressTest(singleConsumer = false) {
    @Operation
    fun removeFirstOrNull() = q.removeFirstOrNull()

    @Test
    fun testWithRemoveForQuiescentConsistency() = LCStressOptionsDefault()
        .verifier(QuiescentConsistencyVerifier::class.java)
        .check(this::class)
}

@OpGroupConfig(name = "consumer", nonParallel = true)
internal class SCLockFreeTaskQueueWithRemoveLCStressTest : AbstractLockFreeTaskQueueWithoutRemoveLCStressTest(singleConsumer = true) {
    @Operation(group = "consumer")
    fun removeFirstOrNull() = q.removeFirstOrNull()

    @Test
    fun testWithRemoveForQuiescentConsistency() = LCStressOptionsDefault()
        .verifier(QuiescentConsistencyVerifier::class.java)
        .check(this::class)
}