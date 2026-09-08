package com.jsound.openal;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.AL11;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code TargetDataLine} backed by an ALC capture device (ALC11 capture API) —
 * this is what lets microphone/voice-input mods work on Android launchers.
 *
 * <p>A per-line capture thread owns every ALC call: it starts the device,
 * polls {@code ALC_CAPTURE_SAMPLES}, pulls frames and encodes the canonical
 * 16-bit LE samples into the requested variant ({@link PCMEncode}) before
 * offering chunks to a bounded queue. {@code read()} consumes that queue with
 * Java Sound blocking semantics (blocks while running and empty, returns what
 * is available once stopped/closed).
 *
 * <p>Android caveats: the launcher needs the microphone permission
 * (RECORD_AUDIO) or {@code alcCaptureOpenDevice} fails and open() throws
 * {@link LineUnavailableException}; most devices only expose 16-bit mono
 * capture, so stereo requests may be rejected by the driver at open time.
 */
final class OpenALTargetDataLine implements TargetDataLine {

    private static final int CAPTURE_BUFFER_FRAMES = 4096;
    private static final int PULL_MAX_FRAMES = 2048;
    private static final int QUEUE_CHUNKS = 64;

    private final DataLine.Info info;
    private final List<LineListener> listeners = new CopyOnWriteArrayList<>();

    private AudioFormat format;
    private PCMEncode encoder; // null = canonical passthrough
    private volatile boolean open;
    private volatile boolean running;
    private volatile boolean closed;

    private long device; // owned by the capture thread (plus open/close)
    private Thread captureThread;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
    private final AtomicLong bytesQueued = new AtomicLong();
    private volatile boolean flushRequested;

    private long bytesReadRequested;
    private byte[] pending; // partially-consumed chunk for read()
    private int pendingPos;
    private final Object readLock = new Object();

    OpenALTargetDataLine(DataLine.Info info) {
        this.info = info;
    }

    /* ----------------------------- Line ------------------------------ */

    @Override
    public Line.Info getLineInfo() {
        return info;
    }

