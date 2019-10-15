/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.scheduling

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.*
import kotlin.math.*
import kotlin.random.*

/**
 * Coroutine scheduler (pool of shared threads) which primary target is to distribute dispatched coroutines
 * over worker threads, including both CPU-intensive and blocking tasks, is the most efficient manner.
 *
 * Current scheduler implementation has two optimization targets:
 * * Efficiency in the face of communication patterns (e.g., actors communicating via channel)
 * * Dynamic resizing to support blocking calls without re-dispatching coroutine to separate "blocking" thread pool
 *
 * ### Structural overview
 *
 * Scheduler consists of [corePoolSize] worker threads to execute CPU-bound tasks and up to [maxPoolSize] lazily created threads
 * to execute blocking tasks. Every worker a has local queue in addition to a global scheduler queue and the global queue
 * has priority over local queue to avoid starvation of externally-submitted (e.g. from Android UI thread) tasks.
 * Work-stealing is implemented on top of that queues to provide even load distribution and illusion of centralized run queue.
 *
 * ### Scheduling policy
 *
 * When a coroutine is dispatched from within a scheduler worker, it's placed into the head of worker run queue.
 * If the head is not empty, the task from the head is moved to the tail. Though it is unfair scheduling policy,
 * it effectively couples communicating coroutines into one and eliminates scheduling latency
 * that arises from placing task to the end of the queue.
 * Placing former head to the tail is necessary to provide semi-FIFO order, otherwise queue degenerates to stack.
 * When a coroutine is dispatched from an external thread, it's put into the global queue.
 *
 * ### Work stealing and affinity
 *
 * To provide even tasks distribution worker tries to steal tasks from other workers queues before parking when his local queue is empty.
 * A non-standard solution is implemented to provide tasks affinity: task from FIFO buffer may be stolen only if it is stale enough
 * (based on the value of [WORK_STEALING_TIME_RESOLUTION_NS]).
 * For this purpose monotonic global clock ([System.nanoTime]) is used and every task has associated with it submission time.
 * This approach shows outstanding results when coroutines are cooperative, but as downside scheduler now depends on high-resolution global clock
 * which may limit scalability on NUMA machines. Tasks from LIFO buffer can be stolen on a regular basis.
 *
 * ### Thread management
 * One of the hardest parts of the scheduler is decentralized management of the threads with the progress guarantees similar
 * to the regular centralized executors. The state of the threads consists of [controlState] and [parkedWorkersStack] fields.
 * The former field incorporates the amount of created threads, CPU-tokens and blocking tasks that require a thread compensation,
 * while the latter represents intrusive versioned Treiber stack of idle workers.
 * When a worker cannot find any work, he first adds itself to the stack, then re-scans the queue (to avoid missing signal)
 * and then attempts to park itself (there is additional layer of signalling against unnecessary park/unpark).
 * If worker finds a task that it cannot yet steal due to timer constraints, it stores this fact in its state
 * (to be uncounted when additional work is signalled) and parks for such duration.
 *
 * When a new task arrives to the scheduler (whether it's local or global queue), either an idle worker is being signalled, or
 * a new worker is attempted to be created (only [corePoolSize] workers can be created for regular CPU tasks).
 *
 * ### Dynamic resizing and support of blocking tasks
 *
 * TODO
 */
