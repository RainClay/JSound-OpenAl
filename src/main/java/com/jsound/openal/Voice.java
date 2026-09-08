package com.jsound.openal;

/**
 * A single OpenAL source managed by the bridge worker thread. All OpenAL calls
 * happen on the worker thread (with the context current); mod threads only push
 * PCM into a bounded queue and set volatile control flags.
 *
 * <p>{@code tick} is invoked repeatedly on the worker thread. Returning
 * {@code false} signals the worker that the voice is finished and should be
 * removed from the registry, after which {@link #free()} releases its OpenAL
 * resources.
 */
interface Voice {

    /** The OpenAL source id; also used as the registry key. */
    int sourceId();

    /** Worker thread, context current. Returns false when done/closed. */
    boolean tick();

    /** Worker thread. Deletes the OpenAL source and buffers. */
    void free();

    /**
     * Any thread. Sets the source's linear gain (0..1); applied on the worker on
     * the next tick. Backs MASTER_GAIN / MUTE controls.
     */
    void setGain(float linear);
}