    @Override
    public void open() throws LineUnavailableException {
        throw new LineUnavailableException("A format must be specified");
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        running = false;
        closed = true;
        Thread t = captureThread;
        if (t != null) {
            try {
                t.join(2000); // let the thread alcCaptureStop first
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        if (device != 0L) {
            ALC11.alcCaptureCloseDevice(device);
            device = 0L;
        }
        synchronized (readLock) {
            readLock.notifyAll();
        }
        Log.debug("[jsound-openal] target line CLOSE");
        fire(LineEvent.Type.CLOSE);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void addLineListener(LineListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeLineListener(LineListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isControlSupported(Control.Type control) {
        return false;
    }

    @Override
    public Control getControl(Control.Type control) {
        throw new IllegalArgumentException("No controls supported");
    }

    @Override
    public Control[] getControls() {
        return new Control[0];
    }

    /* --------------------------- DataLine ---------------------------- */

    @Override
    public void open(AudioFormat format) throws LineUnavailableException {
        open(format, 0);
    }

    @Override
    public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
        if (open) {
            throw new IllegalStateException("Line already open");
        }
        if (!PCMFormat.supports(format)) {
            throw new LineUnavailableException("Unsupported format: " + format);
        }
        int channels = format.getChannels();
        int alFormat = channels == 1 ? AL11.AL_FORMAT_MONO16 : AL11.AL_FORMAT_STEREO16;
        long dev = ALC11.alcCaptureOpenDevice((java.nio.ByteBuffer) null,
                (int) format.getSampleRate(), alFormat, CAPTURE_BUFFER_FRAMES);
        if (dev == 0L) {
            throw new LineUnavailableException(
                    "Failed to open OpenAL capture device (" + format + "). "
                            + "On Android the launcher needs microphone permission (RECORD_AUDIO); "
                            + "the driver may also have rejected the rate/channels.");
        }
        this.device = dev;
        this.encoder = PCMEncode.forFormat(format);
        this.format = format;
        this.open = true;
        Log.info("[jsound-openal] target line OPEN: " + format
                + (encoder != null ? " (encoding captured 16-bit LE)" : ""));
        fire(LineEvent.Type.OPEN);
    }

    @Override
    public void drain() {
        // Input line: nothing buffered on our side worth waiting for.
    }

    @Override
    public void flush() {
        pending = null;
        pendingPos = 0;
        byte[] c;
        while ((c = queue.poll()) != null) {
            bytesQueued.addAndGet(-c.length);
        }
        if (captureThread != null && captureThread.isAlive() && running) {
            flushRequested = true; // capture thread discards driver-side samples
        } else if (open && device != 0L) {
            discardDriverSamples();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isActive() {
        return running && open;
    }

    @Override
    public int available() {
        // Queued-for-reader bytes only; no ALC calls from the reader thread.
        long queued = bytesQueued.get();
        int pend = pending != null ? pending.length - pendingPos : 0;
        return (int) Math.min((long) Integer.MAX_VALUE, queued + pend);
    }

    @Override
    public int getBufferSize() {
        return format == null ? 0 : CAPTURE_BUFFER_FRAMES * format.getFrameSize();
    }

    @Override
    public int getFramePosition() {
        return (int) getLongFramePosition();
    }

    @Override
    public long getLongFramePosition() {
        int fs = format != null ? format.getFrameSize() : 0;
        return fs > 0 ? bytesReadRequested / fs : 0;
    }

    @Override
    public long getMicrosecondPosition() {
        if (format == null || format.getFrameSize() <= 0) {
            return 0;
        }
        return (long) (getLongFramePosition() * 1_000_000.0 / format.getSampleRate());
    }

    @Override
    public float getLevel() {
        return 0.0f;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /* ------------------------- TargetDataLine ------------------------ */

    @Override
    public void start() {
        if (!open) {
            throw new IllegalStateException("Line not open");
        }
        if (running) {
            return;
        }
        running = true;
        closed = false;
        if (captureThread == null || !captureThread.isAlive()) {
            captureThread = new Thread(this::captureLoop, "jsound-openal-capture");
            captureThread.setDaemon(true);
            captureThread.start();
        }
        Log.debug("[jsound-openal] target line START");
        fire(LineEvent.Type.START);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false; // capture thread exits its loop and alcCaptureStops
        synchronized (readLock) {
            readLock.notifyAll();
        }
        Log.debug("[jsound-openal] target line STOP");
        fire(LineEvent.Type.STOP);
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (!open) {
            throw new IllegalStateException("Line not open");
        }
        if (b == null) {
            throw new NullPointerException("buffer is null");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException(
                    "off=" + off + " len=" + len + " buf=" + b.length);
        }
        if (len == 0) {
            return 0;
        }
        int filled = 0;
        synchronized (readLock) {
            while (filled < len && !closed) {
                if (pending == null) {
                    byte[] chunk = queue.poll();
                    if (chunk != null) {
                        bytesQueued.addAndGet(-chunk.length);
                        pending = chunk;
                        pendingPos = 0;
                    }
                }
                if (pending != null) {
                    int n = Math.min(pending.length - pendingPos, len - filled);
                    System.arraycopy(pending, pendingPos, b, off + filled, n);
                    pendingPos += n;
                    filled += n;
                    bytesReadRequested += n;
                    if (pendingPos >= pending.length) {
                        pending = null;
                        pendingPos = 0;
                    }
                    continue;
                }
                // No data available.
                if (!running) {
                    break; // stopped and drained
                }
                try {
                    readLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return filled;
    }

    @Override
    public String toString() {
        return "OpenALTargetDataLine[format=" + format + ", open=" + open + ", running=" + running + "]";
    }

    /* --------------------------- capture ----------------------------- */

    private void captureLoop() {
        ByteBuffer pcm = BufferUtils.createByteBuffer(PULL_MAX_FRAMES * 4); // 2ch * 2B worst case
        int canonicalFrame = format.getChannels() * 2;
        ALC11.alcCaptureStart(device);
        try {
            while (running) {
                if (flushRequested) {
                    flushRequested = false;
                    discardDriverSamples();
                }
                int avail = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
                if (avail <= 0) {
                    sleepQuietly(5);
                    continue;
                }
                int frames = Math.min(avail, PULL_MAX_FRAMES);
                pcm.clear().limit(frames * canonicalFrame);
                ALC11.alcCaptureSamples(device, pcm, frames);
                pcm.flip();
                byte[] canonical = new byte[pcm.remaining()];
                pcm.get(canonical);
                byte[] out = encoder != null
                        ? encoder.encode(canonical, 0, canonical.length)
                        : canonical;
                enqueueChunk(out);
            }
        } catch (Throwable t) {
            Log.error("[jsound-openal] capture thread crashed: " + t);
        } finally {
            ALC11.alcCaptureStop(device);
        }
    }

    private void enqueueChunk(byte[] chunk) {
        if (chunk.length == 0) {
            return;
        }
        try {
            queue.put(chunk);
            bytesQueued.addAndGet(chunk.length);
            synchronized (readLock) {
                readLock.notifyAll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drops whatever the driver has already captured (capture-thread or idle path only). */
    private void discardDriverSamples() {
        ByteBuffer trash = BufferUtils.createByteBuffer(PULL_MAX_FRAMES * 4);
        int canonicalFrame = format.getChannels() * 2;
        while (true) {
            int avail = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
            if (avail <= 0) {
                return;
            }
            int frames = Math.min(avail, PULL_MAX_FRAMES);
            trash.clear().limit(frames * canonicalFrame);
            ALC11.alcCaptureSamples(device, trash, frames);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fire(LineEvent.Type type) {
        LineEvent event = new LineEvent(this, type, getLongFramePosition());
        for (LineListener l : listeners) {
            l.update(event);
        }
    }
}
