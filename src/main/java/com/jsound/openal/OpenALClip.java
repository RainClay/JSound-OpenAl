package com.jsound.openal;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code Clip} backed by a {@link ClipVoice}: the whole PCM buffer is uploaded
 * once and played from a single OpenAL source.
 *
 * <p>Accepts any {@link PCMFormat#supports} format; non-canonical variants
 * (8-bit, big-endian, unsigned) are converted to signed 16-bit LE by
 * {@link PCMConvert} before upload. {@code getFormat()} keeps returning the
 * requested format; byte-offset math uses the effective (converted) frame size.
 * The position queries report real-time playback progress.
 */
final class OpenALClip implements Clip {

    private final DataLine.Info info;
    private final List<LineListener> listeners = new CopyOnWriteArrayList<>();

    private AudioFormat format;
    private ClipVoice voice;
    private volatile boolean open;
    private volatile boolean running;
    private volatile long totalFrames;
    private int effFrameSize = -1; // bytes per frame of the uploaded (converted) PCM
    private int loopStart;
    private int loopEnd;

    private final LineControls controls = new LineControls(g -> {
        ClipVoice v = this.voice;
        if (v != null) {
            v.setGain((float) g);
        }
    });

    OpenALClip(DataLine.Info info) {
        this.info = info;
    }

    /* ----------------------------- Line ------------------------------ */

    @Override
    public Line.Info getLineInfo() {
        return info;
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        running = false;
        if (voice != null) {
            voice.close();
            voice = null;
        }
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
        return controls.supports(control);
    }

    @Override
    public Control getControl(Control.Type control) {
        return controls.get(control);
    }

    @Override
    public Control[] getControls() {
        return controls.all();
    }

    /* --------------------------- DataLine ---------------------------- */

    @Override
    public void open() throws LineUnavailableException {
        throw new LineUnavailableException("Use open(AudioFormat, byte[], int, int)");
    }

    public void open(AudioFormat format) throws LineUnavailableException {
        throw new LineUnavailableException("Use open(AudioFormat, byte[], int, int)");
    }

    public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
        throw new LineUnavailableException("Use open(AudioFormat, byte[], int, int)");
    }

    @Override
    public void drain() {
        // Single-shot clip: drain is effectively a no-op for playback completion.
    }

    @Override
    public void flush() {
        // No buffered stream to flush; no-op.
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
        return 0;
    }

    @Override
    public int getBufferSize() {
        return (int) (totalFrames * effectiveFrameSize());
    }

    @Override
    public int getFramePosition() {
        return (int) getLongFramePosition();
    }

    @Override
    public long getLongFramePosition() {
        ClipVoice v = voice;
        return v == null ? 0 : v.currentFrames();
    }

    @Override
    public long getMicrosecondPosition() {
        if (format == null) {
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

    @Override
    public void start() {
        if (!open) {
            throw new IllegalStateException("Line not open");
        }
        running = true;
        if (voice != null) {
            voice.setByteOffset(loopStart * effectiveFrameSize());
            // Java Sound: start() plays once from the current position; looping
            // belongs to loop(count). (The old code looped whenever loopStart
            // was 0, which made every one-shot clip replay forever.)
            voice.start(false);
        }
        fire(LineEvent.Type.START);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (voice != null) {
            voice.stop();
        }
        fire(LineEvent.Type.STOP);
    }

    @Override
    public void loop(int count) {
        if (!open) {
            throw new IllegalStateException("Line not open");
        }
        running = true;
        if (voice != null) {
            voice.setByteOffset(loopStart * effectiveFrameSize());
            if (count == Clip.LOOP_CONTINUOUSLY) {
                voice.start(true);
            } else {
                // OpenAL only supports whole-buffer endless looping; finite
                // counts are emulated by software replay on the worker.
                voice.setLoops(count);
                voice.start(false);
            }
        }
        fire(LineEvent.Type.START);
    }

    /* ----------------------------- Clip ------------------------------ */

    @Override
    public void open(AudioInputStream stream) throws LineUnavailableException, IOException {
        AudioFormat fmt = stream.getFormat();
        if (!PCMFormat.supports(fmt)) {
            throw new LineUnavailableException("Unsupported format: " + fmt);
        }
        byte[] all = stream.readAllBytes();
        int frameSize = fmt.getFrameSize();
        int frames = frameSize > 0 ? all.length / frameSize : 0;
        open(fmt, all, 0, frames);
    }

    @Override
    public void open(AudioFormat format, byte[] data, int offset, int frameCount)
            throws LineUnavailableException {
        if (open) {
            throw new IllegalStateException("Clip already open");
        }
        if (!PCMFormat.supports(format)) {
            throw new LineUnavailableException("Unsupported format: " + format);
        }
        int frameSize = format.getFrameSize();
        if (frameSize <= 0) {
            throw new LineUnavailableException("Unknown frame size in format: " + format);
        }
        AudioFormat eff = PCMConvert.effectiveFormat(format);
        this.effFrameSize = eff.getFrameSize();
        byte[] payload = data;
        int payloadOffset = offset;
        if (PCMConvert.needsConversion(format)) {
            payload = PCMConvert.convertAll(data, offset, frameCount * frameSize, format);
            payloadOffset = 0;
        }
        final int byteCount = frameCount * this.effFrameSize;
        try {
            final AudioFormat effFmt = eff;
            final byte[] pcm = payload;
            final int pcmOff = payloadOffset;
            this.voice = OpenALCore.INSTANCE.register(() ->
                    new ClipVoice(OpenALCore.INSTANCE, PCMFormat.of(effFmt), pcm, pcmOff, byteCount));
            this.voice.setOnFinish(() -> {
                // Natural completion: clear running and emit STOP (the voice
                // callback runs on the bridge worker thread).
                if (running) {
                    running = false;
                    fire(LineEvent.Type.STOP);
                }
            });
        } catch (Exception e) {
            throw new LineUnavailableException("Failed to open OpenAL clip: " + e);
        }
        this.format = format;
        this.totalFrames = frameCount;
        this.loopStart = 0;
        this.loopEnd = -1;
        this.open = true;
        Log.info("[jsound-openal] clip OPEN: " + format
                + (PCMConvert.needsConversion(format)
                    ? " (converting to " + PCMConvert.effectiveFormat(format) + ")" : "")
                + ", frames=" + frameCount);
        fire(LineEvent.Type.OPEN);
    }

    @Override
    public int getFrameLength() {
        return (int) totalFrames;
    }

    @Override
    public long getMicrosecondLength() {
        if (format == null) {
            return 0;
        }
        return (long) (totalFrames * 1_000_000.0 / format.getSampleRate());
    }

    @Override
    public void setFramePosition(int frames) {
        this.loopStart = Math.max(0, frames);
        if (voice != null) {
            voice.setByteOffset(loopStart * effectiveFrameSize());
        }
    }

    @Override
    public void setMicrosecondPosition(long microseconds) {
        if (format == null) {
            return;
        }
        setFramePosition((int) (microseconds * format.getSampleRate() / 1_000_000.0));
    }

    @Override
    public void setLoopPoints(int start, int end) {
        if (!open) {
            throw new IllegalStateException("Clip not open");
        }
        if (end != -1 && end <= start) {
            throw new IllegalArgumentException("Loop end must be -1 or greater than start");
        }
        this.loopStart = Math.max(0, start);
        this.loopEnd = end;
    }

    private int effectiveFrameSize() {
        return effFrameSize > 0 ? effFrameSize : (format != null ? format.getFrameSize() : 0);
    }

    private void fire(LineEvent.Type type) {
        LineEvent event = new LineEvent(this, type, getLongFramePosition());
        for (LineListener l : listeners) {
            l.update(event);
        }
    }

    @Override
    public String toString() {
        return "OpenALClip[format=" + format + ", frames=" + totalFrames + ", open=" + open + "]";
    }
}
