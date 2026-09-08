package com.jsound.openal;

import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;

/**
 * One-shot playback for a {@code Clip}: the whole PCM buffer is uploaded once
 * and played from a single source. Constructor runs on the worker thread.
 *
 * <p>Beyond plain start/stop it implements the Clip behaviors OpenAL lacks:
 * <ul>
 *   <li>real-time frame position via a worker-side {@code AL_BYTE_OFFSET}
 *       snapshot ({@link #currentFrames()});</li>
 *   <li>finite {@code loop(count)} as software replay (OpenAL only loops
 *       whole buffers endlessly);</li>
 *   <li>a one-shot {@link #onFinish} callback fired on natural completion so
 *       the Clip can emit its STOP event and clear {@code running}.</li>
 * </ul>
 */
final class ClipVoice implements Voice {

    private final int source;
    private final int buffer;
    private final PCMFormat fmt;

    private volatile boolean wantStart;
    private volatile boolean wantStop;
    private volatile boolean looping;
    private volatile boolean closed;
    private volatile int byteOffset;      // seek target in bytes (effective frames)
    private volatile int remainingLoops;  // finite-loop replays left
    private volatile long lastByteOffset; // playback position snapshot
    private volatile float gain = 1.0f;
    private volatile boolean gainDirty;
    private volatile Runnable onFinish;

    private boolean playedOnce;
    private boolean finishNotified;

    private static final int AL_BYTE_OFFSET = 0x1024;

    ClipVoice(OpenALCore core, PCMFormat fmt, byte[] data, int offset, int count) {
        this.fmt = fmt;
        this.source = AL10.alGenSources();
        this.buffer = AL10.alGenBuffers();

        ByteBuffer directBuf = ByteBuffer.allocateDirect(count);
        directBuf.put(data, offset, count);
        directBuf.flip();
        AL10.alBufferData(buffer, fmt.alFormat, directBuf, fmt.sampleRate);
        AL10.alSourceQueueBuffers(source, buffer);
        AL10.alGetError();
    }

    @Override
    public int sourceId() {
        return source;
    }

    void start(boolean loop) {
        looping = loop;
        wantStart = true;
        OpenALCore.INSTANCE.wake();
    }

    /** Number of additional whole-clip replays after the initial pass (finite loops). */
    void setLoops(int count) {
        this.remainingLoops = Math.max(0, count);
    }

    /** Worker-thread callback fired once on natural completion. */
    void setOnFinish(Runnable r) {
        this.onFinish = r;
    }

    void stop() {
        wantStop = true;
        OpenALCore.INSTANCE.wake();
    }

    void setByteOffset(int bytes) {
        this.byteOffset = bytes;
        this.lastByteOffset = bytes;
    }

    void close() {
        closed = true;
        wantStop = true; // deterministic stop, needed for looping sources
        OpenALCore.INSTANCE.wake();
    }

    /** Approximate current playback position in frames (position snapshot from the last tick). */
    long currentFrames() {
        return lastByteOffset / fmt.frameSize;
    }

    @Override
    public void setGain(float linear) {
        this.gain = linear;
        this.gainDirty = true;
        OpenALCore.INSTANCE.wake();
    }

    @Override
    public boolean tick() {
        if (gainDirty) {
            gainDirty = false;
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            AL10.alGetError();
        }
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING) {
            lastByteOffset = AL10.alGetSourcei(source, AL_BYTE_OFFSET);
        }

        if (wantStop) {
            wantStop = false;
            AL10.alSourceStop(source);
            state = AL10.AL_STOPPED;
        }

        boolean replayed = false;
        if (wantStart) {
            wantStart = false;
            playedOnce = true;
            finishNotified = false;
            playFromOffset();
            replayed = true;
        } else if (state == AL10.AL_STOPPED && playedOnce) {
            if (looping) {
                playFromOffset();
                replayed = true;
            } else if (remainingLoops > 0) {
                remainingLoops--;
                playFromOffset();
                replayed = true;
            }
        }

        if (!replayed && playedOnce && !looping && remainingLoops <= 0
                && state == AL10.AL_STOPPED && !finishNotified) {
            finishNotified = true;
            Runnable cb = onFinish;
            if (cb != null) {
                cb.run();
            }
        }

        if (closed && state == AL10.AL_STOPPED) {
            return false;
        }
        return true;
    }

    private void playFromOffset() {
        AL10.alSourcei(source, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
        if (byteOffset > 0) {
            AL10.alSourcei(source, AL_BYTE_OFFSET, byteOffset);
        }
        AL10.alSourcePlay(source);
        AL10.alGetError();
    }

    @Override
    public void free() {
        AL10.alDeleteSources(source);
        AL10.alDeleteBuffers(buffer);
    }
}
