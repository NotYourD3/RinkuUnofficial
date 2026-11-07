package de.keksuccino.rinku;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

final class DirectBufferPool {

    private static final int[] SIZE_CLASSES = { 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, 16 * 1024 * 1024,
        64 * 1024 * 1024 };

    private static final int MAX_PER_SIZE_CLASS = 4;

    private final Map<Integer, Deque<ByteBuffer>> pools = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    ByteBuffer acquire(int minSize) {
        if (minSize <= 0) {
            throw new IllegalArgumentException("minSize must be positive");
        }
        int sizeClass = ceilingSizeClass(minSize);
        lock.lock();
        try {
            Deque<ByteBuffer> pool = pools.get(sizeClass);
            if (pool != null && !pool.isEmpty()) {
                ByteBuffer buffer = pool.pop();
                buffer.clear();
                return buffer;
            }
        } finally {
            lock.unlock();
        }
        return MemoryUtilBridge.memAlloc(sizeClass);
    }

    void release(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        int capacity = buffer.capacity();
        int sizeClass = ceilingSizeClass(capacity);
        if (sizeClass != capacity) {
            MemoryUtilBridge.memFree(buffer);
            return;
        }
        lock.lock();
        try {
            Deque<ByteBuffer> pool = pools.computeIfAbsent(sizeClass, ignored -> new ArrayDeque<>());
            if (pool.size() >= MAX_PER_SIZE_CLASS) {
                MemoryUtilBridge.memFree(buffer);
                return;
            }
            buffer.clear();
            pool.push(buffer);
        } finally {
            lock.unlock();
        }
    }

    void clear() {
        lock.lock();
        try {
            for (Deque<ByteBuffer> pool : pools.values()) {
                ByteBuffer buffer;
                while ((buffer = pool.poll()) != null) {
                    MemoryUtilBridge.memFree(buffer);
                }
            }
            pools.clear();
        } finally {
            lock.unlock();
        }
    }

    private static int ceilingSizeClass(int size) {
        for (int sizeClass : SIZE_CLASSES) {
            if (sizeClass >= size) {
                return sizeClass;
            }
        }
        return size;
    }
}