@Suppress("NOTHING_TO_INLINE")
internal class CoroutineScheduler(
    @JvmField val corePoolSize: Int,
    @JvmField val maxPoolSize: Int,
    @JvmField val idleWorkerKeepAliveNs: Long = IDLE_WORKER_KEEP_ALIVE_NS,
    @JvmField val schedulerName: String = DEFAULT_SCHEDULER_NAME
) : Executor, Closeable {
    init {
        require(corePoolSize >= MIN_SUPPORTED_POOL_SIZE) {
            "Core pool size $corePoolSize should be at least $MIN_SUPPORTED_POOL_SIZE"
        }
        require(maxPoolSize >= corePoolSize) {
            "Max pool size $maxPoolSize should be greater than or equals to core pool size $corePoolSize"
        }
        require(maxPoolSize <= MAX_SUPPORTED_POOL_SIZE) {
            "Max pool size $maxPoolSize should not exceed maximal supported number of threads $MAX_SUPPORTED_POOL_SIZE"
        }
        require(idleWorkerKeepAliveNs > 0) {
            "Idle worker keep alive time $idleWorkerKeepAliveNs must be positive"
        }
    }

    @JvmField
    val globalQueue: GlobalQueue = GlobalQueue()

    /**
     * The stack of parker workers.
     * Every worker registers itself in a stack before parking (if it was not previously registered),
     * so it can be signalled when new tasks arrive.
     * This is a form of intrusive garbage-free Treiber stack where [Worker] also is a stack node.
     *
     * The stack is better than a queue (even with the contention on top) because it unparks threads
     * in most-recently used order, improving both performance and locality.
     * Moreover, it decreases threads thrashing, if the pool has n threads when only n / 2 is required,
     * the latter half will never be unparked and will terminate itself after [IDLE_WORKER_KEEP_ALIVE_NS].
     *
     * This long version consist of version bits with [PARKED_VERSION_MASK]
     * and top worker thread index bits with [PARKED_INDEX_MASK].
     */
    private val parkedWorkersStack = atomic(0L)

    /**
     * Updates index of the worker at the top of [parkedWorkersStack].
     * It always updates version to ensure interference with [parkedWorkersStackPop] operation
     * that might have already decided to put this index to the top.
     *
     * Note, [newIndex] can be zero for the worker that is being terminated (removed from [workers]).
     */
    internal fun parkedWorkersStackTopUpdate(worker: Worker, oldIndex: Int, newIndex: Int) {
        parkedWorkersStack.loop { top ->
            val index = (top and PARKED_INDEX_MASK).toInt()
            val updVersion = (top + PARKED_VERSION_INC) and PARKED_VERSION_MASK
            val updIndex = if (index == oldIndex) {
                if (newIndex == 0) {
                    parkedWorkersStackNextIndex(worker)
                } else {
                    newIndex
                }
            } else {
                index // no change to index, but update version
            }
            if (updIndex < 0) return@loop // retry
            if (parkedWorkersStack.compareAndSet(top, updVersion or updIndex.toLong())) return
        }
    }

    /**
     * Pushes worker into [parkedWorkersStack].
     * It does nothing is this worker is already physically linked to the stack.
     * This method is invoked only from the worker thread itself.
     * This invocation always precedes [LockSupport.parkNanos].
     * See [Worker.doPark].
     *
     * Returns `true` if worker was added to the stack by this invocation, `false` if it was already
     * registered in the stack.
     */
    internal fun parkedWorkersStackPush(worker: Worker): Boolean {
        if (worker.nextParkedWorker !== NOT_IN_STACK) return false // already in stack, bail out
        /*
         * The below loop can be entered only if this worker was not in the stack and, since no other thread
         * can add it to the stack (only the worker itself), this invariant holds while this loop executes.
         */
        parkedWorkersStack.loop { top ->
            val index = (top and PARKED_INDEX_MASK).toInt()
            val updVersion = (top + PARKED_VERSION_INC) and PARKED_VERSION_MASK
            val updIndex = worker.indexInArray
            assert { updIndex != 0 } // only this worker can push itself, cannot be terminated
            worker.nextParkedWorker = workers[index]
            /*
             * Other thread can be changing this worker's index at this point, but it
             * also invokes parkedWorkersStackTopUpdate which updates version to make next CAS fail.
             * Successful CAS of the stack top completes successful push.
             */
            if (parkedWorkersStack.compareAndSet(top, updVersion or updIndex.toLong())) return true
        }
    }

    /**
     * Pops worker from [parkedWorkersStack].
     * It can be invoked concurrently from any thread that is looking for help and needs to unpark some worker.
     * This invocation is always followed by an attempt to [LockSupport.unpark] resulting worker.
     * See [tryUnpark].
     */
    private fun parkedWorkersStackPop(): Worker? {
        parkedWorkersStack.loop { top ->
            val index = (top and PARKED_INDEX_MASK).toInt()
            val worker = workers[index] ?: return null // stack is empty
            val updVersion = (top + PARKED_VERSION_INC) and PARKED_VERSION_MASK
            val updIndex = parkedWorkersStackNextIndex(worker)
            if (updIndex < 0) return@loop // retry
            /*
             * Other thread can be changing this worker's index at this point, but it
             * also invokes parkedWorkersStackTopUpdate which updates version to make next CAS fail.
             * Successful CAS of the stack top completes successful pop.
             */
            if (parkedWorkersStack.compareAndSet(top, updVersion or updIndex.toLong())) {
                /*
                 * We've just took worker out of the stack, but nextParkerWorker is not reset yet, so if a worker is
                 * currently invoking parkedWorkersStackPush it would think it is in the stack and bail out without
                 * adding itself again. It does not matter, since we are going it invoke unpark on the thread
                 * that was popped out of parkedWorkersStack anyway.
                 */
                worker.nextParkedWorker = NOT_IN_STACK
                return worker
            }
        }
    }

    /**
     * Finds next usable index for [parkedWorkersStack]. The problem is that workers can
     * be terminated at their [Worker.indexInArray] becomes zero, so they cannot be
     * put at the top of the stack. In which case we are looking for next.
     *
     * Returns `index >= 0` or `-1` for retry.
     */
    private fun parkedWorkersStackNextIndex(worker: Worker): Int {
        var next = worker.nextParkedWorker
        findNext@ while (true) {
            when {
                next === NOT_IN_STACK -> return -1 // we are too late -- other thread popped this element, retry
                next === null -> return 0 // stack becomes empty
                else -> {
                    val nextWorker = next as Worker
                    val updIndex = nextWorker.indexInArray
                    if (updIndex != 0) return updIndex // found good index for next worker
                    // Otherwise, this worker was terminated and we cannot put it to top anymore, check next
                    next = nextWorker.nextParkedWorker
                }
            }
        }
    }

    /**
     * State of worker threads.
     * [workers] is array of lazily created workers up to [maxPoolSize] workers.
     * [createdWorkers] is count of already created workers (worker with index lesser than [createdWorkers] exists).
     * [blockingTasks] is count of pending (either in the queue or being executed) tasks
     *
     * **NOTE**: `workers[0]` is always `null` (never used, works as sentinel value), so
     * workers are 1-indexed, code path in [Worker.trySteal] is a bit faster and index swap during termination
     * works properly
     */
    @JvmField
    val workers = AtomicReferenceArray<Worker?>(maxPoolSize + 1)

    /**
     * Long describing state of workers in this pool.
     * Currently includes created, CPU-acquired and blocking workers each occupying [BLOCKING_SHIFT] bits.
     */
    private val controlState = atomic(corePoolSize.toLong() shl CPU_PERMITS_SHIFT)
    private val createdWorkers: Int inline get() = (controlState.value and CREATED_MASK).toInt()
    private val availableCpuPermits: Int inline get() = (controlState.value and CPU_PERMITS_MASK shr CPU_PERMITS_SHIFT).toInt()

    private inline fun createdWorkers(state: Long): Int = (state and CREATED_MASK).toInt()
    private inline fun blockingTasks(state: Long): Int = (state and BLOCKING_MASK shr BLOCKING_SHIFT).toInt()
    private inline fun availablePermits(state: Long): Int = (state and CPU_PERMITS_MASK shr CPU_PERMITS_SHIFT).toInt()

    // Guarded by synchronization
    private inline fun incrementCreatedWorkers(): Int = createdWorkers(controlState.incrementAndGet())
    private inline fun decrementCreatedWorkers(): Int = createdWorkers(controlState.getAndDecrement())

    private inline fun incrementBlockingTasks() = controlState.addAndGet(1L shl BLOCKING_SHIFT)

    private inline fun decrementBlockingTasks() {
        controlState.addAndGet(-(1L shl BLOCKING_SHIFT))
    }

    private inline fun tryAcquireCpuPermit(): Boolean {
        while (true) {
            val state = controlState.value
            val available = availablePermits(state)
            if (available == 0) return false
            val update = state - (1L shl CPU_PERMITS_SHIFT)
            if (controlState.compareAndSet(state, update)) return true
        }
    }

    private inline fun releaseCpuPermit() {
        controlState.addAndGet(1L shl CPU_PERMITS_SHIFT)
    }

    // This is used a "stop signal" for close and shutdown functions
    private val _isTerminated = atomic(false)
    val isTerminated: Boolean get() = _isTerminated.value

    companion object {
        // A symbol to mark workers that are not in parkedWorkersStack
        @JvmField
        val NOT_IN_STACK = Symbol("NOT_IN_STACK")

        // Worker termination states
        private const val FORBIDDEN = -1
        private const val ALLOWED = 0
        private const val TERMINATED = 1

        // Masks of control state
        private const val BLOCKING_SHIFT = 21 // 2M threads max
        private const val CREATED_MASK: Long = (1L shl BLOCKING_SHIFT) - 1
        private const val BLOCKING_MASK: Long = CREATED_MASK shl BLOCKING_SHIFT
        private const val CPU_PERMITS_SHIFT = BLOCKING_SHIFT * 2
        private const val CPU_PERMITS_MASK = CREATED_MASK shl CPU_PERMITS_SHIFT

        internal const val MIN_SUPPORTED_POOL_SIZE = 1 // we support 1 for test purposes, but it is not usually used
        internal const val MAX_SUPPORTED_POOL_SIZE = (1 shl BLOCKING_SHIFT) - 2

        // Masks of parkedWorkersStack
        private const val PARKED_INDEX_MASK = CREATED_MASK
        private const val PARKED_VERSION_MASK = CREATED_MASK.inv()
        private const val PARKED_VERSION_INC = 1L shl BLOCKING_SHIFT
    }

    override fun execute(command: Runnable) = dispatch(command)

    override fun close() = shutdown(10_000L)

    // Shuts down current scheduler and waits until all work is done and all threads are stopped.
    fun shutdown(timeout: Long) {
        // atomically set termination flag which is checked when workers are added or removed
        if (!_isTerminated.compareAndSet(false, true)) return
        // make sure we are not waiting for the current thread
        val currentWorker = currentWorker()
        // Capture # of created workers that cannot change anymore (mind the synchronized block!)
        val created = synchronized(workers) { createdWorkers }
        // Shutdown all workers with the only exception of the current thread
        for (i in 1..created) {
            val worker = workers[i]!!
            if (worker !== currentWorker) {
                while (worker.isAlive) {
                    LockSupport.unpark(worker)
                    worker.join(timeout)
                }
                val state = worker.state
                assert { state === WorkerState.TERMINATED } // Expected TERMINATED state
                worker.localQueue.offloadAllWorkTo(globalQueue)
            }
        }
        // Make sure no more work is added to GlobalQueue from anywhere
        globalQueue.close()
        // Finish processing tasks from globalQueue and/or from this worker's local queue
        while (true) {
            val task = currentWorker?.findTask() ?: globalQueue.removeFirstOrNull() ?: break
            runSafely(task)
        }
        // Shutdown current thread
        currentWorker?.tryReleaseCpu(WorkerState.TERMINATED)
        // check & cleanup state
        assert { availableCpuPermits == corePoolSize }
        parkedWorkersStack.value = 0L
        controlState.value = 0L
    }

    /**
     * Dispatches execution of a runnable [block] with a hint to a scheduler whether
     * this [block] may execute blocking operations (IO, system calls, locking primitives etc.)
     *
     * @param taskContext concurrency context of given [block]
     * @param fair whether the task should be dispatched fairly (strict FIFO) or not (semi-FIFO)
     */
    fun dispatch(block: Runnable, taskContext: TaskContext = NonBlockingContext, fair: Boolean = false) {
        trackTask() // this is needed for virtual time support
        val task = createTask(block, taskContext)
        // try to submit the task to the local queue and act depending on the result
        val notAdded = submitToLocalQueue(task, fair)
        if (notAdded != null) {
            if (!globalQueue.addLast(notAdded)) {
                // Global queue is closed in the last step of close/shutdown -- no more tasks should be accepted
                throw RejectedExecutionException("$schedulerName was terminated")
            }
        }
        // Checking 'task' instead of 'notAdded' is completely okay
        if (task.mode == TaskMode.NON_BLOCKING) {
            signalCpuWork()
        } else {
            signalBlockingWork()
        }
    }

    internal fun createTask(block: Runnable, taskContext: TaskContext): Task {
        val nanoTime = schedulerTimeSource.nanoTime()
        if (block is Task) {
            block.submissionTime = nanoTime
            block.taskContext = taskContext
            return block
        }
        return TaskImpl(block, nanoTime, taskContext)
    }

    private fun signalBlockingWork() {
        val stateSnapshot = incrementBlockingTasks()
        if (tryUnpark()) return
        tryCreateWorker(stateSnapshot)
    }

    internal fun signalCpuWork() {
        // todo probably here we should use state snapshot as well
        if (tryUnpark() || availableCpuPermits == 0) return
        if (tryCreateWorker()) return
        // TODO investigate
        tryUnpark() // Try unpark again in case there was race between permit release and parking
    }

    private fun tryCreateWorker(state: Long = controlState.value): Boolean {
        val created = createdWorkers(state)
        val blocking = blockingTasks(state)
        val cpuWorkers = created - blocking
        /*
         * We check how many threads are there to handle non-blocking work,
         * and create one more if we have not enough of them.
         */
        if (cpuWorkers < corePoolSize) {
            val newCpuWorkers = createNewWorker()
            // If we've created the first cpu worker and corePoolSize > 1 then create
            // one more (second) cpu worker, so that stealing between them is operational
            if (newCpuWorkers == 1 && corePoolSize > 1) createNewWorker()
            if (newCpuWorkers > 0) return true
        }
        return false
    }

    private fun tryUnpark(): Boolean {
        while (true) {
            val worker = parkedWorkersStackPop() ?: return false
            val time = worker.minDelayUntilStealableTask // TODO explain
            worker.parkingAllowed = false
            if (worker.signallingAllowed && time == 0L) {
                LockSupport.unpark(worker)
            }
            if (time == 0L && worker.tryForbidTermination()) return true
        }
    }

    /*
     * Returns the number of CPU workers after this function (including new worker) or
     * 0 if no worker was created.
     */
    private fun createNewWorker(): Int {
        synchronized(workers) {
            // Make sure we're not trying to resurrect terminated scheduler
            if (isTerminated) return -1
            val state = controlState.value
            val created = createdWorkers(state)
            val blocking = blockingTasks(state)
            val cpuWorkers = created - blocking
            // Double check for overprovision
            if (cpuWorkers >= corePoolSize) return 0
            if (created >= maxPoolSize || availableCpuPermits == 0) return 0
            // start & register new worker, commit index only after successful creation
            val newIndex = createdWorkers + 1
            require(newIndex > 0 && workers[newIndex] == null)
            /*
             * 1) Claim the slot (under a lock) by the newly created worker
             * 2) Make it observable by increment created workers count
             * 3) Only then start the worker, otherwise it may miss its own creation
             */
            val worker = Worker(newIndex)
            workers[newIndex] = worker
            require(newIndex == incrementCreatedWorkers())
            worker.start()
            return cpuWorkers + 1
        }
    }

    /**
     * Returns `null` if task was successfully added or an instance of the
     * task that was not added or replaced (thus should be added to global queue).
     */
    private fun submitToLocalQueue(task: Task, fair: Boolean): Task? {
        val worker = currentWorker() ?: return task
        /*
         * This worker could have been already terminated from this thread by close/shutdown and it should not
         * accept any more tasks into its local queue.
         */
        if (worker.state === WorkerState.TERMINATED) return task
        if (task.mode == TaskMode.NON_BLOCKING && worker.isBlocking) {
           return task
        }
        return with(worker.localQueue) {
            if (fair) addLast(task) else add(task)
        } ?: return null
    }

    private fun currentWorker(): Worker? = (Thread.currentThread() as? Worker)?.takeIf { it.scheduler == this }

    /**
     * Returns a string identifying the state of this scheduler for nicer debugging.
     * Note that this method is not atomic and represents rough state of pool.
     *
     * State of the queues:
     * b for blocking, c for CPU, r for retiring.
     * E.g. for [1b, 1b, 2c, 1r] means that pool has
     * two blocking workers with queue size 1, one worker with CPU permit and queue size 1
     * and one retiring (executing his local queue before parking) worker with queue size 1.
     * TODO
     */
    override fun toString(): String {
        var parkedWorkers = 0
        var blockingWorkers = 0
        var cpuWorkers = 0
        var retired = 0
        var terminated = 0
        val queueSizes = arrayListOf<String>()
        for (index in 1 until workers.length()) {
            val worker = workers[index] ?: continue
            val queueSize = worker.localQueue.size
            when (worker.state) {
                WorkerState.PARKING -> ++parkedWorkers
                WorkerState.BLOCKING -> {
                    ++blockingWorkers
                    queueSizes += queueSize.toString() + "b" // Blocking
                }
                WorkerState.CPU_ACQUIRED -> {
                    ++cpuWorkers
                    queueSizes += queueSize.toString() + "c" // CPU
                }
                WorkerState.RETIRING -> {
                    ++retired
                    if (queueSize > 0) queueSizes += queueSize.toString() + "r" // Retiring
                }
                WorkerState.TERMINATED -> ++terminated
            }
        }
        val state = controlState.value
        return "$schedulerName@$hexAddress[" +
                "Pool Size {" +
                    "core = $corePoolSize, " +
                    "max = $maxPoolSize}, " +
                "Worker States {" +
                    "CPU = $cpuWorkers, " +
                    "blocking = $blockingWorkers, " +
                    "parked = $parkedWorkers, " +
                    "retired = $retired, " +
                    "terminated = $terminated}, " +
                "running workers queues = $queueSizes, "+
                "global queue size = ${globalQueue.size}, " +
                "Control State Workers {" +
                    "created = ${createdWorkers(state)}, " +
                "blocking = ${blockingTasks(state)}}" +
                "]"
    }

    internal fun runSafely(task: Task) {
        try {
            task.run()
        } catch (e: Throwable) {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler.uncaughtException(thread, e)
        } finally {
            unTrackTask()
        }
    }

    internal inner class Worker private constructor() : Thread() {
        init {
            isDaemon = true
        }

        // guarded by scheduler lock, index in workers array, 0 when not in array (terminated)
        @Volatile // volatile for push/pop operation into parkedWorkersStack
        var indexInArray = 0
            set(index) {
                name = "$schedulerName-worker-${if (index == 0) "TERMINATED" else index.toString()}"
                field = index
            }

        constructor(index: Int) : this() {
            indexInArray = index
        }

        inline val scheduler get() = this@CoroutineScheduler

        @JvmField
        val localQueue: WorkQueue = WorkQueue()

        /**
         * Worker state. **Updated only by this worker thread**.
         * By default, worker is in RETIRING state in the case when it was created, but all CPU tokens or tasks were taken.
         */
        @Volatile
        var state = WorkerState.RETIRING
        val isBlocking: Boolean get() = state == WorkerState.BLOCKING

        /**
         * Small state machine for termination.
         * Followed states are allowed:
         * [ALLOWED] -- worker can wake up and terminate itself
         * [FORBIDDEN] -- worker is not allowed to terminate (because it was chosen by another thread to help)
         * [TERMINATED] -- final state, thread is terminating and cannot be resurrected
         *
         * Allowed transitions:
         * [ALLOWED] -> [FORBIDDEN]
         * [ALLOWED] -> [TERMINATED]
         * [FORBIDDEN] -> [ALLOWED]
         */
        private val terminationState = atomic(ALLOWED)

        /**
         * It is set to the termination deadline when started doing [park] and it reset
         * when there is a task. It servers as protection against spurious wakeups of parkNanos.
         */
        private var terminationDeadline = 0L

        /**
         * Reference to the next worker in the [parkedWorkersStack].
         * It may be `null` if there is no next parked worker.
         * This reference is set to [NOT_IN_STACK] when worker is physically not in stack.
         */
        @Volatile
        var nextParkedWorker: Any? = NOT_IN_STACK
        @Volatile // TODO ughm don't ask
        var parkingAllowed = false
        @Volatile
        var signallingAllowed = false

        /**
         * Tries to set [terminationState] to [FORBIDDEN], returns `false` if this attempt fails.
         * This attempt may fail either because worker terminated itself or because someone else
         * claimed this worker (though this case is rare, because require very bad timings)
         */
        fun tryForbidTermination(): Boolean =
            when (val state = terminationState.value) {
                TERMINATED -> false // already terminated
                FORBIDDEN -> false // already forbidden, someone else claimed this worker
                ALLOWED -> terminationState.compareAndSet(
                    ALLOWED,
                    FORBIDDEN
                )
                else -> error("Invalid terminationState = $state")
            }

        /**
         * Tries to acquire CPU token if worker doesn't have one
         * @return whether worker acquired (or already had) CPU token
         */
        private fun tryAcquireCpuPermit(): Boolean {
            return when {
                state == WorkerState.CPU_ACQUIRED -> true
                this@CoroutineScheduler.tryAcquireCpuPermit() -> {
                    state = WorkerState.CPU_ACQUIRED
                    true
                }
                else -> false
            }
        }

        /**
         * Releases CPU token if worker has any and changes state to [newState]
         * @return whether worker had CPU token
         */
        internal fun tryReleaseCpu(newState: WorkerState): Boolean {
            val previousState = state
            val hadCpu = previousState == WorkerState.CPU_ACQUIRED
            if (hadCpu) releaseCpuPermit()
            if (previousState != newState) state = newState
            return hadCpu
        }

        private var rngState = Random.nextInt()
        /*
         * The delay until at least one task in other worker queues will  become stealable.
         * Volatile to avoid benign data-race
         */
        @Volatile
        public var minDelayUntilStealableTask = 0L

        override fun run() = runWorker()

        private fun runWorker() {
            while (!isTerminated && state != WorkerState.TERMINATED) {
                val task = findTask()
                // Task found. Execute and repeat
                if (task != null) {
                    executeTask(task)
                    continue
                }

                /*
                 * No tasks were found:
                 * 1) Either at least one of the workers has stealable task in its FIFO-buffer with a stealing deadline.
                 *    Then its deadline is stored in [minDelayUntilStealableTask]
                 * 2) No tasks available, time to park and, potentially, shut down the thread.
                 *
                 * In both cases, worker adds itself to the stack of parked workers, re-scans all the queues
                 * to avoid missing wake-up (requestCpuWorker) and either starts executing discovered tasks or parks itself awaiting for new tasks.
                 *
                 * Park duration depends on the possible state: either this is the idleWorkerKeepAliveNs or stealing deadline.
                 */
                parkingAllowed = true
                if (parkedWorkersStackPush(this)) {
                    continue
                } else {
                    signallingAllowed = true
                    if (parkingAllowed) {
                        tryReleaseCpu(WorkerState.PARKING)
                        if (minDelayUntilStealableTask > 0) {
                            LockSupport.parkNanos(minDelayUntilStealableTask)  // No spurious wakeup check here
                        } else {
                            assert { localQueue.size == 0 }
                            park()
                        }
                    }
                    signallingAllowed = false
                }
            }
            tryReleaseCpu(WorkerState.TERMINATED)
        }

        private fun executeTask(task: Task) {
            val taskMode = task.mode
            idleReset(taskMode)
            beforeTask(taskMode)
            runSafely(task)
            afterTask(taskMode)
        }

        private fun beforeTask(taskMode: TaskMode) {
            if (taskMode != TaskMode.NON_BLOCKING) {
                /*
                 * We should release CPU *before* checking for CPU starvation,
                 * otherwise requestCpuWorker() will not count current thread as blocking
                 */
                if (tryReleaseCpu(WorkerState.BLOCKING)) {
                    signalCpuWork()
                }
                return
            }
        }

        private fun afterTask(taskMode: TaskMode) {
            if (taskMode == TaskMode.NON_BLOCKING) return
            decrementBlockingTasks()
            val currentState = state
            // Shutdown sequence of blocking dispatcher
            if (currentState !== WorkerState.TERMINATED) {
                assert { currentState == WorkerState.BLOCKING } // "Expected BLOCKING state, but has $currentState"
                state = WorkerState.RETIRING
            }
        }

        /*
         * Marsaglia xorshift RNG with period 2^32-1 for work stealing purposes.
         * ThreadLocalRandom cannot be used to support Android and ThreadLocal<Random> is up to 15% slower on Ktor benchmarks
         */
        internal fun nextInt(upperBound: Int): Int {
            var r = rngState
            r = r xor (r shl 13)
            r = r xor (r shr 17)
            r = r xor (r shl 5)
            rngState = r
            val mask = upperBound - 1
            // Fast path for power of two bound
            if (mask and upperBound == 0) {
                return r and mask
            }
            return (r and Int.MAX_VALUE) % upperBound
        }

        private fun park() {
            terminationState.value = ALLOWED
            // set termination deadline the first time we are here (it is reset in idleReset)
            if (terminationDeadline == 0L) terminationDeadline = System.nanoTime() + idleWorkerKeepAliveNs
            // actually park
            if (!doPark(idleWorkerKeepAliveNs)) return
            // try terminate when we are idle past termination deadline
            // note that comparison is written like this to protect against potential nanoTime wraparound
            if (System.nanoTime() - terminationDeadline >= 0) {
                terminationDeadline = 0L // if attempt to terminate worker fails we'd extend deadline again
                tryTerminateWorker()
            }
        }

        private fun doPark(nanos: Long): Boolean {
            /*
             * Here we are trying to park, then check whether there are new blocking tasks
             * (because submitting thread could have missed this thread in tryUnpark)
             */
            if (!blockingQuiescence()) return false
            LockSupport.parkNanos(nanos) // Spurious wakeup check in [park]
            return true
        }

        /**
         * Stops execution of current thread and removes it from [createdWorkers].
         */
        private fun tryTerminateWorker() {
            synchronized(workers) {
                // Make sure we're not trying race with termination of scheduler
                if (isTerminated) return
                // Someone else terminated, bail out
                if (createdWorkers <= corePoolSize) return
                // Try to find blocking task before termination
                if (!blockingQuiescence()) return
                /*
                 * See tryUnpark for state reasoning.
                 * If this CAS fails, then we were successfully unparked by other worker and cannot terminate.
                 */
                if (!terminationState.compareAndSet(ALLOWED, TERMINATED)) return
                /*
                 * At this point this thread is no longer considered as usable for scheduling.
                 * We need multi-step choreography to reindex workers.
                 *
                 * 1) Read current worker's index and reset it to zero.
                 */
                val oldIndex = indexInArray
                indexInArray = 0
                /*
                 * Now this worker cannot become the top of parkedWorkersStack, but it can
                 * still be at the stack top via oldIndex.
                 *
                 * 2) Update top of stack if it was pointing to oldIndex and make sure no
                 *    pending push/pop operation that might have already retrieved oldIndex could complete.
                 */
                parkedWorkersStackTopUpdate(this, oldIndex, 0)
                /*
                 * 3) Move last worker into an index in array that was previously occupied by this worker,
                 *    if last worker was a different one (sic!).
                 */
                val lastIndex = decrementCreatedWorkers()
                if (lastIndex != oldIndex) {
                    val lastWorker = workers[lastIndex]!!
                    workers[oldIndex] = lastWorker
                    lastWorker.indexInArray = oldIndex
                    /*
                     * Now lastWorker is available at both indices in the array, but it can
                     * still be at the stack top on via its lastIndex
                     *
                     * 4) Update top of stack lastIndex -> oldIndex and make sure no
                     *    pending push/pop operation that might have already retrieved lastIndex could complete.
                     */
                    parkedWorkersStackTopUpdate(lastWorker, lastIndex, oldIndex)
                }
                /*
                 * 5) It is safe to clear reference from workers array now.
                 */
                workers[lastIndex] = null
            }
            state = WorkerState.TERMINATED
        }

        /**
         * Checks whether new blocking tasks arrived to the pool when worker decided
         * it can go to deep park/termination and puts recently arrived task to its local queue.
         * Returns `true` if there is no blocking tasks in the queue.
         * Invariant: invoked only with empty local queue
         */
        private fun blockingQuiescence(): Boolean {
            assert { localQueue.size == 0 }
            globalQueue.removeFirstWithModeOrNull(TaskMode.PROBABLY_BLOCKING)?.let {
                localQueue.add(it)
                return false
            }
            return true
        }

        // It is invoked by this worker when it finds a task
        private fun idleReset(mode: TaskMode) {
            terminationDeadline = 0L // reset deadline for termination
            if (state == WorkerState.PARKING) {
                assert { mode == TaskMode.PROBABLY_BLOCKING }
                state = WorkerState.BLOCKING
            }
        }

        internal fun findTask(): Task? {
            if (tryAcquireCpuPermit()) return findTaskWithCpuPermit()
            /*
             * If the local queue is empty, try to extract blocking task from global queue.
             * It's helpful for two reasons:
             * 1) We won't call excess park/unpark here and someone's else CPU token won't be transferred,
             *    which is a performance win
             * 2) It helps with rare race when external submitter sends depending blocking tasks
             *    one by one and one of the requested workers may miss CPU token
             */
            return localQueue.poll() ?: globalQueue.removeFirstWithModeOrNull(TaskMode.PROBABLY_BLOCKING)
        }

        private fun findTaskWithCpuPermit(): Task? {
            /*
             * Anti-starvation mechanism: if pool is overwhelmed by external work
             * or local work is frequently offloaded, global queue polling will
             * starve tasks from local queue. But if we never poll global queue,
             * then local tasks may starve global queue, so poll global queue
             * once per two core pool size iterations.
             * Poll global queue only for non-blocking tasks as for blocking task a separate thread was woken up.
             * If current thread is woken up, then its local queue is empty and it will poll global queue anyway,
             * otherwise current thread may already have blocking task in its local queue.
             */
            val globalFirst = nextInt(2 * corePoolSize) == 0
            if (globalFirst) globalQueue.removeFirstWithModeOrNull(TaskMode.NON_BLOCKING)?.let { return it }
            localQueue.poll()?.let { return it }
            if (!globalFirst) globalQueue.removeFirstOrNull()?.let { return it }
            return trySteal()
        }

        private fun trySteal(): Task? {
            assert { localQueue.size == 0 }
            val created = createdWorkers
            // 0 to await an initialization and 1 to avoid excess stealing on single-core machines
            if (created < 2) {
                return null
            }

            var currentIndex = nextInt(created)
            var minDelay = Long.MAX_VALUE
            repeat(created) {
                ++currentIndex
                if (currentIndex > created) currentIndex = 1
                val worker = workers[currentIndex]
                if (worker !== null && worker !== this) {
                    assert { localQueue.size == 0 }
                    val stealResult = localQueue.tryStealFrom(victim = worker.localQueue)
                    if (stealResult == TASK_STOLEN) {
                        return localQueue.poll()
                    } else if (stealResult > 0) {
                        minDelay = min(minDelay, stealResult)
                    }
                }
            }
            minDelayUntilStealableTask = if (minDelay != Long.MAX_VALUE) minDelay else 0
            return null
        }
    }

    enum class WorkerState {
        /**
         * Has CPU token and either executes [TaskMode.NON_BLOCKING] task or tries to steal one
         */
        CPU_ACQUIRED,

        /**
         * Executing task with [TaskMode.PROBABLY_BLOCKING].
         */
        BLOCKING,

        /**
         * Currently parked.
         */
        PARKING,

        /**
         * Tries to execute its local work and then goes to infinite sleep as no longer needed worker.
         */
        RETIRING,

        /**
         * Terminal state, will no longer be used
         */
        TERMINATED
    }
}
