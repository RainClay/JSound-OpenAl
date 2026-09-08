package com.jsound.openal;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code SourceDataLine} backed by an {@link StreamVoice}. Streaming PCM is
 * enqueued on the caller's thread and played by the bridge worker via OpenAL.
 *
 * <p>Accepts any {@link PCMFormat#supports} format; non-canonical variants
 * (8-bit, big-endian, unsigned) are converted to signed 16-bit LE by
 * {@link PCMConvert} on the writing thread before chunking. {@code write()}
 * returns the number of input bytes accepted; a trailing partial frame is
 * carried over to the next call.
 */
final class OpenALSourceDataLine implements SourceDataLine {

    private final DataLine.Info info;
    private final List<LineListener> listeners = new CopyOnWriteArrayList<>();

    private AudioFormat format;
    private StreamVoice voice;
    private PCMConvert conv; // null when the stream is already 16-bit LE
    private volatile boolean open;
    private volatile boolean running;
    private boolean wroteOnce;
    private long totalWritten;
    private long lastLogBytes;

    private final LineControls controls = new LineControls(g -> {
        StreamVoice v = this.voice;
        if (v != null) {
            v.setGain((float) g);
        }
    });

    OpenALSourceDataLine(DataLine.Info info) {
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
        if (voice != null) {
            voice.close();
            voice = null;
        }
        Log.debug("[jsound-openal] source line CLOSE");
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
        this.conv = PCMConvert.forFormat(format);
        final AudioFormat eff = conv != null ? conv.targetFormat() : format;
        try {
            this.voice = OpenALCore.INSTANCE.register(() ->
                    new StreamVoice(OpenALCore.INSTANCE, PCMFormat.of(eff)));
        } catch (Exception e) {
            this.conv = null;
            throw new LineUnavailableException("Failed to open OpenAL source: " + e);
        }
        this.format = format;
        this.open = true;
        Log.info("[jsound-openal] source line OPEN: " + format
                + (conv != null ? " (converting to " + eff + ")" : ""));
        fire(LineEvent.Type.OPEN);
    }

    @Override
    public int write(byte[] b, int off, int len) {
        if (!open || voice == null) {
            throw new IllegalStateException("Line not open");
        }
        if (len <= 0) {
            return 0;
        }
        if (!wroteOnce) {
            wroteOnce = true;
            Log.debug("[jsound-openal] source line WRITE first len=" + len);
        }
        totalWritten += len;
        if (totalWritten - lastLogBytes >= 200000) {
            lastLogBytes = totalWritten;
            Log.debug("[jsound-openal] WRITE total=" + totalWritten
                    + " bytes (~" + (totalWritten / 4 / 44100.0) + "s audio)");
        }

        byte[] src = b;
        int soff = off;
        int slen = len;
        if (conv != null) {
            src = conv.convert(b, off, len);
            soff = 0;
            slen = src.length;
            if (slen == 0) {
                return 0; // only a partial frame so far; carried over
            }
        }
        int chunkSize = voice.bufferBytes();
        int end = soff + slen;
        int written = 0;
        while (soff < end) {
            if (voice.isClosed()) {
                break;
            }
            int n = Math.min(chunkSize, end - soff);
            byte[] chunk = new byte[n];
            System.arraycopy(src, soff, chunk, 0, n);
            voice.enqueue(chunk);
            soff += n;
            written += n;
        }
        if (conv != null) {
            // Report progress in input (requested-format) bytes.
            return written * conv.inFrameSize() / conv.outFrameSize();
        }
        return written;
    }

    @Override
    public int available() {
        if (voice == null) {
            return 0;
        }
        return voice.queueFree() * voice.bufferBytes();
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
    public void drain() {
        if (voice != null) {
            Log.debug("[jsound-openal] source line DRAIN");
            voice.requestDrain();
            voice.awaitDrain(5000);
        }
    }

    @Override
    public void flush() {
        if (voice != null) {
            voice.requestFlush();
        }
    }

    @Override
    public int getBufferSize() {
        return voice == null ? 0 : voice.bufferBytes() * 12;
    }

    @Override
    public int getFramePosition() {
        return (int) (voice == null ? 0 : voice.framesConsumed());
    }

    @Override
    public long getLongFramePosition() {
        return voice == null ? 0 : voice.framesConsumed();
    }

    @Override
    public long getMicrosecondPosition() {
        if (voice == null || format == null) {
            return 0;
        }
        return (long) (voice.framesConsumed() * 1_000_000.0 / format.getSampleRate());
    }

    @Override
    public float getLevel() {
        return 0.0f;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /* ------------------------- SourceDataLine ------------------------ */

    @Override
    public void start() {
        if (!open) {
            throw new IllegalStateException("Line not open");
        }
        if (running) {
            return;
        }
        running = true;
        if (voice != null) {
            voice.setRunning(true);
        }
        Log.debug("[jsound-openal] source line START");
        fire(LineEvent.Type.START);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (voice != null) {
            voice.setRunning(false);
        }
        Log.debug("[jsound-openal] source line STOP");
        fire(LineEvent.Type.STOP);
    }

    private void fire(LineEvent.Type type) {
        long pos = getLongFramePosition();
        LineEvent event = new LineEvent(this, type, pos);
        for (LineListener l : listeners) {
            l.update(event);
        }
    }

    @Override
    public String toString() {
        return "OpenALSourceDataLine[format=" + format + ", open=" + open + ", running=" + running + "]";
    }
}
