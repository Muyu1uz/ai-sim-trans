package com.muyulu.aisimtrans.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.muyulu.aisimtrans.config.SimTransProperties;

@Component
public class AudioFrameQueue {
    private final BlockingQueue<AudioChunk> queue;
    private final AtomicLong droppedChunks = new AtomicLong();

    public AudioFrameQueue(SimTransProperties properties) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.audio().queueCapacity()));
    }

    public void offerLatest(AudioChunk chunk) {
        if (queue.offer(chunk)) {
            return;
        }
        queue.poll();
        droppedChunks.incrementAndGet();
        queue.offer(chunk);
    }

    public AudioChunk poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void clear() {
        queue.clear();
    }

    public int size() {
        return queue.size();
    }

    public long droppedChunks() {
        return droppedChunks.get();
    }
}
