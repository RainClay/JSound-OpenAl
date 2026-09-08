package com.jsound.openal;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * A minimal {@link Mixer} exposing the bridge's {@code SourceDataLine},
 * {@code Clip} and {@code TargetDataLine} (microphone capture) implementations
 * to {@code AudioSystem}.
 */
final class JSoundMixer implements Mixer {

    static final Mixer.Info INFO = new Mixer.Info(
            "OpenAL Bridge (jsound-openal)",
            "jsound-openal",
            "Routes javax.sound.sampled playback through LWJGL OpenAL for Android launchers",
            "1.0") {
    };

    // Advertisement only — isLineSupported does the real filtering via
    // PCMFormat.supports (any rate, 8/16-bit, either endianness, convertible
    // via PCMConvert). These entries just make the convertible variants
    // visible to mods inspecting getSourceLineInfo().
    private static final AudioFormat[] SUPPORTED = {
            new AudioFormat(44100, 16, 1, true, false),
            new AudioFormat(44100, 16, 2, true, false),
            new AudioFormat(44100, 16, 1, true, true),
            new AudioFormat(44100, 16, 2, true, true),
            new AudioFormat(44100, 8, 1, false, false),
            new AudioFormat(44100, 8, 2, false, false),
            new AudioFormat(22050, 8, 1, false, false),
            new AudioFormat(22050, 8, 2, false, false),
    };

    private static final Line.Info[] SOURCE_LINES = {
            new DataLine.Info(SourceDataLine.class, SUPPORTED, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED),
            new DataLine.Info(Clip.class, SUPPORTED, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED),
    };

    private static final Line.Info[] TARGET_LINES = {
            new DataLine.Info(TargetDataLine.class, SUPPORTED, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED),
    };

    private volatile boolean open;

    @Override
    public Mixer.Info getMixerInfo() {
        return INFO;
    }

    @Override
    public Line.Info[] getSourceLineInfo() {
        return SOURCE_LINES.clone();
    }

    @Override
    public Line.Info[] getTargetLineInfo() {
        return TARGET_LINES.clone();
    }

    @Override
    public Line.Info[] getSourceLineInfo(Line.Info info) {
        if (isLineSupported(info)) {
            return new Line.Info[]{info};
        }
        return new Line.Info[0];
    }

    @Override
    public Line.Info[] getTargetLineInfo(Line.Info info) {
        if (isLineSupported(info) && TargetDataLine.class.isAssignableFrom(info.getLineClass())) {
            return new Line.Info[]{info};
        }
        return new Line.Info[0];
    }

    @Override
    public boolean isLineSupported(Line.Info info) {
        if (info == null) {
            return false;
        }
        // A bare Line.Info(SourceDataLine/Clip/TargetDataLine.class) (no format)
        // is Concerto-style "do you support this kind of line" enumeration
        // query. We support all three kinds, so answer true; only a
        // DataLine.Info carrying specific formats is filtered by format.
        if (!(info instanceof DataLine.Info)) {
            Class<?> kind = info.getLineClass();
            return SourceDataLine.class.isAssignableFrom(kind)
                    || Clip.class.isAssignableFrom(kind)
                    || TargetDataLine.class.isAssignableFrom(kind);
        }
        DataLine.Info d = (DataLine.Info) info;
        Class<?> c = d.getLineClass();
        boolean kind = SourceDataLine.class.isAssignableFrom(c)
                || Clip.class.isAssignableFrom(c)
                || TargetDataLine.class.isAssignableFrom(c);
        if (!kind) {
            return false;
        }
        AudioFormat[] formats = d.getFormats();
        if (formats == null) {
            return true;
        }
        for (AudioFormat f : formats) {
            if (PCMFormat.supports(f)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Line getLine(Line.Info info) throws LineUnavailableException {
        if (!(info instanceof DataLine.Info)) {
            throw new LineUnavailableException("Unsupported line info: " + info);
        }
        DataLine.Info d = (DataLine.Info) info;
        Class<?> c = d.getLineClass();
        if (SourceDataLine.class.isAssignableFrom(c)) {
            return new OpenALSourceDataLine(d);
        }
        if (Clip.class.isAssignableFrom(c)) {
            return new OpenALClip(d);
        }
        if (TargetDataLine.class.isAssignableFrom(c)) {
            return new OpenALTargetDataLine(d);
        }
        throw new LineUnavailableException("Unsupported line: " + c);
    }

    @Override
    public int getMaxLines(Line.Info info) {
        // Capture devices are effectively exclusive on Android: one mic line.
        if (info != null && TargetDataLine.class.isAssignableFrom(info.getLineClass())) {
            return 1;
        }
        return AudioSystem.NOT_SPECIFIED;
    }

    @Override
    public Line[] getSourceLines() {
        return new Line[0];
    }

    @Override
    public Line[] getTargetLines() {
        return new Line[0];
    }

    @Override
    public void synchronize(Line[] lines, boolean sync) {
    }

    @Override
    public void unsynchronize(Line[] lines) {
    }

    @Override
    public boolean isSynchronizationSupported(Line[] lines, boolean sync) {
        return false;
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public Line.Info getLineInfo() {
        return new Line.Info(Mixer.class) {
        };
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

    @Override
    public void addLineListener(LineListener listener) {
    }

    @Override
    public void removeLineListener(LineListener listener) {
    }
}
