package io.github.tmessssy.chatgptbackfix;

/**
 * Preference keys shared by the settings screen (module's own process) and the hook (ChatGPT's process).
 * Plain constants only: this class must stay safe to load in both processes.
 */
final class Prefs {

    static final String FILE = "settings";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_CONFIRM_EXIT = "confirm_exit";
    static final String KEY_DEBUG = "debug";
    static final String KEY_EXTRA_LABELS = "extra_labels";
    static final String KEY_KEEP_KEYBOARD_CLOSED = "keep_keyboard_closed";

    static final boolean DEF_ENABLED = true;
    static final boolean DEF_CONFIRM_EXIT = true;
    static final boolean DEF_DEBUG = false;
    static final String DEF_EXTRA_LABELS = "";
    static final boolean DEF_KEEP_KEYBOARD_CLOSED = true;

    private Prefs() {
    }
}
