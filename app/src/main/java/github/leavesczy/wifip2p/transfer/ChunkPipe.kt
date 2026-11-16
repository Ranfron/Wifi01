package github.leavesczy.wifip2p.transfer

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Chunk(capacity: Int) {
    @JvmField
    val data = ByteArray(capacity)

    @JvmField
    var length = 0
}

/**
 * The buffers of one session. They are allocated once and reused for every file, so sending
 * thousands of files does not allocate (and garbage-collect) megabytes per file.
 * A pipeline that ends abnormally does not give its buffers back, because a helper thread might
 * still touch them - the next file simply gets fresh ones.
 */
class ChunkPool(val chunkSize: Int, val depth: Int) {
    private var cache: Array<Chunk>? = null

    @Synchronized
    fun take(): Array<Chunk> {
        val cached = cache
        cache = null
        return cached ?: Array(depth) { Chunk(chunkSize) }
    }

    @Synchronized
    fun giveBack(chunks: Array<Chunk>) {
        cache = chunks
    }
}

/**
 * Two-stage pipeline with a small pool of reusable buffers:
 *
 *  - [producer] runs on a helper thread and fills a chunk (read the file / read the socket),
 *  - [consumer] runs on the calling thread and drains it (write the socket / write the file).
 *
 * While the network is busy sending chunk N, the disk is already reading chunk N+1..N+depth
 * (and the other way round when receiving), so neither side ever sits idle waiting for the other.
 * The old implementation did read -> write -> read -> write strictly one after the other.
 *
 * Back-pressure is automatic: the producer blocks when every buffer is full.
 * The first failure of either side aborts the whole pipeline and is re-thrown to the caller.
 */
object ChunkPipe {

    private const val POLL_MS = 100L
    private val END = Chunk(0)

    /** Convenience for callers without a long-lived pool (and for tests). */
    @Throws(Exception::class)
    fun run(
        chunkSize: Int,
        depth: Int,
        cancel: CancelToken,
        threadName: String,
        producer: (Chunk) -> Boolean,
        consumer: (Chunk) -> Unit
    ) {
        run(ChunkPool(chunkSize, depth), Long.MAX_VALUE, cancel, threadName, producer, consumer)
    }

    /**
     * @param expectedBytes how much data will flow (used to skip the pipeline for small files)
     * @param producer fills [Chunk.data]/[Chunk.length]; returns false when there is nothing more to produce
     * @param consumer processes one filled chunk
     */
    @Throws(Exception::class)
    fun run(
        pool: ChunkPool,
        expectedBytes: Long,
        cancel: CancelToken,
        threadName: String,
        producer: (Chunk) -> Boolean,
        consumer: (Chunk) -> Unit
    ) {
        require(pool.chunkSize > 0 && pool.depth > 0)
        val chunks = pool.take()

        if (expectedBytes <= pool.chunkSize) {
            // Small file: there is nothing to overlap, so skip the helper thread and the queues.
            val chunk = chunks[0]
            while (true) {
                cancel.throwIfCancelled()
                if (!producer(chunk)) {
                    break
                }
                consumer(chunk)
            }
            pool.giveBack(chunks)
            return
        }

        // never more buffers than the file can fill (+1 so the producer can run ahead)
        val needed = minOf(chunks.size.toLong(), (expectedBytes - 1) / pool.chunkSize + 2).toInt()
        val free = ArrayBlockingQueue<Chunk>(needed)
        // `needed` real chunks + 1 slot for the END marker, so offering never blocks
        val full = ArrayBlockingQueue<Chunk>(needed + 1)
        for (i in 0 until needed) {
            free.add(chunks[i])
        }
        val abort = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)

        val helper = Thread({
            try {
                while (!abort.get()) {
                    val chunk = free.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    if (!producer(chunk)) {
                        break
                    }
                    full.put(chunk)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                full.offer(END)
            }
        }, threadName)
        helper.isDaemon = true
        helper.start()

        var completed = false
        try {
            while (true) {
                val chunk = full.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    cancel.throwIfCancelled()
                    continue
                }
                if (chunk === END) {
                    break
                }
                consumer(chunk)
                free.offer(chunk)
                cancel.throwIfCancelled()
            }
            val error = failure.get()
            if (error != null) {
                throw error
            }
            completed = true
            pool.giveBack(chunks)
        } finally {
            abort.set(true)
            try {
                helper.join(if (completed) 5_000L else 1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
