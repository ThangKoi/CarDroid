package com.cardroid.lib.airplay

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One bounded writer for an encrypted CarPlay event session.  ACKs and phone
 * commands must share the same CarPlayRecordOutput so their record nonce
 * counter remains strictly ordered.  Callers only enqueue; the event reader
 * is never held up by a receiver that is slow to accept bytes.
 */
class EventCommandWriter(
    wire: OutputStream,
    key: ByteArray,
    private val log: (String) -> Unit = {},
    capacity: Int = 128,
) : Closeable {
    private sealed class Item {
        abstract val bytes: ByteArray
        abstract val label: String
        data class Bytes(override val bytes: ByteArray, override val label: String) : Item()
    }

    private val output = CarPlayRecordOutput(wire, key)
    private val queue = ArrayBlockingQueue<Item>(capacity.coerceIn(8, 512))
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val nextCseq = AtomicInteger(0)
    /** Includes queued and in-flight items, so awaitIdle has no poll race. */
    private val pending = AtomicInteger(0)
    private val writeLock = Any()
    @Volatile private var worker: Thread? = null

    private fun ensureWorker() {
        if (!started.compareAndSet(false, true)) return
        worker = Thread({
            try {
                while (!closed.get() || queue.isNotEmpty()) {
                    val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        synchronized(writeLock) {
                            output.write(item.bytes)
                            output.flush()
                        }
                        log("Media: event writer wrote ${item.label}")
                    } finally {
                        pending.decrementAndGet()
                    }
                }
            } catch (e: InterruptedException) {
                // close() deliberately interrupts the worker after clearing
                // the queue; a peer/session teardown must remain bounded.
            } catch (e: Throwable) {
                failed.set(true)
                closed.set(true)
                discardQueued()
                log("Media: event writer failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "cardroid-event-tx").apply { isDaemon = true; start() }
    }

    private fun discardQueued() {
        val dropped = ArrayList<Item>()
        queue.drainTo(dropped)
        if (dropped.isNotEmpty()) pending.addAndGet(-dropped.size)
    }

    private fun enqueue(bytes: ByteArray, label: String): Boolean {
        if (closed.get() || failed.get()) return false
        pending.incrementAndGet()
        val accepted = queue.offer(Item.Bytes(bytes, label))
        if (!accepted) {
            pending.decrementAndGet()
            log("Media: event writer queue full; dropped $label")
            return false
        }
        ensureWorker()
        return true
    }

    /** Enqueue a response to an incoming event-channel request. */
    fun enqueueAck(cseq: String?): Boolean {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            if (!cseq.isNullOrBlank()) append("CSeq: ").append(cseq).append("\r\n")
            append("Content-Length: 0\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return enqueue(header, "event ACK${cseq?.let { " CSeq=$it" }.orEmpty()}")
    }

    /** Enqueue a phone-to-car event-channel command. */
    fun enqueueCommand(command: AirPlayBplist.Value, label: String = "event command"): Boolean {
        val body = AirPlayBplist.encode(command)
        val cseq = nextCseq.incrementAndGet().toString()
        val header = buildString {
            append("POST /command RTSP/1.0\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("Content-Type: application/x-apple-binary-plist\r\n")
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return enqueue(header + body, "$label CSeq=$cseq")
    }

    /** Test/diagnostic hook; waits for queued writes without making the reader block. */
    fun awaitIdle(timeoutMs: Long = 1000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        while (pending.get() != 0 && System.nanoTime() < deadline) Thread.yield()
        return pending.get() == 0
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        discardQueued()
        worker?.interrupt()
    }
}
