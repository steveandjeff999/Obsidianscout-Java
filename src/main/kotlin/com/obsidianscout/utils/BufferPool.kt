package com.obsidianscout.utils

import java.io.ByteArrayOutputStream

/**
 * Thread-safe object pool for reusing heavy StringBuilder and ByteArrayOutputStream instances
 * across HTTP request handling and background sync routines.
 *
 * This dramatically reduces Java heap allocation rate and GC pressure on memory-constrained SBCs
 * such as Raspberry Pi 4B.
 */
object BufferPool {

    @PublishedApi internal const val DEFAULT_STRING_BUILDER_CAPACITY = 8192
    @PublishedApi internal const val MAX_STRING_BUILDER_CAPACITY = 65536

    @PublishedApi internal const val DEFAULT_BYTE_STREAM_CAPACITY = 8192
    @PublishedApi internal const val MAX_BYTE_STREAM_CAPACITY = 65536

    @PublishedApi internal val stringBuilderPool = ThreadLocal.withInitial {
        StringBuilder(DEFAULT_STRING_BUILDER_CAPACITY)
    }

    @PublishedApi internal val byteStreamPool = ThreadLocal.withInitial {
        RecyclableByteArrayOutputStream(DEFAULT_BYTE_STREAM_CAPACITY)
    }

    /**
     * Executes the given [block] with a recycled ThreadLocal [StringBuilder].
     * Clears the buffer before and after use, keeping capacity under [MAX_STRING_BUILDER_CAPACITY].
     */
    inline fun <R> withStringBuilder(block: (StringBuilder) -> R): R {
        val sb = stringBuilderPool.get()
        sb.setLength(0)
        try {
            return block(sb)
        } finally {
            if (sb.capacity() > MAX_STRING_BUILDER_CAPACITY) {
                stringBuilderPool.set(StringBuilder(DEFAULT_STRING_BUILDER_CAPACITY))
            } else {
                sb.setLength(0)
            }
        }
    }

    /**
     * Executes the given [block] with a recycled ThreadLocal [ByteArrayOutputStream].
     */
    inline fun <R> withByteArrayStream(block: (ByteArrayOutputStream) -> R): R {
        val stream = byteStreamPool.get()
        stream.reset()
        try {
            return block(stream)
        } finally {
            if (stream.capacity() > MAX_BYTE_STREAM_CAPACITY) {
                byteStreamPool.set(RecyclableByteArrayOutputStream(DEFAULT_BYTE_STREAM_CAPACITY))
            } else {
                stream.reset()
            }
        }
    }

    class RecyclableByteArrayOutputStream(capacity: Int) : ByteArrayOutputStream(capacity) {
        fun capacity(): Int = buf.size
    }
}
