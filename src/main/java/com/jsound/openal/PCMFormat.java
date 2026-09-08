package com.jsound.openal;

import org.lwjgl.openal.AL11;

import javax.sound.sampled.AudioFormat;

/**
 * Normalizes a Java Sound {@link AudioFormat} into the fields the OpenAL bridge
 * needs. {@link #of} must only ever receive the canonical signed 16-bit
 * little-endian mono/stereo form — the open paths convert anything else via
 * {@link PCMConvert} first (8-bit, big-endian, unsigned). Anything outside the
 * convertible set throws {@link IllegalArgumentException}, which is the Java
 * Sound contract.
 */
final class PCMFormat {

    final int sampleRate;
    final int channels;
    final int frameSize; // bytes per frame (channels * 2)
    final int alFormat;

    private PCMFormat(int sampleRate, int channels, int alFormat) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameSize = channels * 2;
        this.alFormat = alFormat;
    }

    static PCMFormat of(AudioFormat f) {
        if (f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                && f.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
            throw new IllegalArgumentException(
                    "Unsupported encoding: " + f.getEncoding() + " (only PCM)");
        }
        if (f.getSampleSizeInBits() != 16) {
            throw new IllegalArgumentException(
                    "Unsupported sample size: " + f.getSampleSizeInBits() + " bits (only 16-bit PCM)");
        }
        int channels = f.getChannels();
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException(
                    "Unsupported channels: " + channels + " (only mono/stereo)");
        }
        int alFormat = channels == 1 ? AL11.AL_FORMAT_MONO16 : AL11.AL_FORMAT_STEREO16;
        return new PCMFormat((int) f.getSampleRate(), channels, alFormat);
    }

    static boolean supports(AudioFormat f) {
        if (f == null) {
            return true; // unknown format => defer to open time
        }
        boolean pcm = f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                || f.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
        boolean bits = f.getSampleSizeInBits() == 16 || f.getSampleSizeInBits() == 8;
        boolean ch = f.getChannels() == 1 || f.getChannels() == 2;
        return pcm && bits && ch;
    }
}
