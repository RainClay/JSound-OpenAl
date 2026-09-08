package com.jsound.openal;

/**
 * Central logging gate. Periodic/diagnostic output is suppressed unless the
 * {@code jsound.openal.debug} system property is {@code true}; error paths
 * always print. One-shot lifecycle lines use {@link #info}.
 */
final class Log {

    static final boolean DEBUG = Boolean.getBoolean("jsound.openal.debug");

    private Log() {
    }

    /** Diagnostic/periodic lines; printed only with {@code -Djsound.openal.debug=true}. */
    static void debug(String msg) {
        if (DEBUG) {
            System.err.println(msg);
        }
    }

    /** One-shot lifecycle lines (context ready, etc.). */
    static void info(String msg) {
        System.err.println(msg);
    }

    /** Error paths; always printed. */
    static void error(String msg) {
        System.err.println(msg);
    }
}
