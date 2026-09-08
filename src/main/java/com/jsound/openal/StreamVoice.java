package com.jsound.openal;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streaming playback for a {@code SourceDataLine}. A bounded queue holds PCM
 * chunks pushed by mod threads; the worker refills OpenAL queued buffers from
 * it. The queue is the backpressure valve: when the worker cannot keep up,
 * {@code write()} blocks, matching Java Sound's blocking-write contract.
 *
 * <p>All OpenAL calls run on the worker thread. Constructor runs on the worker.
 *
 * <p><b>Driver quirk workaround:</b> some Android OpenAL builds auto-recycle
 * consumed buffers — {@code AL_BUFFERS_PROCESSED} stays 0 forever while
 * {@code AL_BUFFERS_QUEUED} shrinks. The refill therefore reclaims buffers via
 * {@code max(processed, inflight - driverQueued)} in FIFO order instead of
 * relying on {@code processed} alone. Playback is additionally gated behind a
 * prebuffer threshold so the source never starts (or restarts) with a nearly
 * empty pipeline — starting with a single ~23 ms buffer used to cycle
 * stop/replay dozens of times per second and sound like static.
 */
final class StreamVoice implements Voice {

    private static final int BUFFER_COUNT = 48;
    private static final int FRAMES_PER_BUFFER = 4096;
    private static final int QUEUE_CAPACITY = 96;

    /** Buffers to queue before starting/restarting the source (~0.74 s @44.1k stereo). */
    private static final int PREBUFFER_BUFFERS = 8;
    /** Play out whatever is queued once the producer has been silent this long (song tail). */
    private static final long STALL_PLAY_MS = 300;

    private final int source;
    private final int[] buffers = new int[BUFFER_COUNT];
    private final Map<Integer, ByteBuffer> direct = new HashMap<>();
    private final int[] idlePool = new int[BUFFER_COUNT];
    private final IntBuffer unqueuedBuf;
    private final PCMFormat fmt;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong bytesConsumed = new AtomicLong();

    /** Buffers currently handed to the driver, FIFO in queue order. */
    private final ArrayDeque<Integer> inflight = new ArrayDeque<>();
    /** Last time the producer enqueued a chunk (drives the tail play-out rule). */
    private volatile long lastEnqueueAt;

    private int idleCount;

    private volatile boolean running;
    private volatile boolean closed;
    private volatile float gain = 1.0f;
    private volatile boolean gainDirty;
    private boolean playedOnce;
    private boolean reportedStuck;
    private boolean reportedUnderrun;
    private long stateLogAt;
    private long lastBalanceLog;

    private static String stateName(int s) {
        switch (s) {
            case AL10.AL_INITIAL: return "INITIAL";
            case AL10.AL_PLAYING: return "PLAYING";
            case AL10.AL_PAUSED: return "PAUSED";
            case AL10.AL_STOPPED: return "STOPPED";
            default: return "?" + s;
        }
    }
    private volatile boolean flushRequested;
    private volatile boolean drainRequested;
    private volatile boolean drainDone;

    StreamVoice(OpenALCore core, PCMFormat fmt) {
        this.fmt = fmt;
        this.source = AL10.alGenSources();
        int bufferBytes = FRAMES_PER_BUFFER * fmt.frameSize;
        int gerr = AL10.alGetError();
        for (int i = 0; i < BUFFER_COUNT; i++) {
            buffers[i] = AL10.alGenBuffers();
            direct.put(buffers[i], ByteBuffer.allocateDirect(bufferBytes));
            idlePool[i] = buffers[i];
        }
        idleCount = BUFFER_COUNT;
        unqueuedBuf = BufferUtils.createIntBuffer(BUFFER_COUNT);
        int err = AL10.alGetError();
        Log.debug("[jsound-openal] voice ctor source=" + source + " err=" + gerr
                + " buffers=" + java.util.Arrays.toString(buffers) + " errAfterGen=" + err);
    }

    @Override
    public int sourceId() {
        return source;
    }

    /* ------------------------- mod-thread API ------------------------- */

