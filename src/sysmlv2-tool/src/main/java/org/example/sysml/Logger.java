package org.example.sysml;

import java.io.PrintWriter;
import java.io.StringWriter;

public class Logger {
    public enum Level { 
        NONE(0), ERROR(1), WARN(2), INFO(3), DEBUG(4);
        final int value;
        Level(int value) { this.value = value; }
    }

    private static final Level CURRENT_LEVEL;

    static {
        String prop = System.getProperty("sysmlv2tool.loglevel", "warn").toUpperCase();
        Level detected;
        try {
            detected = Level.valueOf(prop);
        } catch (IllegalArgumentException e) {
            detected = Level.WARN; 
        }
        CURRENT_LEVEL = detected;
    }

    // --- NEU: Getter für das aktuelle Level ---
    public static Level getLevel() {
        return CURRENT_LEVEL;
    }

    // --- NEU: Komfort-Abfragen (wie in Profi-Frameworks) ---
    public static boolean isDebugEnabled() { return CURRENT_LEVEL.value >= Level.DEBUG.value; }
    public static boolean isInfoEnabled()  { return CURRENT_LEVEL.value >= Level.INFO.value; }
    public static boolean isWarnEnabled()  { return CURRENT_LEVEL.value >= Level.WARN.value; }
    public static boolean isErrorEnabled() { return CURRENT_LEVEL.value >= Level.ERROR.value; }

    // --- Bestehende Methoden ---
    public static void error(String msg, Object... args) {
        if (isErrorEnabled()) System.err.printf("[ERROR] " + msg + "%n", args);
    }

    public static void warn(String msg, Object... args) {
        if (isWarnEnabled()) System.err.printf("[WARN]  " + msg + "%n", args);
    }

    public static void info(String msg, Object... args) {
        if (isInfoEnabled()) System.out.printf("[INFO]  " + msg + "%n", args);
    }
    
    public static void debug(String msg, Object... args) {
        if (isDebugEnabled()) System.out.printf("[DEBUG] " + msg + "%n", args);
    }

    public static void error(String msg, Throwable t) {
    if (isErrorEnabled()) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
    
        error("%s%n%s", msg, sw.toString());
    }
}
}