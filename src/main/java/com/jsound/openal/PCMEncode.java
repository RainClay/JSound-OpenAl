package com.jsound.openal;

import javax.sound.sampled.AudioFormat;

/**
 * Inverse of {@link PCMConvert}: encodes the canonical signed 16-bit
 * little-endian PCM that OpenAL capture delivers into whatever variant a mod
 * requested on its {@code TargetDataLine} (8-bit, big-endian, unsigned).
 * Instances are stateless and safe to call from any thread.
 */
final class PCMEncode {

    private final boolean bits8;
    private final boolean unsigned;
    private final boolean swap; // target is big-endian 16-bit
    private final int channels;

    private PCMEncode(AudioFormat target) {
        this.bits8 = target.getSampleSizeInBits() == 8;
        this.unsigned = target.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
        this.swap = !bits8 && target.isBigEndian();
        this.channels = target.getChannels();
    }

    static boolean needsEncoding(AudioFormat f) {
        return PCMConvert.needsConversion(f);
    }

    /** Encoder for {@code f}, or null when the request is already canonical. */
    static PCMEncode forFormat(AudioFormat f) {
        return needsEncoding(f) ? new PCMEncode(f) : null;
    }

    /** One-shot whole-buffer encoding (used by tests and full-buffer paths). */
    static byte[] encodeAll(byte[] canonical, int offset, int len, AudioFormat f) {
        return new PCMEncode(f).encode(canonical, offset, len);
    }

    /** Bytes per frame in the target variant. */
    int outFrameSize() {
        return bits8 ? channels : channels * 2;
    }

    /** Encodes canonical 16-bit LE bytes {@code b[off..off+len)} (whole frames). */
    byte[] encode(byte[] b, int off, int len) {
        int canonicalFrame = channels * 2;
        int frames = len / canonicalFrame;
        int usable = frames * canonicalFrame;
        byte[] out = new byte[frames * outFrameSize()];

        int o = 0;
        for (int i = 0; i < usable; i += 2) {
            int v = (b[off + i] & 0xFF) | (b[off + i + 1] << 8); // LE pair -> signed sample
            if (bits8) {
                int s8 = (v >> 8) & 0xFF; // arithmetic shift, 0..255 covers -128..127
                out[o++] = (byte) (unsigned ? s8 + 128 : s8);
            } else {
                if (unsigned) {
                    v = (v + 32768) & 0xFFFF;
                }
                if (swap) {
                    out[o++] = (byte) (v >> 8);
                    out[o++] = (byte) v;
                } else {
                    out[o++] = (byte) v;
                    out[o++] = (byte) (v >> 8);
                }
            }
        }
        return out;
    }
}
