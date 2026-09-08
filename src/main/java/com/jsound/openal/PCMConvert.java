package com.jsound.openal;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;

/**
 * Normalizes any supported PCM variant into the canonical signed 16-bit
 * little-endian form OpenAL expects:
 * <ul>
 *   <li>8-bit (unsigned by Java Sound convention; signed tolerated) -&gt; 16-bit</li>
 *   <li>16-bit big-endian -&gt; little-endian</li>
 *   <li>16-bit unsigned -&gt; signed</li>
 * </ul>
 * This is what lets old mods (8-bit 22 kHz WAVs, big-endian AU/AIFF, ...) play
 * through the bridge instead of being rejected or rendered as noise.
 *
 * <p>Whole-buffer conversion backs {@code Clip.open}; the instance form carries
 * a trailing partial frame across {@code SourceDataLine.write()} calls.
 * Instances are not thread-safe: confine to the writing thread.
 */
final class PCMConvert {

    private final int channels;
    private final int inFrameSize;
    private final boolean bits8;
    private final boolean unsigned;
    private final boolean swap; // 16-bit big-endian
    private final AudioFormat target;

    private byte[] carry;
    private int carryLen;

    private PCMConvert(AudioFormat f) {
        this.channels = f.getChannels();
        this.bits8 = f.getSampleSizeInBits() == 8;
        this.inFrameSize = bits8 ? channels : channels * 2;
        this.unsigned = f.getEncoding() == Encoding.PCM_UNSIGNED;
        this.swap = !bits8 && f.isBigEndian();
        this.target = effectiveFormat(f);
    }

    /** True when {@code f} is not already signed 16-bit little-endian PCM. */
    static boolean needsConversion(AudioFormat f) {
        if (f == null) {
            return false;
        }
        boolean bits8 = f.getSampleSizeInBits() == 8;
        boolean unsigned16 = f.getEncoding() == Encoding.PCM_UNSIGNED && f.getSampleSizeInBits() == 16;
        return bits8 || unsigned16 || f.isBigEndian();
    }

    /** The canonical 16-bit little-endian format with the same rate/channels. */
    static AudioFormat effectiveFormat(AudioFormat f) {
        return new AudioFormat(Encoding.PCM_SIGNED, f.getSampleRate(), 16, f.getChannels(),
                f.getChannels() * 2, f.getSampleRate(), false);
    }

    /** Instance converter for {@code f}, or null when no conversion is needed. */
    static PCMConvert forFormat(AudioFormat f) {
        return needsConversion(f) ? new PCMConvert(f) : null;
    }

    /** One-shot whole-buffer conversion (Clip open path). */
    static byte[] convertAll(byte[] data, int offset, int count, AudioFormat f) {
        return new PCMConvert(f).convert(data, offset, count);
    }

    int inFrameSize() {
        return inFrameSize;
    }

    int outFrameSize() {
        return channels * 2;
    }

    AudioFormat targetFormat() {
        return target;
    }

    /**
     * Converts the complete input frames in {@code b[off..off+len)}; a trailing
     * partial frame is retained and prepended to the next call. Returns the
     * converted bytes (possibly length 0).
     */
    byte[] convert(byte[] b, int off, int len) {
        int total = carryLen + len;
        int frames = total / inFrameSize;
        int usable = frames * inFrameSize;
        byte[] out = new byte[frames * outFrameSize()];

        // Combined view: carry prefix (if any) followed by b[off..off+len).
        byte[] combined = b;
        int base = off;
        if (carryLen > 0) {
            combined = new byte[total];
            System.arraycopy(carry, 0, combined, 0, carryLen);
            System.arraycopy(b, off, combined, carryLen, len);
            base = 0;
        }

        int p = base;
        int o = 0;
        for (int fr = 0; fr < frames; fr++) {
            for (int ch = 0; ch < channels; ch++) {
                short s;
                if (bits8) {
                    int raw = combined[p++];
                    s = (short) (unsigned ? ((raw & 0xFF) - 128) << 8 : raw << 8);
                } else {
                    // Interpret the pair per source endianness, then emit LE.
                    int b0 = combined[p++] & 0xFF;
                    int b1 = combined[p++] & 0xFF;
                    int v = swap ? (b0 << 8) | b1 : (b1 << 8) | b0;
                    if (unsigned) {
                        v = (v - 32768) & 0xFFFF;
                    }
                    s = (short) v;
                }
                out[o++] = (byte) s;
                out[o++] = (byte) (s >> 8);
            }
        }

        int rem = total - usable;
        if (rem > 0) {
            if (carry == null || carry.length < rem) {
                carry = new byte[inFrameSize];
            }
            System.arraycopy(combined, base + usable, carry, 0, rem);
            carryLen = rem;
        } else {
            carryLen = 0;
        }
        return out;
    }
}
