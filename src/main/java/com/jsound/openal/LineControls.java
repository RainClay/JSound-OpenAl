package com.jsound.openal;

import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.Control;
import javax.sound.sampled.FloatControl;
import java.util.function.DoubleConsumer;

/**
 * The controls every {@code Line} exposes: MASTER_GAIN (volume, dB) and MUTE.
 * Any change pushes the effective linear gain to the OpenAL source via the
 * given sink. Java Sound uses dB for gain; OpenAL uses linear 0..1, so the
 * conversion is {@code linear = 10^(dB/20)}, with MUTE forcing 0.
 */
final class LineControls {

    private final FloatControl gain;
    private final BooleanControl mute;

    LineControls(DoubleConsumer sink) {
        this.gain = new FloatControl(FloatControl.Type.MASTER_GAIN, -80.0f, 6.0206f, 0.1f,
                100, 0.0f, "dB") {
            @Override
            public void setValue(float v) {
                // Concerto computes gain as 20*log10(volume); volume 0 yields
                // -Infinity, which stock FloatControl.setValue rejects. Clamp so
                // it degrades to silence instead of throwing and killing the
                // caller's playback thread.
                super.setValue(clampDb(v));
                push(sink);
            }
        };
        this.mute = new BooleanControl(BooleanControl.Type.MUTE, false) {
            @Override
            public void setValue(boolean v) {
                super.setValue(v);
                push(sink);
            }
        };
    }

    boolean supports(Control.Type t) {
        return t == FloatControl.Type.MASTER_GAIN || t == BooleanControl.Type.MUTE;
    }

    Control get(Control.Type t) {
        if (t == FloatControl.Type.MASTER_GAIN) {
            return gain;
        }
        if (t == BooleanControl.Type.MUTE) {
            return mute;
        }
        throw new IllegalArgumentException("Unsupported control: " + t);
    }

    Control[] all() {
        return new Control[]{gain, mute};
    }

    private void push(DoubleConsumer sink) {
        float lin = mute.getValue() ? 0f : (float) Math.pow(10.0, gain.getValue() / 20.0);
        sink.accept(lin);
    }

    private static float clampDb(float v) {
        if (Float.isNaN(v) || v < -80.0f) {
            return -80.0f;
        }
        if (v > 6.0206f) {
            return 6.0206f;
        }
        return v;
    }
}
