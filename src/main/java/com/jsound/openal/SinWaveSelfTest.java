package com.jsound.openal;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Smoke test: generates a short 440 Hz sine and plays it through the bridge via
 * {@code AudioSystem.getSourceDataLine}. Requires a functioning OpenAL device
 * (i.e. run on the phone inside PojavLauncher / Zalith, or a desktop with LWJGL
 * natives on the classpath). On a bare desktop JVM without AL natives this will
 * print that the bridge mixer was NOT found / playback failed, which is expected.
 */
public final class SinWaveSelfTest {

    public static void main(String[] args) throws Exception {
        boolean found = false;
        for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
            System.out.println("[jsound-openal] mixer: " + mi.getName());
            if (mi.getName().startsWith("OpenAL Bridge")) {
                found = true;
            }
        }
        System.out.println("[jsound-openal] bridge mixer present: " + found);
        if (!found) {
            System.out.println("[jsound-openal] Bridge not discoverable via ServiceLoader; "
                    + "proceeding to open the first SourceDataLine anyway.");
        }

        float rate = 44100;
        AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);

        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        System.out.println("[jsound-openal] got line: " + line.getClass().getName());

        line.open(fmt);
        line.start();

        int seconds = 2;
        int frames = (int) (rate * seconds);
        byte[] data = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = i / rate;
            short s = (short) (Math.sin(2 * Math.PI * 440 * t) * Short.MAX_VALUE * 0.6);
            data[i * 2] = (byte) (s & 0xFF);
            data[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }

        System.out.println("[jsound-openal] writing " + data.length + " bytes...");
        line.write(data, 0, data.length);
        line.drain();
        System.out.println("[jsound-openal] drained. frames played: " + line.getLongFramePosition());

        line.close();
        System.out.println("[jsound-openal] done.");
    }

    private SinWaveSelfTest() {
    }
}
