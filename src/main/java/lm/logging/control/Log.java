package lm.logging.control;

import java.io.PrintStream;

import lm.configuration.control.ZCfg;

public enum Log {

    ERROR(Color.RED, System.err),
    SYSTEM(Color.BLUE, System.out),
    DEBUG(Color.VIOLET, System.out),
    SUCCESS(Color.MAGENTA, System.out),
    HTTP(Color.CYAN, System.out),
    PROGRESS(Color.CYAN, System.err);

    private final PrintStream out;
    private final String value;
    private static final String RESET = "[0m";

    enum Color {
        RED("[38;2;220;50;47m"),
        MAGENTA("[38;2;211;54;130m"),
        VIOLET("[38;2;108;113;196m"),
        BLUE("[38;2;38;139;210m"),
        CYAN("[38;2;42;161;152m");

        final String code;

        Color(String code) {
            this.code = code;
        }
    }

    Log(Color color, PrintStream out) {
        this.value = (color.code + "%s" + RESET);
        this.out = out;
    }

    String formatted(String raw) {
        return this.value.formatted(raw);
    }

    private static volatile boolean progressActive;

    void out(String message) {
        if (progressActive) {
            PROGRESS.out.println();
            progressActive = false;
        }
        this.out.println(formatted(message));
    }

    public static void error(String message) {
        Log.ERROR.out(message);
    }

    public static void error(String message, Exception e) {
        Log.ERROR.out(message + ": " + e.getMessage());
        e.printStackTrace(System.err);
    }

    public static void system(String message) {
        Log.SYSTEM.out(message);
    }

    public static void debug(String message) {
        if (!isDebugMode()) return;
        Log.DEBUG.out(message);
    }

    public static boolean isDebugMode() {
        return ZCfg.bool("debug", false);
    }

    public static void success(String message) {
        Log.SUCCESS.out(message);
    }

    public static void http(String message) {
        Log.HTTP.out(message);
    }

    public static void progress() {
        if (!isProgressEnabled()) return;
        progressActive = true;
        PROGRESS.out.print(PROGRESS.formatted("."));
        PROGRESS.out.flush();
    }

    public static boolean isProgressEnabled() {
        return ZCfg.bool("progress", true);
    }

    public static void progressDone() {
        if (!progressActive) return;
        PROGRESS.out.println();
        progressActive = false;
    }
}
