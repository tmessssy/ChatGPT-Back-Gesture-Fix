package io.github.tmessssy.chatgptbackfix;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Reads the module's settings from inside ChatGPT's process (LSPosed shared preferences).
 * Falls back to defaults whenever the file is missing or unreadable, so the module always works.
 */
final class ModuleConfig {

    private static final long RECHECK_INTERVAL_MS = 1000;

    private static XSharedPreferences prefs;
    private static long lastCheckAt;
    private static String extraLabelsRaw;
    private static List<String> extraLabels = Collections.emptyList();

    private ModuleConfig() {
    }

    static void init() {
        try {
            prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE);
            if (!prefs.getFile().canRead()) {
                L.i("settings not saved yet (open the module once to create them); using defaults");
            }
        } catch (Throwable t) {
            prefs = null;
            L.e("shared preferences unavailable; using defaults", t);
        }
    }

    static boolean enabled() {
        return bool(Prefs.KEY_ENABLED, Prefs.DEF_ENABLED);
    }

    static boolean confirmExit() {
        return bool(Prefs.KEY_CONFIRM_EXIT, Prefs.DEF_CONFIRM_EXIT);
    }

    static boolean keepKeyboardClosed() {
        return bool(Prefs.KEY_KEEP_KEYBOARD_CLOSED, Prefs.DEF_KEEP_KEYBOARD_CLOSED);
    }

    static boolean debug() {
        return bool(Prefs.KEY_DEBUG, Prefs.DEF_DEBUG);
    }

    /** User-supplied extra labels for the sidebar button, normalized for matching. */
    static List<String> extraLabels() {
        maybeReload();
        String raw = Prefs.DEF_EXTRA_LABELS;
        try {
            if (prefs != null) raw = prefs.getString(Prefs.KEY_EXTRA_LABELS, Prefs.DEF_EXTRA_LABELS);
        } catch (Throwable ignored) {
        }
        if (raw == null) raw = "";
        if (!raw.equals(extraLabelsRaw)) {
            List<String> parsed = new ArrayList<>();
            for (String part : raw.split(",")) {
                String norm = Text.forMatching(part);
                if (!norm.isEmpty()) parsed.add(norm);
            }
            extraLabelsRaw = raw;
            extraLabels = Collections.unmodifiableList(parsed);
        }
        return extraLabels;
    }

    private static boolean bool(String key, boolean def) {
        maybeReload();
        try {
            return prefs == null ? def : prefs.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void maybeReload() {
        if (prefs == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastCheckAt < RECHECK_INTERVAL_MS) return;
        lastCheckAt = now;
        try {
            if (prefs.hasFileChanged()) prefs.reload();
        } catch (Throwable ignored) {
        }
    }
}