    /** Blocks if the queue is full (backpressure). */
    void enqueue(byte[] chunk) {
        if (closed) {
            return;
        }
        lastEnqueueAt = System.currentTimeMillis();
        try {
            queue.put(chunk);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        OpenALCore.INSTANCE.wake();
    }

    void setRunning(boolean running) {
        this.running = running;
        OpenALCore.INSTANCE.wake();
    }

    void requestDrain() {
        drainRequested = true;
        OpenALCore.INSTANCE.wake();
    }

    void requestFlush() {
        flushRequested = true;
        OpenALCore.INSTANCE.wake();
    }

    boolean awaitDrain(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!drainDone && System.currentTimeMillis() < deadline) {
            OpenALCore.INSTANCE.wake();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return drainDone;
            }
        }
        return drainDone;
    }

    void close() {
        closed = true;
        running = false;
        flushRequested = true;
        OpenALCore.INSTANCE.wake();
    }

    boolean isClosed() {
        return closed;
    }

    long framesConsumed() {
        return bytesConsumed.get() / fmt.frameSize;
    }

    int queueFree() {
        return QUEUE_CAPACITY - queue.size();
    }

    int bufferBytes() {
        return FRAMES_PER_BUFFER * fmt.frameSize;
    }

    int chunkFrames() {
        return FRAMES_PER_BUFFER;
    }

    /* --------------------------- worker-side -------------------------- */

    @Override
    public void setGain(float linear) {
        this.gain = linear;
        this.gainDirty = true;
        OpenALCore.INSTANCE.wake();
    }

    @Override
    public boolean tick() {
        if (flushRequested) {
            doFlush();
        }
        if (gainDirty) {
            gainDirty = false;
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            AL10.alGetError();
        }

        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        int driverQueued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int st = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);

        if (st == AL10.AL_STOPPED && !inflight.isEmpty()) {
            // On stop, reclaim all our buffers regardless of whether the driver kept
            // them (processed) or auto-cleared them. Reset so the refill loop re-queues.
            if (driverQueued > 0) {
                unqueuedBuf.clear();
                unqueuedBuf.limit(driverQueued);
                AL10.alSourceUnqueueBuffers(source, unqueuedBuf);
                AL10.alGetError();
            }
            for (int i = 0; i < BUFFER_COUNT; i++) {
                idlePool[i] = buffers[i];
            }
            idleCount = BUFFER_COUNT;
            inflight.clear();
        } else if (processed > 0) {
            // Spec-compliant path: consumed buffers sit in PROCESSED until unqueued.
            unqueuedBuf.clear();
            unqueuedBuf.limit(processed); // unqueue only the processed count, else AL_INVALID_VALUE
            AL10.alSourceUnqueueBuffers(source, unqueuedBuf);
            int eu = AL10.alGetError();
            if (eu != 0) {
                Log.error("[jsound-openal] AL_ERR unqueue processed=" + processed + " code=" + eu);
            }
            unqueuedBuf.flip(); // limit = actual unqueued count; guards against stale/garbage ids
            while (unqueuedBuf.hasRemaining()) {
                int b = unqueuedBuf.get();
                if (inflight.removeFirstOccurrence(b) && idleCount < BUFFER_COUNT) {
                    idlePool[idleCount++] = b;
                }
            }
        } else if (!inflight.isEmpty()) {
            // Auto-recycling driver: consumed buffers vanish from QUEUED without
            // ever appearing in PROCESSED. Reclaim the difference, FIFO order.
            int vanished = Math.max(0, inflight.size() - driverQueued);
            for (int i = 0; i < vanished && !inflight.isEmpty(); i++) {
                int b = inflight.pollFirst();
                if (idleCount < BUFFER_COUNT) {
                    idlePool[idleCount++] = b;
                }
            }
        }

        // Refill idle buffers from the queue (packing whole chunks per buffer).
        while (idleCount > 0) {
            byte[] chunk = queue.poll();
            if (chunk == null) {
                break;
            }
            int b = idlePool[--idleCount];
            fillAndQueue(b, chunk);
            inflight.addLast(b);
        }

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        long now = System.currentTimeMillis();
        int qQueued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int qProc = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        if (idleCount + inflight.size() != BUFFER_COUNT
                && (lastBalanceLog == 0 || now - lastBalanceLog >= 1000)) {
            lastBalanceLog = now;
            Log.debug("[jsound-openal] BALANCE idle=" + idleCount + " queued=" + inflight.size()
                    + " = " + (idleCount + inflight.size()) + " (expect " + BUFFER_COUNT
                    + ") driverQueued=" + qQueued + " q=" + queue.size());
        }
        if (stateLogAt == 0 || now - stateLogAt >= 2000) {
            stateLogAt = now;
            Log.debug("[jsound-openal] tick state=" + stateName(state) + " queued=" + inflight.size()
                    + " driver[queued=" + qQueued + " processed=" + qProc + "] idle=" + idleCount
                    + " q=" + queue.size() + " running=" + running + " closed=" + closed);
        }
        if (running) {
            if (inflight.size() > 0 && state != AL10.AL_PLAYING) {
                // Never start with a nearly empty pipeline: prebuffer, but play
                // out the tail once the producer goes quiet.
                boolean prebuffered = inflight.size() >= PREBUFFER_BUFFERS
                        || now - lastEnqueueAt > STALL_PLAY_MS;
                if (prebuffered) {
                    AL10.alSourcePlay(source);
                    int ep = AL10.alGetError();
                    int after = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                    if (ep != 0 || after != AL10.AL_PLAYING) {
                        Log.error("[jsound-openal] PLAY state=" + stateName(state)
                                + " -> after=" + stateName(after) + " queued=" + inflight.size()
                                + " code=" + ep);
                    }
                    if (!playedOnce) {
                        playedOnce = true;
                        Log.debug("[jsound-openal] STREAM PLAYING queued=" + inflight.size());
                    }
                }
            } else if (inflight.isEmpty() && state == AL10.AL_STOPPED && !queue.isEmpty()) {
                // Data waiting in queue but source silent: a refill bug.
                if (!reportedStuck) {
                    reportedStuck = true;
                    Log.debug("[jsound-openal] STUCK source silent but queue.size=" + queue.size());
                }
            } else if (inflight.isEmpty() && state == AL10.AL_STOPPED && queue.isEmpty()) {
                // Source ran dry: producer (Concerto) stopped feeding.
                if (!reportedUnderrun) {
                    reportedUnderrun = true;
                    Log.debug("[jsound-openal] UNDERRUN queue empty, source silent, idle=" + idleCount);
                }
            }
        } else if (state == AL10.AL_PLAYING) {
            AL10.alSourcePause(source);
        }

        if (drainRequested && inflight.isEmpty() && queue.isEmpty()) {
            drainDone = true;
            drainRequested = false;
        }

        return !(closed && inflight.isEmpty() && queue.isEmpty());
    }

    /**
     * Fills {@code buffer} with {@code first} plus as many whole chunks as fit in
     * the buffer's capacity, then queues it. Packing keeps each OpenAL buffer a
     * meaningful duration (~92ms here) so the source drains slowly and never
     * starves between worker ticks; with a single short chunk per buffer the
     * source repeatedly hits STOPPED and each stop/replay boundary is a click.
     * Only whole chunks are consumed; a remainder that does not fit is left in
     * the queue for the next buffer.
     */
    private void fillAndQueue(int buffer, byte[] first) {
        ByteBuffer directBuf = direct.get(buffer);
        directBuf.clear();
        directBuf.put(first, 0, first.length);
        int used = first.length;
        int cap = directBuf.capacity();
        while (used < cap) {
            byte[] next = queue.peek();
            if (next == null || next.length > cap - used) {
                break;
            }
            queue.poll();
            directBuf.put(next, 0, next.length);
            used += next.length;
        }
        directBuf.flip();
        AL10.alBufferData(buffer, fmt.alFormat, directBuf, fmt.sampleRate);
        int edata = AL10.alGetError();
        AL10.alSourceQueueBuffers(source, buffer);
        int e = AL10.alGetError();
        if (e != 0 || edata != 0) {
            Log.error("[jsound-openal] FILL buf=" + buffer + " len=" + used
                    + " dataErr=" + edata + " queueErr=" + e);
        } else {
            Log.debug("[jsound-openal] FILL buf=" + buffer + " len=" + used);
        }
        bytesConsumed.addAndGet(used);
    }

    private void doFlush() {
        flushRequested = false;
        AL10.alSourceStop(source);
        // This driver auto-clears queued buffers on source stop, so the old
        // unqueue-based return leaked every buffer (idle/queued/driver all hit 0
        // and the refill deadlocked on the second play). Reclaim the full set
        // unconditionally, matching the STOPPED-recovery block in tick().
        for (int i = 0; i < BUFFER_COUNT; i++) {
            idlePool[i] = buffers[i];
        }
        idleCount = BUFFER_COUNT;
        inflight.clear();
        queue.clear();
        drainDone = true;
    }

    @Override
    public void free() {
        AL10.alDeleteSources(source);
        for (int b : buffers) {
            AL10.alDeleteBuffers(b);
        }
    }
}
