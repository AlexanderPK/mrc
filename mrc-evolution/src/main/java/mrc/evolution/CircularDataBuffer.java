package mrc.evolution;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe circular (ring) buffer for maintaining a rolling window of recent corpus data.
 * Used by EvolutionaryEdgeFinder to feed data to fitness evaluations.
 */
public class CircularDataBuffer {
    private final byte[] buffer;
    private final int capacity;
    private int writePos;
    private int size;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public CircularDataBuffer(int capacity) {
        this.buffer = new byte[capacity];
        this.capacity = capacity;
        this.writePos = 0;
        this.size = 0;
    }

    /**
     * Add data to the buffer, overwriting old data if necessary.
     */
    public void add(byte[] data) {
        lock.writeLock().lock();
        try {
            for (byte b : data) {
                buffer[writePos] = b;
                writePos = (writePos + 1) % capacity;
                size = Math.min(size + 1, capacity);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get a snapshot of the current buffer contents.
     */
    public byte[] snapshot() {
        lock.readLock().lock();
        try {
            byte[] snapshot = new byte[size];
            if (size < capacity) {
                // Buffer not full yet
                System.arraycopy(buffer, 0, snapshot, 0, size);
            } else {
                // Buffer is full, need to reorder
                int start = writePos;
                System.arraycopy(buffer, start, snapshot, 0, capacity - start);
                System.arraycopy(buffer, 0, snapshot, capacity - start, start);
            }
            return snapshot;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the current size (number of bytes in buffer).
     */
    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clear the buffer.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            writePos = 0;
            size = 0;
            Arrays.fill(buffer, (byte) 0);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
