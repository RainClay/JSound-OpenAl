package com.jsound.openal;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Owns the single OpenAL device/context and the bridge worker thread that is
 * current on that context. Every AL call runs on the worker; mod threads only
 * enqueue tasks, push PCM, or set control flags.
 *
 * <p>The bridge opens its <em>own</em> device/context rather than reusing the
 * game's, so it never races the Minecraft render thread's OpenAL use.
 */
public final class OpenALCore {

    static final OpenALCore INSTANCE = new OpenALCore();

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Integer, Voice> voices = new ConcurrentHashMap<>();
    private final Object wakeMonitor = new Object();
    private final Thread worker;

    private volatile boolean shutdown;

    private OpenALCore() {
        worker = new Thread(this::runWorker, "jsound-openal-worker");
        worker.setDaemon(true);
        worker.start();
    }

    static OpenALCore instance() {
        return INSTANCE;
    }

    /* ------------------------------------------------------------------ */
    /* Worker                                                              */
    /* ------------------------------------------------------------------ */

    private void runWorker() {
        if (!setupContext()) {
            // Fatal for playback: keep worker alive as a no-op so no caller blocks forever.
            synchronized (wakeMonitor) {
                while (!shutdown) {
                    try {
                        wakeMonitor.wait(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            return;
        }

        while (!shutdown) {
            drainTasks();

            Iterator<Voice> it = voices.values().iterator();
            while (it.hasNext()) {
                Voice v = it.next();
                try {
                    if (!v.tick()) {
                        it.remove();
                        v.free();
                        Log.debug("[jsound-openal] voice removed src=" + v.sourceId());
                    }
                } catch (Throwable t) {
                    it.remove();
                    Log.error("[jsound-openal] voice crashed src=" + v.sourceId() + ": " + t);
                    try {
                        v.free();
                    } catch (Throwable ignored) {
                    }
                    t.printStackTrace();
                }
            }

            synchronized (wakeMonitor) {
                if (tasks.isEmpty() && voices.isEmpty()) {
                    try {
                        wakeMonitor.wait(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private boolean setupContext() {
        try {
            long device = ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
            if (device == 0L) {
                Log.error("[jsound-openal] Failed to open OpenAL device");
                return false;
            }
            long context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0L) {
                Log.error("[jsound-openal] Failed to create OpenAL context");
                ALC10.alcCloseDevice(device);
                return false;
            }
            if (!ALC10.alcMakeContextCurrent(context)) {
                Log.error("[jsound-openal] Failed to make OpenAL context current");
                ALC10.alcDestroyContext(context);
                ALC10.alcCloseDevice(device);
                return false;
            }
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            AL.createCapabilities(alcCaps);
            Log.info("[jsound-openal] context ready: device=" + device + " context=" + context);
            return true;
        } catch (Throwable t) {
            Log.error("[jsound-openal] OpenAL setup failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private void drainTasks() {
        Runnable t;
        while ((t = tasks.poll()) != null) {
            try {
                t.run();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    void wake() {
        synchronized (wakeMonitor) {
            wakeMonitor.notifyAll();
        }
    }

    /** Runs a task on the worker (context current) and waits for completion. */
    void runWorkerSync(Runnable task) {
        if (Thread.currentThread() == worker) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        tasks.add(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        wake();
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs a factory on the worker, registers the resulting voice, returns it. */
    <T extends Voice> T register(Callable<T> factory) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        tasks.add(() -> {
            try {
                T v = factory.call();
                voices.put(v.sourceId(), v);
                out[0] = v;
            } catch (Throwable t) {
                err[0] = t;
            } finally {
                latch.countDown();
            }
        });
        wake();
        if (Thread.currentThread() != worker) {
            latch.await(10, TimeUnit.SECONDS);
        }
        if (err[0] != null) {
            throw new RuntimeException("Failed to create OpenAL voice", err[0]);
        }
        @SuppressWarnings("unchecked")
        T v = (T) out[0];
        return v;
    }

    /* ------------------------------------------------------------------ */
    /* Default-mixer pinning                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Pins Java Sound's default mixers to this bridge via the documented
     * system properties ("javax.sound.sampled.SourceDataLine" / "Clip" /
     * "TargetDataLine"). AudioSystem consults these before any provider-order
     * fallback, so default lines always come from this implementation even
     * when a launcher ships a duplicate bridge (Zalith2 integrates one into
     * its lwjgl jar and plain ServiceLoader discovers it first).
     *
     * <p>History note: this method used to reflectively edit an
     * {@code AudioSystem.mixers} field — which does not exist in any modern
     * JDK, so it silently did nothing. Discovery always actually worked
     * because {@code ServiceLoader} consults the thread context classloader,
     * which under Forge/Fabric is the mod classloader and does see our
     * {@code META-INF/services} file. Idempotent; does not touch OpenAL.
     */
    public static void registerFallback() {
        pinDefaultMixer("javax.sound.sampled.SourceDataLine");
        pinDefaultMixer("javax.sound.sampled.Clip");
        pinDefaultMixer("javax.sound.sampled.TargetDataLine");
    }

    private static void pinDefaultMixer(String key) {
        // Full "providerClassName#mixerName" form: AudioSystem matches the
        // provider by class name against the already-loaded provider list and
        // the mixer by name inside it — completely independent of the order in
        // which ServiceLoader discovered the providers. A bare mixer name would
        // be misparsed as a provider class name (getDefaultMixer).
        String value = JSoundMixerProvider.class.getName() + "#" + JSoundMixer.INFO.getName();
        String existing = System.getProperty(key);
        // Respect an explicitly configured desktop mixer, but take priority
        // over launcher-shipped duplicates (a competing jsound variant that
        // copied our provider/mixer name shares the property harmlessly).
        if (existing != null && !existing.equals(value) && !existing.contains("jsound")) {
            Log.debug("[jsound-openal] keeping existing " + key + "=" + existing);
            return;
        }
        System.setProperty(key, value);
    }
}
